// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Command x9-qrcode is the Go build of the X9.150 Payment QR Code service (ADR-0022). main is the
// wiring and the start-up order, top to bottom; nothing runs before it says so.
package main

import (
	"context"
	"crypto/x509"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"slices"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/clock"
	"github.com/matera-inc/x9.150-qrcode/internal/config"
	"github.com/matera-inc/x9.150-qrcode/internal/database"
	"github.com/matera-inc/x9.150-qrcode/internal/health"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/httplog"
	"github.com/matera-inc/x9.150-qrcode/internal/jws"
	"github.com/matera-inc/x9.150-qrcode/internal/keys"
	"github.com/matera-inc/x9.150-qrcode/internal/testclock"
)

// Stamped at build time with -ldflags "-X main.version=… -X main.commit=…".
var (
	version = "dev"
	commit  = "unknown"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	if err := run(ctx, os.Args[1:], os.Environ(), os.Stdout); err != nil {
		// The logger may not exist yet (a bad configuration), so this line is written as JSON here.
		httplog.NewLogger(os.Stderr, slog.LevelInfo).Error("x9-qrcode stopped", "err", err)
		os.Exit(1)
	}
}

// run starts the service and returns when ctx is cancelled (SIGTERM) and shutdown has finished, or
// at once with the reason it cannot start.
func run(ctx context.Context, args, env []string, stdout io.Writer) error {
	flags := flag.NewFlagSet("x9-qrcode", flag.ContinueOnError)
	flags.SetOutput(stdout)
	configFile := flags.String("config", lookup(env, "X9_CONFIG_FILE"), "the YAML configuration file (or X9_CONFIG_FILE)")
	showVersion := flags.Bool("version", false, "print the version and exit")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if *showVersion {
		_, err := fmt.Fprintf(stdout, "x9-qrcode %s (%s)\n", version, commit)
		return err
	}

	// ── 1. Configuration, then the logger it configures ──────────────────────────────────────
	cfg, err := config.Load(*configFile, env)
	if err != nil {
		return err
	}
	logger := httplog.NewLogger(stdout, cfg.Log.SlogLevel())
	slog.SetDefault(logger)
	logger.Info("x9-qrcode starting", "version", version, "commit", commit, "database", cfg.Database,
		"certificate", cfg.Certificate)

	// ── 1b. The signing keys and the truststore, re-read on a timer until shutdown ──────────
	keyStore, err := keys.Load(cfg.Certificate, logger, keys.WithPKCS12(keys.SSLMatePKCS12{}))
	if err != nil {
		return fmt.Errorf("loading the signing keys: %w", err)
	}
	// The configured alg must be one the key can produce (Java refuses the same at start).
	if err := jws.CheckKey(cfg.Certificate.JWKAlgorithm, keyStore.Current().Identity); err != nil {
		return fmt.Errorf("x9.certificate.jwk-algorithm (X9_CERTIFICATE_JWKALGORITHM) and the signing key: %w", err)
	}
	reloadCtx, stopReload := context.WithCancel(ctx)
	reloading := make(chan struct{})
	go func() {
		defer close(reloading)
		keyStore.Run(reloadCtx, time.Duration(cfg.Keystore.Reload.Frequency)*time.Millisecond)
	}()
	defer func() {
		stopReload()
		<-reloading
	}()

	// ── 1c. The business clock (ADR-0033). It can be moved only with X9_TESTING_CLOCKCONTROL=true
	// and a bundled signing certificate, over a listener of its own. Certificate validity is never
	// judged on it: internal/pki gets clock.New(), which has no offset.
	businessClock := clock.New()
	if cfg.Testing.ClockControl {
		businessClock = clock.New(clock.AllowOffset())
		clockListener, err := testclock.Start(ctx, cfg.Testing.ClockListen, businessClock,
			func() *x509.Certificate { return keyStore.Current().Identity.Certificate() }, logger)
		if err != nil {
			return fmt.Errorf("starting the test clock: %w", err)
		}
		defer func() {
			if err := clockListener.Shutdown(context.WithoutCancel(ctx)); err != nil {
				logger.Error("x9-qrcode test clock listener did not stop cleanly", "err", err)
			}
		}()
	}

	// ── 2. Database pools (they connect lazily) and the health probes ─────────────────────────
	pools, err := database.Open(ctx, cfg.Database)
	if err != nil {
		return fmt.Errorf("opening the database pools: %w", err)
	}
	probes := health.New(pools)

	// ── 3. HTTP server: liveness UP from now on, readiness OUT_OF_SERVICE until the schema check passes ──
	// Every API answer carries X-Test-Clock-Offset while the business clock is moved.
	// The routes of Epics 9 and 10 are registered on api (internal/httpapi), which answers what no
	// route takes, and request headers past 30 KB, as the Java build does (S9.2-AC1, S9.2-AC8).
	api := httpapi.New(logger, businessClock.Now)
	probes.Register(api.Mux())
	srv := newServer(cfg.Server.Port, httplog.Middleware(logger, testclock.OffsetHeader(businessClock, api)))
	listener, err := (&net.ListenConfig{}).Listen(ctx, "tcp", srv.Addr)
	if err != nil {
		pools.Close()
		return fmt.Errorf("listening on %s: %w", srv.Addr, err)
	}
	served := make(chan error, 1)
	go func() { served <- srv.Serve(listener) }()
	logger.Info("x9-qrcode listening", "addr", listener.Addr().String())

	// ── 4. The schema check (ADR-0032), retried while the database is unreachable ────────────
	runCtx, stopRun := context.WithCancel(ctx)
	defer stopRun()
	checked := make(chan error, 1)
	go func() { checked <- awaitSchema(runCtx, logger, pools, cfg.Database.Schema) }()

	// ── 5. Run until SIGTERM, a refused schema, or a server failure ──────────────────────────
	var runErr error
	for running := true; running; {
		select {
		case err := <-served:
			served, runErr, running = nil, fmt.Errorf("serving: %w", err), false
		case err := <-checked:
			checked = nil
			if err != nil && runCtx.Err() == nil {
				runErr, running = err, false
			} else if err == nil {
				probes.SetReady(true)
				logger.Info("x9-qrcode ready", "schema", cfg.Database.Schema, "changeset", database.ExpectedChangeset)
			}
		case <-ctx.Done():
			running = false
		}
	}
	stopRun()
	if checked != nil {
		if err := <-checked; err != nil {
			logger.Debug("x9-qrcode schema check cut short by the shutdown", "err", err)
		}
	}

	// ── 6. Shut down (ADR-0031) ──────────────────────────────────────────────────────────────
	delay := time.Duration(cfg.Server.ShutdownDelaySeconds) * time.Second
	if runErr != nil {
		delay = 0 // never ready, or no longer serving: nobody is routed here
	}
	if err := shutdown(context.WithoutCancel(ctx), logger, srv, served, probes, pools, delay); err != nil {
		runErr = errors.Join(runErr, err)
	}
	if runErr != nil {
		return runErr
	}
	logger.Info("x9-qrcode stopped")
	return nil
}

// shutdown follows ADR-0031: readiness turns OUT_OF_SERVICE; after delay, so that load balancers have seen
// it, the listener stops accepting; in-flight requests get up to drainTimeout to finish; then the
// pools close. ctx must outlive the shutdown (context.WithoutCancel). served is the Serve goroutine's result, or nil if it already returned.
func shutdown(ctx context.Context, logger *slog.Logger, srv *http.Server, served <-chan error, probes *health.Health,
	pools *database.Pools, delay time.Duration,
) error {
	probes.SetReady(false)
	logger.Info("x9-qrcode shutting down", "readiness", "OUT_OF_SERVICE", "delayMs", delay.Milliseconds())
	if delay > 0 {
		_ = sleep(ctx, delay)
	}
	ctx, cancel := context.WithTimeout(ctx, drainTimeout)
	defer cancel()
	var err error
	if e := srv.Shutdown(ctx); e != nil {
		_ = srv.Close()
		err = fmt.Errorf("in-flight requests did not finish within %v: %w", drainTimeout, e)
	}
	if served != nil {
		if e := <-served; !errors.Is(e, http.ErrServerClosed) {
			err = errors.Join(err, e)
		}
	}
	pools.Close()
	return err
}

// awaitSchema runs the schema check until it passes, retrying while the database cannot be
// reached. A database that answers with the wrong schema or changelog, or refuses the
// credentials, is not retried: the service refuses to start (ADR-0032).
func awaitSchema(ctx context.Context, logger *slog.Logger, pools *database.Pools, schema string) error {
	delay := time.Second
	for {
		err := database.CheckSchema(ctx, pools.Read, schema, database.ExpectedChangeset)
		if err == nil || ctx.Err() != nil {
			return err
		}
		if !database.Unreachable(err) {
			return err
		}
		logger.Warn("x9-qrcode database not reachable yet", "err", err, "retryInMs", delay.Milliseconds())
		if err := sleep(ctx, delay); err != nil {
			return err
		}
		delay = min(delay*2, 10*time.Second)
	}
}

// sleep waits d or until ctx ends. It measures a real retry delay, not business time.
func sleep(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d) //nolint:forbidigo // a real retry delay, not business time
	defer t.Stop()
	select {
	case <-t.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// drainTimeout is how long in-flight requests may take to finish after SIGTERM (ADR-0031).
const drainTimeout = 20 * time.Second

// newServer applies ADR-0031's timeouts. The write timeout is above the 30 s long-poll ceiling.
func newServer(port int, h http.Handler) *http.Server {
	return &http.Server{
		Addr:              ":" + strconv.Itoa(port),
		Handler:           h,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      40 * time.Second,
		IdleTimeout:       120 * time.Second,
		// net/http answers a header past this limit itself, with a 431 the Java build never gives;
		// httpapi refuses past 30 KB as Tomcat does (a 400), so this stays well above it.
		MaxHeaderBytes: 1 << 20,
	}
}

func lookup(env []string, name string) string {
	for _, kv := range slices.Backward(env) {
		if k, v, ok := strings.Cut(kv, "="); ok && k == name {
			return v
		}
	}
	return ""
}
