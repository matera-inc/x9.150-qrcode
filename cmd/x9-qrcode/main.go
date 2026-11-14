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
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/config"
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

	// ── 3. Run until SIGTERM, then shut down ─────────────────────────────────────────────────
	select {
	case err := <-served:
		return fmt.Errorf("serving: %w", err)
	case <-ctx.Done():
	}
	logger.Info("x9-qrcode shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), drainTimeout)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		_ = srv.Close()
		return fmt.Errorf("shutting down: %w", err)
	}
	if err := <-served; !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	logger.Info("x9-qrcode stopped")
	return nil
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
	for i := len(env) - 1; i >= 0; i-- {
		if k, v, ok := strings.Cut(env[i], "="); ok && k == name {
			return v
		}
	}
	return ""
}
