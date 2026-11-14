// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package main

import (
	"context"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/config"
	"github.com/matera-inc/x9.150-qrcode/internal/database"
	"github.com/matera-inc/x9.150-qrcode/internal/dbtest"
	"github.com/matera-inc/x9.150-qrcode/internal/health"
)

// proxy forwards TCP to the database and can cut it off, so a test can make the database
// unreachable and bring it back without restarting it.
type proxy struct {
	ln     net.Listener
	target string
	mu     sync.Mutex
	up     bool
	conns  map[net.Conn]struct{}
	wg     sync.WaitGroup
}

func startProxy(ctx context.Context, t *testing.T, target string) *proxy {
	t.Helper()
	ln, err := (&net.ListenConfig{}).Listen(ctx, "tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	p := &proxy{ln: ln, target: target, up: true, conns: map[net.Conn]struct{}{}}
	p.wg.Go(func() { p.accept(ctx) })
	t.Cleanup(func() {
		_ = ln.Close()
		p.setUp(false)
		p.wg.Wait()
	})
	return p
}

func (p *proxy) port() int { return p.ln.Addr().(*net.TCPAddr).Port }

func (p *proxy) accept(ctx context.Context) {
	for {
		c, err := p.ln.Accept()
		if err != nil {
			return
		}
		p.mu.Lock()
		if !p.up {
			p.mu.Unlock()
			_ = c.Close()
			continue
		}
		p.conns[c] = struct{}{}
		p.mu.Unlock()
		p.wg.Go(func() { p.pipe(ctx, c) })
	}
}

func (p *proxy) pipe(ctx context.Context, c net.Conn) {
	defer p.drop(c)
	d, err := (&net.Dialer{}).DialContext(ctx, "tcp", p.target)
	if err != nil {
		return
	}
	p.mu.Lock()
	p.conns[d] = struct{}{}
	p.mu.Unlock()
	defer p.drop(d)
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(d, c); done <- struct{}{} }()
	go func() { _, _ = io.Copy(c, d); done <- struct{}{} }()
	<-done
	_ = c.Close()
	_ = d.Close()
	<-done
}

func (p *proxy) drop(c net.Conn) {
	_ = c.Close()
	p.mu.Lock()
	delete(p.conns, c)
	p.mu.Unlock()
}

// setUp lets connections through, or refuses new ones and cuts every open one.
func (p *proxy) setUp(up bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.up = up
	if !up {
		for c := range p.conns {
			_ = c.Close()
		}
	}
}

func probe(ctx context.Context, t *testing.T, port int, path string) (int, string) {
	t.Helper()
	client := &http.Client{Timeout: 5 * time.Second}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://127.0.0.1:"+strconv.Itoa(port)+path, nil)
	if err != nil {
		t.Fatal(err)
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, err.Error()
	}
	defer func() { _ = resp.Body.Close() }()
	b, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, string(b)
}

// eventually polls until the probe answers want, or fails the test.
func eventually(ctx context.Context, t *testing.T, port int, path string, want int) {
	t.Helper()
	var code int
	var body string
	for range 300 {
		if code, body = probe(ctx, t, port, path); code == want {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("%s answered %d %s, want %d", path, code, body, want)
}

// S7.3-AC1: readiness is OUT_OF_SERVICE until the schema check passes and while the database is
// unreachable; liveness is UP throughout.
func TestReadinessFollowsTheDatabase(t *testing.T) {
	db := dbtest.Start(t)
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	px := startProxy(ctx, t, net.JoinHostPort(db.Host, strconv.Itoa(db.Port)))
	px.setUp(false)

	port := freePort(ctx, t)
	env := dbEnv(db, port)
	env = append(env, "DATABASE_HOST=127.0.0.1", "DATABASE_PORT="+strconv.Itoa(px.port()))
	var out syncBuffer
	done := make(chan error, 1)
	go func() { done <- run(ctx, nil, env, &out) }()

	// The database cannot be reached yet: alive, not ready.
	eventually(ctx, t, port, "/actuator/health/liveness", http.StatusOK)
	if code, body := probe(ctx, t, port, "/actuator/health/readiness"); code != http.StatusServiceUnavailable || body != `{"status":"OUT_OF_SERVICE"}` {
		t.Fatalf("readiness before the schema check: %d %s", code, body)
	}
	if code, body := probe(ctx, t, port, "/actuator/health"); code != http.StatusServiceUnavailable ||
		body != `{"status":"DOWN","groups":["liveness","readiness"]}` {
		t.Fatalf("health before the schema check: %d %s", code, body)
	}

	// The database comes up: the schema check passes, and the service is ready.
	px.setUp(true)
	eventually(ctx, t, port, "/actuator/health/readiness", http.StatusOK)
	if code, body := probe(ctx, t, port, "/actuator/health"); code != http.StatusOK ||
		body != `{"status":"UP","groups":["liveness","readiness"]}` {
		t.Fatalf("health when ready: %d %s", code, body)
	}

	// The database goes away: readiness OUT_OF_SERVICE, liveness still UP; and back.
	px.setUp(false)
	eventually(ctx, t, port, "/actuator/health/readiness", http.StatusServiceUnavailable)
	if code, _ := probe(ctx, t, port, "/actuator/health/liveness"); code != http.StatusOK {
		t.Fatalf("liveness while the database is down: %d", code)
	}
	px.setUp(true)
	eventually(ctx, t, port, "/actuator/health/readiness", http.StatusOK)

	cancel()
	if err := <-done; err != nil {
		t.Fatalf("run returned %v", err)
	}
}

// S7.3-AC2: on shutdown, readiness goes OUT_OF_SERVICE, a request already in flight finishes, and the pools
// close.
func TestShutdownLetsInFlightRequestsFinish(t *testing.T) {
	ctx := t.Context()
	pools, err := database.Open(ctx, config.Database{
		Host: "127.0.0.1", Port: 1, Name: "x", User: "x", Password: "x", SSLMode: "disable", PoolWrites: 1, PoolReads: 1,
	})
	if err != nil {
		t.Fatal(err)
	}
	probes := health.New(okPinger{})
	probes.SetReady(true)

	started := make(chan struct{})
	mux := http.NewServeMux()
	probes.Register(mux)
	mux.HandleFunc("GET /slow", func(w http.ResponseWriter, _ *http.Request) {
		close(started)
		time.Sleep(300 * time.Millisecond)
		_, _ = w.Write([]byte("finished"))
	})
	srv := newServer(0, mux)
	ln, err := (&net.ListenConfig{}).Listen(ctx, "tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	served := make(chan error, 1)
	go func() { served <- srv.Serve(ln) }()

	slow := make(chan string, 1)
	go func() {
		code, body := probe(ctx, t, port, "/slow")
		slow <- strconv.Itoa(code) + " " + body
	}()
	<-started

	stopped := make(chan error, 1)
	go func() {
		stopped <- shutdown(ctx, slog.New(slog.DiscardHandler), srv, served, probes, pools, 200*time.Millisecond)
	}()
	// During the delay readiness is OUT_OF_SERVICE, and the server still answers.
	eventually(ctx, t, port, "/actuator/health/readiness", http.StatusServiceUnavailable)

	if got := <-slow; got != "200 finished" {
		t.Fatalf("the in-flight request got %q", got)
	}
	if err := <-stopped; err != nil {
		t.Fatalf("shutdown: %v", err)
	}
	if err := pools.Ping(ctx); err == nil || !strings.Contains(err.Error(), "closed") {
		t.Fatalf("the pools are still open: %v", err)
	}
}

type okPinger struct{}

func (okPinger) Ping(context.Context) error { return nil }

// S7.3-AC2: the real binary, sent SIGTERM: readiness OUT_OF_SERVICE during the delay, then exit 0.
func TestSIGTERMExitsZero(t *testing.T) {
	db := dbtest.Start(t)
	ctx := t.Context()
	bin := filepath.Join(t.TempDir(), "x9-qrcode")
	build := exec.CommandContext(ctx, "go", "build", "-o", bin, ".") //nolint:gosec // G204: builds this package into the test's own temp dir.
	if out, err := build.CombinedOutput(); err != nil {
		t.Fatalf("go build: %v\n%s", err, out)
	}

	port := freePort(ctx, t)
	env := append(dbEnv(db, port), "X9_SERVER_SHUTDOWNDELAYSECONDS=1")
	cmd := exec.CommandContext(ctx, bin) //nolint:gosec // G204: the binary this test just built.
	cmd.Env = env
	var out syncBuffer
	cmd.Stdout, cmd.Stderr = &out, &out
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	eventually(ctx, t, port, "/actuator/health/readiness", http.StatusOK)

	if err := cmd.Process.Signal(syscall.SIGTERM); err != nil {
		t.Fatal(err)
	}
	eventually(ctx, t, port, "/actuator/health/readiness", http.StatusServiceUnavailable)
	if code, _ := probe(ctx, t, port, "/actuator/health/liveness"); code != http.StatusOK {
		t.Fatalf("liveness during the shutdown delay: %d", code)
	}

	if err := cmd.Wait(); err != nil {
		t.Fatalf("exit: %v\n%s", err, out.String())
	}
	if code := cmd.ProcessState.ExitCode(); code != 0 {
		t.Fatalf("exit code %d\n%s", code, out.String())
	}
	if !strings.Contains(out.String(), `"msg":"x9-qrcode stopped"`) {
		t.Fatalf("no clean stop in the log:\n%s", out.String())
	}
}
