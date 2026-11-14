// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Command x9-qrcode is the Go build of the X9.150 Payment QR Code service (ADR-0022). main is the
// wiring and the start-up order, top to bottom; nothing runs before it says so.
package main

import (
	"context"
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

	"github.com/matera-inc/x9.150-qrcode/internal/config"
	"github.com/matera-inc/x9.150-qrcode/internal/database"
	"github.com/matera-inc/x9.150-qrcode/internal/httplog"
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
	logger.Info("x9-qrcode starting", "version", version, "commit", commit, "database", cfg.Database)

	// ── 2. HTTP server ───────────────────────────────────────────────────────────────────────
	mux := http.NewServeMux()
	srv := newServer(cfg.Server.Port, httplog.Middleware(logger, mux))
	listener, err := (&net.ListenConfig{}).Listen(ctx, "tcp", srv.Addr)
	if err != nil {
		return fmt.Errorf("listening on %s: %w", srv.Addr, err)
	}
	served := make(chan error, 1)
	go func() { served <- srv.Serve(listener) }()
	logger.Info("x9-qrcode listening", "addr", listener.Addr().String())

	// ── 3. Database: two pools, then the schema check (ADR-0032) ─────────────────────────────
	pools, err := database.Open(ctx, cfg.Database)
	if err != nil {
		_ = srv.Close()
		return fmt.Errorf("opening the database pools: %w", err)
	}
	runCtx, stopRun := context.WithCancel(ctx)
	defer stopRun()
	checked := make(chan error, 1)
	go func() { checked <- awaitSchema(runCtx, logger, pools, cfg.Database.Schema) }()

	// ── 4. Run until SIGTERM, a refused schema, or a server failure ──────────────────────────
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
				logger.Info("x9-qrcode schema checked", "schema", cfg.Database.Schema, "changeset", database.ExpectedChangeset)
			}
		case <-ctx.Done():
			running = false
		}
	}

	// ── 5. Shut down: stop accepting, let in-flight requests finish, then close the pools ───────
	logger.Info("x9-qrcode shutting down")
	stopRun()
	if checked != nil {
		if err := <-checked; err != nil {
			logger.Debug("x9-qrcode schema check cut short by the shutdown", "err", err)
		}
	}
	shutdownCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), drainTimeout)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		_ = srv.Close()
		runErr = errors.Join(runErr, fmt.Errorf("shutting down: %w", err))
	}
	if served != nil {
		if err := <-served; !errors.Is(err, http.ErrServerClosed) {
			runErr = errors.Join(runErr, err)
		}
	}
	pools.Close()
	if runErr != nil {
		return runErr
	}
	logger.Info("x9-qrcode stopped")
	return nil
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
		MaxHeaderBytes:    30 << 10, // 30 KB, as the Java build
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
