// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package testclock

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log/slog"
	"math/big"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/clock"
	"github.com/matera-inc/x9.150-qrcode/internal/keys"
	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

const repoRoot = "../.."

// loadCert reads the first certificate of a PEM file.
func loadCert(path string) (*x509.Certificate, error) {
	data, err := os.ReadFile(path) //nolint:gosec // G304: a fixture of this repository
	if err != nil {
		return nil, err
	}
	for {
		var block *pem.Block
		block, data = pem.Decode(data)
		if block == nil {
			return nil, fmt.Errorf("%s holds no certificate", path)
		}
		if block.Type == "CERTIFICATE" {
			return x509.ParseCertificate(block.Bytes)
		}
	}
}

func readCertPEM(t *testing.T, path string) *x509.Certificate {
	t.Helper()
	cert, err := loadCert(path)
	if err != nil {
		t.Fatal(err)
	}
	return cert
}

func demoCertificate(t *testing.T) *x509.Certificate {
	t.Helper()
	return readCertPEM(t, filepath.Join(repoRoot, "internal/keys/bundled/x9-demo.pem"))
}

// freshCertificate is generated here, never committed: the only kind of certificate the refusal
// is tested with (ADR-0033, note of 2026-11-14).
func freshCertificate(t *testing.T) *x509.Certificate {
	t.Helper()
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
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return cert
}

// S8.6-AC2: the demo certificate and the test-CA certificates in the golden set are bundled; the
// demo fingerprint is the one internal/keys knows.
func TestBundledCertificatesPassTheGuard(t *testing.T) {
	if !IsBundled(keys.DemoFingerprint) {
		t.Fatal("the demo fingerprint is not bundled")
	}
	paths := []string{filepath.Join(repoRoot, "internal/keys/bundled/x9-demo.pem")}
	golden, err := filepath.Glob(filepath.Join(repoRoot, "others/blackbox/golden/pki/*.pem"))
	if err != nil || len(golden) == 0 {
		t.Fatalf("no golden PKI certificates: %v", err)
	}
	for _, path := range append(paths, golden...) {
		if err := RequireBundled(readCertPEM(t, path)); err != nil {
			t.Errorf("%s: %v", path, err)
		}
	}
}

// S8.6-AC2: every fingerprint the Java build accepts is accepted here too, so a deployment that
// starts on Java starts on Go.
func TestJavaFingerprintsAreBundled(t *testing.T) {
	path := filepath.Join(repoRoot,
		"x9-qrcode-infrastructure/src/main/java/com/matera/x9qrcode/infrastructure/testclock/TestClockCertificateGuard.java")
	src, err := os.ReadFile(path) //nolint:gosec // G304: a source file of this repository
	if err != nil {
		t.Fatal(err)
	}
	found := regexp.MustCompile(`"([0-9a-f]{64})"`).FindAllStringSubmatch(string(src), -1)
	if len(found) < 7 {
		t.Fatalf("found only %d fingerprints in %s", len(found), path)
	}
	for _, m := range found {
		if !IsBundled(m[1]) {
			t.Errorf("Java accepts %s, Go does not", m[1])
		}
	}
}

// S8.6-AC2: any other certificate is refused, naming its subject and fingerprint; so is none.
func TestOtherCertificatesAreRefused(t *testing.T) {
	cert := freshCertificate(t)
	err := RequireBundled(cert)
	if err == nil || !strings.Contains(err.Error(), Fingerprint(cert)) || !strings.Contains(err.Error(), "CN=real.example.com") ||
		!strings.Contains(err.Error(), "turn X9_TESTING_CLOCKCONTROL off") {
		t.Fatalf("err = %v", err)
	}
	if err := RequireBundled(nil); err == nil || !strings.Contains(err.Error(), "no signing certificate") {
		t.Fatalf("nil certificate: err = %v", err)
	}
}

// syncBuffer is a log sink the server goroutines and the test can share.
type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
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

type fixture struct {
	clock   *clock.Clock
	cert    atomic.Pointer[x509.Certificate]
	handler http.Handler
	log     *syncBuffer
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	f := &fixture{clock: clock.New(clock.AllowOffset()), log: &syncBuffer{}}
	f.cert.Store(demoCertificate(t))
	f.handler = Handler(f.clock, f.cert.Load, slog.New(slog.NewJSONHandler(f.log, nil)))
	return f
}

type answer struct {
	status int
	header http.Header
	body   map[string]any
}

func (f *fixture) send(t *testing.T, method, path, body string) answer {
	t.Helper()
	var r io.Reader
	if body != "" {
		r = strings.NewReader(body)
	}
	req := httptest.NewRequestWithContext(t.Context(), method, path, r)
	rec := httptest.NewRecorder()
	f.handler.ServeHTTP(rec, req)
	a := answer{status: rec.Code, header: rec.Header()}
	if err := json.Unmarshal(rec.Body.Bytes(), &a.body); err != nil {
		t.Fatalf("%s %s: the answer is not JSON: %q", method, path, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("Content-Type = %q", ct)
	}
	return a
}

func (a answer) now(t *testing.T) time.Time {
	t.Helper()
	text, _ := a.body["now"].(string)
	if !strings.HasSuffix(text, "Z") {
		t.Fatalf("now = %q, want an instant in UTC ending in Z", text)
	}
	now, err := time.Parse(time.RFC3339, text)
	if err != nil {
		t.Fatal(err)
	}
	return now
}

func (a answer) offset(t *testing.T) int64 {
	t.Helper()
	v, ok := a.body["offsetMillis"].(float64)
	if !ok {
		t.Fatalf("no offsetMillis in %v", a.body)
	}
	return int64(v)
}

// S8.6-AC1: PUT an offset in seconds, GET it back in milliseconds, DELETE it.
func TestPutOffsetSecondsThenGetThenDelete(t *testing.T) {
	f := newFixture(t)

	put := f.send(t, http.MethodPut, Path, `{"offsetSeconds": 86400}`)
	if put.status != http.StatusOK || put.offset(t) != 86_400_000 || f.clock.OffsetMillis() != 86_400_000 {
		t.Fatalf("PUT: %+v, clock offset %d", put, f.clock.OffsetMillis())
	}
	got := f.send(t, http.MethodGet, Path, "")
	if got.status != http.StatusOK || got.offset(t) != 86_400_000 {
		t.Fatalf("GET: %+v", got)
	}
	if d := got.now(t).Sub(time.Now().Add(24 * time.Hour)); d.Abs() > 2*time.Second { //nolint:forbidigo // the real clock, to compare with
		t.Fatalf("now is %v from real time plus a day", d)
	}
	deleted := f.send(t, http.MethodDelete, Path, "")
	if deleted.status != http.StatusOK || deleted.offset(t) != 0 || f.clock.OffsetMillis() != 0 {
		t.Fatalf("DELETE: %+v", deleted)
	}
	if log := f.log.String(); !strings.Contains(log, `"msg":"test clock moved"`) ||
		!strings.Contains(log, `"msg":"test clock reset to real time"`) || !strings.Contains(log, `"level":"WARN"`) {
		t.Fatalf("moves are not logged as WARN:\n%s", log)
	}
}

// S8.6-AC1: PUT at keeps its milliseconds, and the clock never reads earlier than it.
func TestPutAtKeepsMilliseconds(t *testing.T) {
	f := newFixture(t)
	for _, text := range []string{"2031-12-31T23:59:59.999Z", "2031-12-31T23:59:50Z", "2031-12-31T20:59:59.999-03:00"} {
		at, err := time.Parse(time.RFC3339, text)
		if err != nil {
			t.Fatal(err)
		}
		put := f.send(t, http.MethodPut, Path, `{"at": "`+text+`"}`)
		if put.status != http.StatusOK {
			t.Fatalf("%s: %+v", text, put)
		}
		if now := put.now(t); now.Before(at) || now.Sub(at) > 500*time.Millisecond {
			t.Fatalf("%s: now = %v", text, now)
		}
	}
}

// S8.6-AC1: the offset can be set in milliseconds, negative included.
func TestPutOffsetMillis(t *testing.T) {
	f := newFixture(t)
	put := f.send(t, http.MethodPut, Path, `{"offsetMillis": -1001}`)
	if put.status != http.StatusOK || put.offset(t) != -1001 || f.clock.OffsetMillis() != -1001 {
		t.Fatalf("PUT: %+v", put)
	}
}

// S8.6-AC1: a null field is absent, as Jackson's hasNonNull reads it.
func TestNullFieldsAreAbsent(t *testing.T) {
	f := newFixture(t)
	put := f.send(t, http.MethodPut, Path, `{"at": null, "offsetSeconds": null, "offsetMillis": 5}`)
	if put.status != http.StatusOK || put.offset(t) != 5 {
		t.Fatalf("PUT: %+v", put)
	}
}

// S8.6-AC1: a bad PUT is a 400 that says why, in the Java build's words, and leaves the clock
// where it was.
func TestABadPutIsRefusedAndLeavesTheClockAlone(t *testing.T) {
	f := newFixture(t)
	if err := f.clock.SetOffsetMillis(7); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct{ body, want string }{
		{"", "send exactly one of"},
		{"{}", "send exactly one of"},
		{"[]", "send exactly one of"},
		{"5", "send exactly one of"},
		{`{"at": "2031-12-31T23:59:50Z", "offsetSeconds": 1}`, "send exactly one of"},
		{`{"offsetMillis": 1, "offsetSeconds": 1}`, "send exactly one of"},
		{"not json", "body must be JSON"},
		{`{"at": "tomorrow"}`, "at must be an RFC 3339 instant"},
		{`{"at": 5}`, "at must be an RFC 3339 instant"},
		{`{"at": "2031-12-31T23:59:50.0005Z"}`, "at must be given to the millisecond at most"},
		{`{"at": "9999-12-31T23:59:59Z"}`, "the offset must be within ±3155760000000 milliseconds"},
		{`{"offsetSeconds": 1.5}`, "offsetSeconds must be an integer"},
		{`{"offsetSeconds": 1e3}`, "offsetSeconds must be an integer"},
		{`{"offsetSeconds": "60"}`, "offsetSeconds must be an integer"},
		{`{"offsetMillis": 1.5}`, "offsetMillis must be an integer"},
		{`{"offsetMillis": 1.0}`, "offsetMillis must be an integer"},
		{`{"offsetMillis": 99999999999999999999}`, "offsetMillis must be an integer"},
		{`{"offsetSeconds": 999999999999}`, "the offset must be within"},
		{`{"offsetMillis": 999999999999999}`, "the offset must be within"},
		{`{"offsetSeconds": 9223372036854775807}`, "offsetSeconds is too large"},
		{`{"offsetSeconds": -9223372036854775808}`, "offsetSeconds is too large"},
		{`{"offsetMillis": ` + strings.Repeat("1", maxBody) + `}`, "body must be JSON"},
	} {
		a := f.send(t, http.MethodPut, Path, tc.body)
		if msg, _ := a.body["error"].(string); a.status != http.StatusBadRequest || !strings.Contains(msg, tc.want) {
			t.Errorf("PUT %.60q: %d %v, want 400 %q", tc.body, a.status, a.body, tc.want)
		}
	}
	if f.clock.OffsetMillis() != 7 {
		t.Fatalf("a refused PUT moved the clock to %d", f.clock.OffsetMillis())
	}
}

// S8.6-AC1: other methods are 405 with Allow; other paths are 404.
func TestOtherMethodsAndPathsAreRefused(t *testing.T) {
	f := newFixture(t)
	for _, m := range []string{http.MethodPost, http.MethodPatch, http.MethodHead} {
		req := httptest.NewRequestWithContext(t.Context(), m, Path, nil)
		rec := httptest.NewRecorder()
		f.handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusMethodNotAllowed || rec.Header().Get("Allow") != "GET, PUT, DELETE" {
			t.Errorf("%s: %d, Allow %q", m, rec.Code, rec.Header().Get("Allow"))
		}
	}
	for _, p := range []string{"/test/clock/extra", "/", "/actuator/health"} {
		if a := f.send(t, http.MethodGet, p, ""); a.status != http.StatusNotFound {
			t.Errorf("GET %s: %d", p, a.status)
		}
	}
}

// S8.6-AC2: a real certificate swapped in by a reload stops the clock moving: PUT is 403.
func TestACertificateSwappedForARealOneStopsTheClockMoving(t *testing.T) {
	f := newFixture(t)
	f.cert.Store(freshCertificate(t))
	a := f.send(t, http.MethodPut, Path, `{"offsetSeconds": 60}`)
	if msg, _ := a.body["error"].(string); a.status != http.StatusForbidden || !strings.Contains(msg, "not a bundled certificate") {
		t.Fatalf("PUT: %+v", a)
	}
	if f.clock.OffsetMillis() != 0 {
		t.Fatalf("the clock moved: %d", f.clock.OffsetMillis())
	}
}

// S8.6-AC1: now is written as Java's Instant.toString writes it, to the millisecond.
func TestFormatInstant(t *testing.T) {
	for _, tc := range []struct {
		at   time.Time
		want string
	}{
		{time.Date(2031, 12, 31, 23, 59, 50, 0, time.UTC), "2031-12-31T23:59:50Z"},
		{time.Date(2031, 12, 31, 23, 59, 59, 999_999_999, time.UTC), "2031-12-31T23:59:59.999Z"},
		{time.Date(2031, 12, 31, 23, 59, 59, 100_000_000, time.UTC), "2031-12-31T23:59:59.100Z"},
		{time.Date(2031, 12, 31, 23, 59, 59, 999, time.UTC), "2031-12-31T23:59:59Z"},
		{time.Date(2031, 12, 31, 20, 0, 0, 0, time.FixedZone("-03", -3*3600)), "2031-12-31T23:00:00Z"},
	} {
		if got := formatInstant(tc.at); got != tc.want {
			t.Errorf("formatInstant(%v) = %s, want %s", tc.at, got, tc.want)
		}
	}
}

// S8.6-AC2: X-Test-Clock-Offset is on every answer while the offset is not zero, in signed
// milliseconds, and absent once it is zero again.
func TestOffsetHeader(t *testing.T) {
	c := clock.New(clock.AllowOffset())
	h := OffsetHeader(c, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusTeapot)
	}))
	header := func() (string, bool) {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/actuator/health", nil))
		v, ok := rec.Header()[Header]
		if !ok {
			return "", false
		}
		return v[0], true
	}
	if v, ok := header(); ok {
		t.Fatalf("header %q with no offset", v)
	}
	for _, ms := range []int64{86_400_000, -3_600_000, 1} {
		if err := c.SetOffsetMillis(ms); err != nil {
			t.Fatal(err)
		}
		if v, ok := header(); !ok || v != strconv.FormatInt(ms, 10) {
			t.Fatalf("offset %d: header %q, %v", ms, v, ok)
		}
	}
	if err := c.SetOffsetMillis(0); err != nil {
		t.Fatal(err)
	}
	if v, ok := header(); ok {
		t.Fatalf("header %q after reset", v)
	}
}

// S8.6-AC1, S8.6-AC2: the listener serves the clock on an address of its own and logs a WARN;
// shutting it down puts the clock back on real time.
func TestStartServesAndShutdownResets(t *testing.T) {
	c := clock.New(clock.AllowOffset())
	var log syncBuffer
	cert := demoCertificate(t)
	l, err := Start(t.Context(), "127.0.0.1:0", c, func() *x509.Certificate { return cert }, slog.New(slog.NewJSONHandler(&log, nil)))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(log.String(), `"level":"WARN"`) || !strings.Contains(log.String(), "TEST CLOCK CONTROL IS ON") ||
		!strings.Contains(log.String(), l.Addr().String()) {
		t.Fatalf("no startup WARN naming the address:\n%s", log.String())
	}
	client := &http.Client{Timeout: 5 * time.Second}
	req, err := http.NewRequestWithContext(t.Context(), http.MethodPut, "http://"+l.Addr().String()+Path,
		strings.NewReader(`{"offsetMillis": 60000}`))
	if err != nil {
		t.Fatal(err)
	}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK || c.OffsetMillis() != 60_000 {
		t.Fatalf("PUT over the listener: %d, offset %d", resp.StatusCode, c.OffsetMillis())
	}
	client.CloseIdleConnections()
	if err := l.Shutdown(context.WithoutCancel(t.Context())); err != nil {
		t.Fatal(err)
	}
	if c.OffsetMillis() != 0 {
		t.Fatalf("shutdown left the clock at %d", c.OffsetMillis())
	}
}

// S8.6-AC2, S8.6-AC3: the listener refuses to start with a certificate that is not bundled, or on a
// clock that cannot be moved; nothing is bound.
func TestStartRefuses(t *testing.T) {
	logger := slog.New(slog.DiscardHandler)
	fresh := freshCertificate(t)
	if _, err := Start(t.Context(), "127.0.0.1:0", clock.New(clock.AllowOffset()),
		func() *x509.Certificate { return fresh }, logger); err == nil || !strings.Contains(err.Error(), "not a bundled certificate") {
		t.Fatalf("a real certificate: err = %v", err)
	}
	demo := demoCertificate(t)
	if _, err := Start(t.Context(), "127.0.0.1:0", clock.New(),
		func() *x509.Certificate { return demo }, logger); err == nil || !strings.Contains(err.Error(), "AllowOffset") {
		t.Fatalf("a production clock: err = %v", err)
	}
}

// FuzzPut: whatever the body, a PUT is answered 200 or 400 with JSON, and a 400 leaves the clock alone.
func FuzzPut(f *testing.F) {
	for _, s := range []string{`{"at": "2031-12-31T23:59:59.999Z"}`, `{"offsetMillis": -1}`, `{"offsetSeconds": 60}`,
		`{}`, `not json`, `{"at": null, "offsetMillis": 1e3}`, `[1]`} {
		f.Add(s)
	}
	cert, err := loadCert(filepath.Join(repoRoot, "internal/keys/bundled/x9-demo.pem"))
	if err != nil {
		f.Fatal(err)
	}
	f.Fuzz(func(t *testing.T, body string) {
		c := clock.New(clock.AllowOffset())
		h := Handler(c, func() *x509.Certificate { return cert }, slog.New(slog.DiscardHandler))
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequestWithContext(context.Background(), http.MethodPut, Path, strings.NewReader(body)))
		var out map[string]any
		if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
			t.Fatalf("not JSON: %q", rec.Body.String())
		}
		switch rec.Code {
		case http.StatusOK:
		case http.StatusBadRequest:
			if c.OffsetMillis() != 0 {
				t.Fatalf("a 400 moved the clock to %d", c.OffsetMillis())
			}
		default:
			t.Fatalf("status %d for %q", rec.Code, body)
		}
	})
}
