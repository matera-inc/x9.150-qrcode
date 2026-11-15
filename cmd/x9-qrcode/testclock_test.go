// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/testclock"
)

// clockEnv is a configuration whose database is never reached (port 1): these tests are about
// the listeners, which start before the schema check.
func clockEnv(apiPort, clockPort int, extra ...string) []string {
	return append([]string{
		"DATABASE_HOST=127.0.0.1", "DATABASE_PORT=1", "DATABASE_NAME=bank", "DATABASE_USER=app", "DATABASE_PASSWORD=pw",
		"DATABASE_SSLMODE=disable", "X9_SERVER_PORT=" + strconv.Itoa(apiPort), "X9_SERVER_SHUTDOWNDELAYSECONDS=0",
		"X9_TESTING_CLOCKLISTEN=127.0.0.1:" + strconv.Itoa(clockPort),
	}, extra...)
}

// call sends one request and returns its status, the X-Test-Clock-Offset header (and whether it
// was there) and the body, or err when nothing answers.
func call(ctx context.Context, method, url, body string) (status int, offset string, present bool, out []byte, err error) {
	var r io.Reader
	if body != "" {
		r = strings.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, url, r)
	if err != nil {
		return 0, "", false, nil, err
	}
	client := &http.Client{Timeout: 2 * time.Second}
	defer client.CloseIdleConnections()
	resp, err := client.Do(req)
	if err != nil {
		return 0, "", false, nil, err
	}
	defer func() { _ = resp.Body.Close() }()
	out, err = io.ReadAll(resp.Body)
	v, present := resp.Header[testclock.Header]
	if present {
		offset = v[0]
	}
	return resp.StatusCode, offset, present, out, err
}

// startRun runs the service until the test ends, and waits until the API answers.
func startRun(t *testing.T, env []string) (*syncBuffer, func()) {
	t.Helper()
	ctx, cancel := context.WithCancel(t.Context())
	var out syncBuffer
	done := make(chan error, 1)
	go func() { done <- run(ctx, nil, env, &out) }()
	var once sync.Once
	stop := func() {
		once.Do(func() {
			cancel()
			if err := <-done; err != nil {
				t.Errorf("run returned %v after SIGTERM", err)
			}
		})
	}
	waitForLog(t, &out, "x9-qrcode listening")
	return &out, stop
}

// S8.6-AC3: with the flag off (the default) nothing listens on the clock address, /test/clock is
// not on the API port, and no answer carries X-Test-Clock-Offset.
func TestTestClockOffByDefault(t *testing.T) {
	apiPort, clockPort := freePort(t.Context(), t), freePort(t.Context(), t)
	out, stop := startRun(t, clockEnv(apiPort, clockPort))
	defer stop()
	api := "http://127.0.0.1:" + strconv.Itoa(apiPort)

	if _, _, _, _, err := call(t.Context(), http.MethodGet, "http://127.0.0.1:"+strconv.Itoa(clockPort)+testclock.Path, ""); err == nil {
		t.Fatal("something answers on the clock address with the flag off")
	}
	if status, _, _, _, err := call(t.Context(), http.MethodPut, api+testclock.Path, `{"offsetSeconds": 60}`); err != nil || status != http.StatusNotFound {
		t.Fatalf("PUT /test/clock on the API port: %d %v, want 404", status, err)
	}
	if _, offset, present, _, err := call(t.Context(), http.MethodGet, api+"/actuator/health/liveness", ""); err != nil || present {
		t.Fatalf("health: header %q present %v, err %v", offset, present, err)
	}
	if strings.Contains(out.String(), "TEST CLOCK CONTROL IS ON") {
		t.Fatalf("a test clock WARN with the flag off:\n%s", out.String())
	}
}

// S8.6-AC1, S8.6-AC2: with the flag on and the bundled demo identity, the clock is served on its
// own listener, with a WARN; while it is moved every API answer carries X-Test-Clock-Offset, in
// milliseconds; it is gone again after DELETE; and /test/clock is never on the API port.
func TestTestClockOn(t *testing.T) {
	apiPort, clockPort := freePort(t.Context(), t), freePort(t.Context(), t)
	out, stop := startRun(t, clockEnv(apiPort, clockPort, "X9_TESTING_CLOCKCONTROL=true"))
	defer stop()
	api := "http://127.0.0.1:" + strconv.Itoa(apiPort)
	clk := "http://127.0.0.1:" + strconv.Itoa(clockPort) + testclock.Path

	if !strings.Contains(out.String(), `"level":"WARN","msg":"TEST CLOCK CONTROL IS ON`) {
		t.Fatalf("no startup WARN:\n%s", out.String())
	}
	if status, _, _, _, err := call(t.Context(), http.MethodGet, api+testclock.Path, ""); err != nil || status != http.StatusNotFound {
		t.Fatalf("GET /test/clock on the API port: %d %v, want 404", status, err)
	}

	target := time.Now().Add(24 * time.Hour).UTC().Truncate(time.Millisecond) //nolint:forbidigo // the real clock, to move from
	status, _, _, body, err := call(t.Context(), http.MethodPut, clk, `{"at": "`+target.Format("2006-01-02T15:04:05.000Z")+`"}`)
	if err != nil || status != http.StatusOK {
		t.Fatalf("PUT: %d %s %v", status, body, err)
	}
	var state struct {
		Now          string `json:"now"`
		OffsetMillis int64  `json:"offsetMillis"`
	}
	if err := json.Unmarshal(body, &state); err != nil {
		t.Fatal(err)
	}
	now, err := time.Parse(time.RFC3339, state.Now)
	if err != nil || now.Before(target) || now.Sub(target) > time.Second {
		t.Fatalf("now = %q after PUT at %v (%v)", state.Now, target, err)
	}

	_, offset, present, _, err := call(t.Context(), http.MethodGet, api+"/actuator/health/liveness", "")
	ms, convErr := strconv.ParseInt(offset, 10, 64)
	if err != nil || !present || convErr != nil || ms < 86_395_000 || ms > 86_400_000 {
		t.Fatalf("health while moved: header %q present %v, err %v", offset, present, err)
	}
	// Every API answer, a 404 included.
	if _, _, present, _, _ := call(t.Context(), http.MethodGet, api+"/nothing-here", ""); !present {
		t.Fatal("a 404 of the API lacks the header while the clock is moved")
	}

	if status, _, _, body, err := call(t.Context(), http.MethodDelete, clk, ""); err != nil || status != http.StatusOK ||
		!strings.Contains(string(body), `"offsetMillis":0`) {
		t.Fatalf("DELETE: %d %s %v", status, body, err)
	}
	if _, offset, present, _, _ := call(t.Context(), http.MethodGet, api+"/actuator/health/liveness", ""); present {
		t.Fatalf("header %q after DELETE", offset)
	}

	stop()
	if _, _, _, _, err := call(context.WithoutCancel(t.Context()), http.MethodGet, clk, ""); err == nil {
		t.Fatal("the clock listener still answers after shutdown")
	}
}

// S8.6-AC2: with the flag on, start-up refuses a signing certificate that is not bundled, before
// any listener exists. The certificate is generated here, never committed.
func TestTestClockRefusesARealCertificate(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "real.example.com"},
		NotBefore:    time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC),
		NotAfter:     time.Date(2036, 1, 1, 0, 0, 0, 0, time.UTC),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, key.Public(), key)
	if err != nil {
		t.Fatal(err)
	}
	pkcs8, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "real.pem")
	pemData := append(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: pkcs8}),
		pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})...)
	if err := os.WriteFile(path, pemData, 0o600); err != nil {
		t.Fatal(err)
	}

	apiPort, clockPort := freePort(t.Context(), t), freePort(t.Context(), t)
	// The key is EC P-256, so the algorithm is ES256: the refusal must come from the clock guard,
	// not from the signing check (Story 8.4) that runs before it.
	env := clockEnv(apiPort, clockPort, "X9_TESTING_CLOCKCONTROL=true", "X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION="+path,
		"X9_CERTIFICATE_JWKALGORITHM=ES256")
	err = run(t.Context(), nil, env, io.Discard)
	if err == nil || !strings.Contains(err.Error(), "not a bundled certificate") || !strings.Contains(err.Error(), "CN=real.example.com") {
		t.Fatalf("err = %v, want a refusal naming the certificate", err)
	}

	// The same key starts with the flag off: only the clock is refused.
	_, stop := startRun(t, clockEnv(apiPort, clockPort, "X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION="+path,
		"X9_CERTIFICATE_JWKALGORITHM=ES256"))
	stop()
}
