// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/database"
	"github.com/matera-inc/x9.150-qrcode/internal/dbtest"
	"github.com/matera-inc/x9.150-qrcode/internal/jws"
	"github.com/matera-inc/x9.150-qrcode/internal/keys"
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

func freePort(ctx context.Context, t *testing.T) int {
	t.Helper()
	l, err := (&net.ListenConfig{}).Listen(ctx, "tcp", "127.0.0.1:0")
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

// S12.1-AC1: a QR Code retention shorter than the events' stops the service at start, naming both.
func TestRunRefusesAQRCodeRetentionShorterThanTheEvents(t *testing.T) {
	err := run(t.Context(), nil, []string{
		"DATABASE_HOST=db", "DATABASE_NAME=bank", "DATABASE_USER=app", "DATABASE_PASSWORD=pw", "DATABASE_SSLMODE=disable",
		"X9_EVENTS_RETENTION=P30D", "X9_QRCODE_RETENTION=P7D",
	}, io.Discard)
	if err == nil || !strings.Contains(err.Error(), "qrcode.retention (X9_QRCODE_RETENTION): P7D is shorter than events.retention (X9_EVENTS_RETENTION) P30D") {
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
	port := freePort(t.Context(), t)
	env := []string{
		"DATABASE_HOST=127.0.0.1", "DATABASE_PORT=1", "DATABASE_NAME=bank", "DATABASE_USER=app", "DATABASE_PASSWORD=pw",
		"DATABASE_SSLMODE=disable", "X9_SERVER_PORT=" + strconv.Itoa(port), "X9_SERVER_SHUTDOWNDELAYSECONDS=0",
	}
	ctx, cancel := context.WithCancel(t.Context())
	var out syncBuffer
	done := make(chan error, 1)
	go func() { done <- run(ctx, nil, env, &out) }()

	url := "http://127.0.0.1:" + strconv.Itoa(port) + "/nothing-here"
	client := &http.Client{Timeout: 2 * time.Second}
	var resp *http.Response
	for range 100 {
		req, err := http.NewRequestWithContext(t.Context(), http.MethodGet, url, nil)
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
		if resp, err = client.Do(req); err == nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if resp == nil {
		t.Fatal("the server never answered")
	}
	body, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	// S9.2-AC1: the API's routes sit behind httplog, and an unknown path is Spring's 404.
	if resp.StatusCode != http.StatusNotFound || !strings.Contains(string(body), `"error":"Not Found","path":"/nothing-here"`) {
		t.Errorf("an unknown path: %d %s, want Spring's 404", resp.StatusCode, body)
	}
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
	// S8.2-AC4: with no keystore configured, the bundled demo identity signs, and a WARN says it is
	// NON-PRODUCTION.
	if !strings.Contains(out.String(), keys.DemoFingerprint) || !strings.Contains(out.String(), "NON-PRODUCTION") {
		t.Errorf("no demo identity warning in:\n%s", out.String())
	}
}

// S8.2-AC1: a JKS keystore is refused at start, naming the conversion; the database is never
// reached.
func TestRunRefusesAJKSTruststore(t *testing.T) {
	env := []string{
		"DATABASE_HOST=127.0.0.1", "DATABASE_PORT=1", "DATABASE_NAME=bank", "DATABASE_USER=app", "DATABASE_PASSWORD=pw",
		"DATABASE_SSLMODE=disable",
		"X9_CERTIFICATE_TRUSTSTORE_LOCATION=file:../../x9-qrcode-infrastructure/src/test/resources/certificate/x9-test-truststore.jks",
	}
	err := run(t.Context(), nil, env, io.Discard)
	if err == nil || !errors.Is(err, keys.ErrUnsupportedFormat) || !strings.Contains(err.Error(), "x9-config-convert") {
		t.Fatalf("err = %v, want a JKS refusal naming x9-config-convert", err)
	}
}

// S8.4-AC1: an algorithm the signing key cannot produce stops the service at start, naming the
// setting and both halves, as the Java build does; the database is never reached.
func TestRunRefusesAnAlgorithmTheKeyCannotProduce(t *testing.T) {
	env := []string{
		"DATABASE_HOST=127.0.0.1", "DATABASE_PORT=1", "DATABASE_NAME=bank", "DATABASE_USER=app", "DATABASE_PASSWORD=pw",
		"DATABASE_SSLMODE=disable", "X9_CERTIFICATE_JWKALGORITHM=ES256",
	}
	err := run(t.Context(), nil, env, io.Discard)
	if err == nil || !errors.Is(err, jws.ErrKey) || !strings.Contains(err.Error(), "X9_CERTIFICATE_JWKALGORITHM") ||
		!strings.Contains(err.Error(), "ES256 needs an EC key, the key is RSA") {
		t.Fatalf("err = %v, want a refusal naming X9_CERTIFICATE_JWKALGORITHM and the RSA demo key", err)
	}
}

// S9.6-AC4: with no public host the service starts on the placeholder, and a WARN says that QR
// Codes printed with it cannot be scanned, naming X9_PUBLICENDPOINTS_HOST; a real host gives no
// such line. An algorithm the key cannot produce stops each run right after the line is due, so
// the database is never reached.
func TestRunWarnsOfThePlaceholderHost(t *testing.T) {
	base := []string{
		"DATABASE_HOST=127.0.0.1", "DATABASE_PORT=1", "DATABASE_NAME=bank", "DATABASE_USER=app", "DATABASE_PASSWORD=pw",
		"DATABASE_SSLMODE=disable", "X9_CERTIFICATE_JWKALGORITHM=ES256",
	}
	for _, tc := range []struct {
		name string
		env  []string
		warn bool
	}{
		{"placeholder", base, true},
		{"a real host", append(slices.Clone(base), "X9_PUBLICENDPOINTS_HOST=pay.bank.example"), false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var out syncBuffer
			if err := run(t.Context(), nil, tc.env, &out); !errors.Is(err, jws.ErrKey) {
				t.Fatalf("err = %v, want the key refusal that ends the run", err)
			}
			var warned bool
			sc := bufio.NewScanner(strings.NewReader(out.String()))
			for sc.Scan() {
				var line map[string]any
				if err := json.Unmarshal(sc.Bytes(), &line); err != nil {
					t.Fatalf("a log line is not JSON: %q", sc.Text())
				}
				msg, _ := line["msg"].(string)
				if strings.Contains(msg, "cannot be scanned") {
					warned = true
					if line["level"] != "WARN" || !strings.Contains(msg, "X9_PUBLICENDPOINTS_HOST") ||
						line["host"] != "x9-150.example.com" {
						t.Errorf("the placeholder warning: %s", sc.Text())
					}
				}
			}
			if warned != tc.warn {
				t.Fatalf("warned = %v, want %v:\n%s", warned, tc.warn, out.String())
			}
		})
	}
}

func dbEnv(db *dbtest.DB, port int) []string {
	return []string{
		"DATABASE_HOST=" + db.Host, "DATABASE_PORT=" + strconv.Itoa(db.Port), "DATABASE_NAME=postgres",
		"DATABASE_USER=" + dbtest.AppRole, "DATABASE_PASSWORD=unused", "DATABASE_SSLMODE=disable",
		"X9_SERVER_PORT=" + strconv.Itoa(port), "X9_SERVER_SHUTDOWNDELAYSECONDS=0",
	}
}

// waitForLog waits until the log holds msg, or fails the test.
func waitForLog(t *testing.T, out *syncBuffer, msg string) {
	t.Helper()
	for range 500 {
		if strings.Contains(out.String(), `"msg":"`+msg+`"`) {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("no %q in the log:\n%s", msg, out.String())
}

func TestRunAgainstPostgres(t *testing.T) {
	db := dbtest.Start(t)

	// S7.2-AC2: the right schema and changelog: the service starts, and stops cleanly.
	t.Run("starts on the right schema", func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		var out syncBuffer
		done := make(chan error, 1)
		env := dbEnv(db, freePort(ctx, t))
		go func() { done <- run(ctx, nil, env, &out) }()
		waitForLog(t, &out, "x9-qrcode ready")
		cancel()
		if err := <-done; err != nil {
			t.Fatalf("run returned %v", err)
		}
	})

	// S12.1-AC4, S12.2-AC1: once ready, retention runs at once as the application role (its DELETE
	// grants included), logs one line and counts it at /actuator/prometheus; on shutdown it stops
	// before the pools close.
	t.Run("runs retention and stops it", func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		var out syncBuffer
		done := make(chan error, 1)
		port := freePort(ctx, t)
		go func() { done <- run(ctx, nil, append(dbEnv(db, port), "X9_RETENTION_INTERVAL=PT1S"), &out) }()
		waitForLog(t, &out, "x9-qrcode retention run")
		code, body := probe(ctx, t, port, "/actuator/prometheus")
		if code != http.StatusOK || !strings.Contains(body, `x9_retention_deleted_rows_total{table="qr_code"} 0`) ||
			!strings.Contains(body, "x9_retention_runs_failed_total 0") {
			t.Fatalf("/actuator/prometheus: %d\n%s", code, body)
		}
		cancel()
		if err := <-done; err != nil {
			t.Fatalf("run returned %v", err)
		}
		log := out.String()
		stopped, closed := strings.Index(log, `"msg":"x9-qrcode retention stopped"`), strings.Index(log, `"msg":"x9-qrcode stopped"`)
		if stopped < 0 || closed < stopped {
			t.Fatalf("retention did not stop before the service:\n%s", log)
		}
		if strings.Contains(log, "retention run failed") {
			t.Fatalf("a retention run failed:\n%s", log)
		}
	})

	// S7.2-AC2: current_schema() differs from the configured schema: the service refuses to start.
	t.Run("refuses another schema", func(t *testing.T) {
		env := append(dbEnv(db, freePort(t.Context(), t)), "DATABASE_SCHEMA=bank_x9")
		err := run(t.Context(), nil, env, io.Discard)
		if !errors.Is(err, database.ErrWrongSchema) || !strings.Contains(err.Error(), "bank_x9") {
			t.Fatalf("err = %v", err)
		}
	})

	// A refused credential is an answer, not an outage: no retrying.
	t.Run("refuses an unknown role", func(t *testing.T) {
		env := append(dbEnv(db, freePort(t.Context(), t)), "DATABASE_USER=nobody_here")
		err := run(t.Context(), nil, env, io.Discard)
		if err == nil || !strings.Contains(err.Error(), "nobody_here") {
			t.Fatalf("err = %v", err)
		}
	})
}
