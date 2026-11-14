// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package main

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

// syncBuffer is an io.Writer the server goroutine and the test can share.
type syncBuffer struct {
	mu sync.Mutex
	b  strings.Builder
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

func freePort(t *testing.T) int {
	t.Helper()
	l, err := (&net.ListenConfig{}).Listen(t.Context(), "tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = l.Close() }()
	return l.Addr().(*net.TCPAddr).Port
}

// S7.1-AC1: a missing required key is a refusal at start, naming the key.
func TestRunRefusesAnIncompleteConfiguration(t *testing.T) {
	err := run(t.Context(), nil, []string{"DATABASE_HOST=db"}, io.Discard)
	if err == nil || !strings.Contains(err.Error(), "database.sslmode (DATABASE_SSLMODE)") {
		t.Fatalf("err = %v", err)
	}
}

// S7.1-AC2: Spring-style configuration is refused, naming the converter.
func TestRunRefusesSpringConfiguration(t *testing.T) {
	err := run(t.Context(), nil, []string{"SPRING_CONFIG_LOCATION=/config/application.yml"}, io.Discard)
	if err == nil || !strings.Contains(err.Error(), "x9-config-convert") {
		t.Fatalf("err = %v", err)
	}
}

func TestVersionFlag(t *testing.T) {
	var out strings.Builder
	if err := run(t.Context(), []string{"-version"}, nil, &out); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(out.String(), "x9-qrcode dev") {
		t.Fatalf("version output %q", out.String())
	}
}

// S7.1-AC3: every log line is JSON, and a request's traceparent appears in its log line.
func TestRunLogsJSONWithTraceIDs(t *testing.T) {
	port := freePort(t)
	env := []string{
		"DATABASE_HOST=db", "DATABASE_NAME=bank", "DATABASE_USER=app", "DATABASE_PASSWORD=pw",
		"DATABASE_SSLMODE=disable", "X9_SERVER_PORT=" + strconv.Itoa(port),
	}
	ctx, cancel := context.WithCancel(t.Context())
	var out syncBuffer
	done := make(chan error, 1)
	go func() { done <- run(ctx, nil, env, &out) }()

	url := "http://127.0.0.1:" + strconv.Itoa(port) + "/nothing-here"
	client := &http.Client{Timeout: 2 * time.Second}
	var resp *http.Response
	for range 100 {
		req, _ := http.NewRequestWithContext(t.Context(), http.MethodGet, url, nil)
		req.Header.Set("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
		var err error
		if resp, err = client.Do(req); err == nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if resp == nil {
		t.Fatal("the server never answered")
	}
	_ = resp.Body.Close()
	cancel()
	if err := <-done; err != nil {
		t.Fatalf("run returned %v after SIGTERM", err)
	}

	var sawRequest bool
	sc := bufio.NewScanner(strings.NewReader(out.String()))
	for sc.Scan() {
		var line map[string]any
		if err := json.Unmarshal(sc.Bytes(), &line); err != nil {
			t.Fatalf("a log line is not JSON: %q", sc.Text())
		}
		if line["msg"] == "request" {
			sawRequest = true
			if line["traceId"] != "4bf92f3577b34da6a3ce929d0e0e4736" || line["spanId"] != "00f067aa0ba902b7" {
				t.Errorf("request line without the trace ids: %s", sc.Text())
			}
		}
		if strings.Contains(sc.Text(), "\"pw\"") {
			t.Errorf("the password was logged: %s", sc.Text())
		}
	}
	if !sawRequest {
		t.Fatalf("no request line in:\n%s", out.String())
	}
}
