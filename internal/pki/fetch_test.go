// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package pki

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// fetchFixture serves certificate documents over HTTPS at payer.example and over plain HTTP at
// x9-150.example.com (the deployment's own host) and at payer.example:80, all in process.
type fetchFixture struct {
	clock   *testClock
	net     *testNetwork
	tlsConf *tls.Config

	mu      sync.Mutex
	docs    map[string][]byte
	headers []http.Header
	hits    atomic.Int64
	plain   atomic.Int64
}

func newFetchFixture(t *testing.T) *fetchFixture {
	t.Helper()
	f := &fetchFixture{clock: newTestClock(fixedNow), net: newTestNetwork(), docs: map[string][]byte{}}
	serve := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.headers = append(f.headers, r.Header.Clone())
		body, ok := f.docs[r.URL.Path]
		f.mu.Unlock()
		switch {
		case r.URL.Path == "/redirect":
			http.Redirect(w, r, "/chain.pem", http.StatusFound)
		case r.URL.Path == "/slow":
			<-r.Context().Done()
		case r.URL.Path == "/cookie":
			http.SetCookie(w, &http.Cookie{Name: "session", Value: "s", Secure: true, HttpOnly: true, SameSite: http.SameSiteStrictMode})
			_, _ = w.Write(body)
		case ok:
			_, _ = w.Write(body)
		default:
			http.NotFound(w, r)
		}
	})
	secure := httptest.NewTLSServer(queried(serve, &f.hits))
	t.Cleanup(secure.Close)
	plain := httptest.NewServer(queried(serve, &f.plain))
	t.Cleanup(plain.Close)

	pool := x509.NewCertPool()
	pool.AddCert(secure.Certificate())
	f.tlsConf = &tls.Config{RootCAs: pool, ServerName: "example.com", MinVersion: tls.VersionTLS12}
	f.net.route("payer.example:443", secure)
	f.net.route("payer.example:80", plain)
	f.net.route("x9-150.example.com:80", plain)
	f.net.route("127.0.0.1:8080", plain)
	return f
}

func (f *fetchFixture) serve(path string, body []byte) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.docs[path] = body
}

func (f *fetchFixture) fetcher(t *testing.T, mod func(*FetcherConfig)) *Fetcher {
	t.Helper()
	cfg := FetcherConfig{
		Clock:          f.clock,
		OwnHost:        "x9-150.example.com",
		ConnectTimeout: 200 * time.Millisecond,
		ReadTimeout:    300 * time.Millisecond,
		MaxBytes:       16 << 10,
		DialContext:    f.net.dial,
		TLSConfig:      f.tlsConf,
	}
	if mod != nil {
		mod(&cfg)
	}
	fetcher, err := NewFetcher(cfg)
	if err != nil {
		t.Fatal(err)
	}
	return fetcher
}

func goldenRSA(t *testing.T) []*x509.Certificate {
	t.Helper()
	return readChain(t, goldenDir+"/pki/signer-rsa.pem")
}

// S8.3-AC3: x5u is fetched over HTTPS and read as Java's CertificateFactory reads it; a plain http
// URL to another host is fetched over HTTPS, never in the clear (the Java build rewrites it too).
func TestFetchX5UOverHTTPS(t *testing.T) {
	f := newFetchFixture(t)
	chain := goldenRSA(t)
	f.serve("/chain.pem", pemOf(chain...))
	fetcher := f.fetcher(t, nil)

	for _, u := range []string{"https://payer.example/chain.pem", "http://payer.example/chain.pem", "payer.example/chain.pem"} {
		doc, err := fetcher.Fetch(t.Context(), X5U, u, thumb(chain[0]))
		if err != nil {
			t.Fatalf("%s: %v", u, err)
		}
		certs, err := doc.Certificates()
		if err != nil || len(certs) != 2 || !certs[0].Equal(chain[0]) {
			t.Fatalf("%s: got %d certificates, %v", u, len(certs), err)
		}
	}
	if f.plain.Load() != 0 || f.net.dialed("payer.example:80") != 0 {
		t.Fatal("a certificate was fetched in the clear from another host")
	}
}

// S8.3-AC3: the deployment's own host may be read over plain HTTP, matched exactly. A URL that
// merely mentions the own host elsewhere is another host (the Java build matches the string
// anywhere in the URL, and then fetches that other host in the clear).
func TestFetchOwnHost(t *testing.T) {
	f := newFetchFixture(t)
	chain := goldenRSA(t)
	f.serve("/x9/.well-known/certificate/x9.pem", pemOf(chain...))
	f.serve("/chain.pem", pemOf(chain...))

	if _, err := f.fetcher(t, nil).Fetch(t.Context(), X5U, "http://x9-150.example.com/x9/.well-known/certificate/x9.pem", nil); err != nil {
		t.Fatal(err)
	}
	if f.plain.Load() != 1 {
		t.Fatal("the own host was not read over plain HTTP")
	}

	own := f.fetcher(t, func(c *FetcherConfig) { c.OwnBaseURL = "http://127.0.0.1:8080" })
	if _, err := own.Fetch(t.Context(), X5U, "https://X9-150.example.com/x9/.well-known/certificate/x9.pem", nil); err != nil {
		t.Fatal(err)
	}
	if f.net.dialed("127.0.0.1:8080") == 0 {
		t.Fatal("the own host was not read from the configured base")
	}

	plainBefore := f.plain.Load()
	if _, err := f.fetcher(t, nil).Fetch(t.Context(), X5U, "http://payer.example/chain.pem?h=x9-150.example.com", nil); err != nil {
		t.Fatal(err)
	}
	if f.plain.Load() != plainBefore {
		t.Fatal("a URL mentioning the own host in its query was fetched in the clear")
	}
}

// S8.3-AC3: everything that is refused is CERTIFICATE_UNAVAILABLE: a redirect (never followed),
// an HTTP error, a scheme other than http(s), a document over the size cap, an answer slower than
// the read timeout, a host that cannot be reached, something that is not a JWK set or a
// certificate chain.
func TestFetchRefusals(t *testing.T) {
	f := newFetchFixture(t)
	f.serve("/chain.pem", pemOf(goldenRSA(t)...))
	f.serve("/big.pem", []byte(strings.Repeat("A", 32<<10)))
	f.serve("/not-a-set.json", []byte(`[1,2,3]`))
	f.serve("/empty-set.json", []byte(`{}`))
	f.serve("/not-pem.pem", []byte("-----BEGIN CERTIFICATE-----\nnope\n-----END CERTIFICATE-----\n"))
	f.serve("/empty.pem", []byte("\n"))
	fetcher := f.fetcher(t, nil)

	for _, tc := range []struct {
		name string
		kind Kind
		url  string
	}{
		{"redirect", X5U, "https://payer.example/redirect"},
		{"not found", X5U, "https://payer.example/missing.pem"},
		{"ftp", X5U, "ftp://payer.example/chain.pem"},
		{"file", X5U, "file:///etc/passwd"},
		{"too large", X5U, "https://payer.example/big.pem"},
		{"read timeout", X5U, "https://payer.example/slow"},
		{"refused", X5U, "https://unrouted.example/chain.pem"},
		{"not a JWK set", JKU, "https://payer.example/not-a-set.json"},
		{"an empty JWK set", JKU, "https://payer.example/empty-set.json"},
		{"empty document", X5U, "https://payer.example/empty.pem"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			before := f.hits.Load()
			_, err := fetcher.Fetch(t.Context(), tc.kind, tc.url, nil)
			wantProblem(t, err, CertificateUnavailable)
			if tc.name == "redirect" && f.hits.Load()-before != 1 {
				t.Fatal("the redirect was followed")
			}
		})
	}

	doc, err := fetcher.Fetch(t.Context(), X5U, "https://payer.example/not-pem.pem", nil)
	if err != nil {
		t.Fatal(err)
	}
	_, err = doc.Certificates()
	wantProblem(t, err, CertificateUnavailable)
}

// S8.3-AC3: a connection that does not open within the connect timeout is given up, however long
// the read timeout.
func TestFetchConnectTimeout(t *testing.T) {
	f := newFetchFixture(t)
	fetcher := f.fetcher(t, func(c *FetcherConfig) {
		c.ReadTimeout = time.Minute
		c.DialContext = func(ctx context.Context, _, _ string) (net.Conn, error) {
			<-ctx.Done()
			return nil, ctx.Err()
		}
	})
	done := make(chan error, 1)
	go func() {
		_, err := fetcher.Fetch(context.Background(), X5U, "https://stalled.example/chain.pem", nil)
		done <- err
	}()
	select {
	case err := <-done:
		wantProblem(t, err, CertificateUnavailable)
	case <-time.After(10 * time.Second): //nolint:forbidigo // a real deadline for a test that would otherwise hang
		t.Fatal("the connect timeout did not apply")
	}
}

// S8.3-AC3: no credential is sent: a user name and password in the URL are dropped, and a cookie
// the server set is never sent back.
func TestFetchSendsNoCredentials(t *testing.T) {
	f := newFetchFixture(t)
	f.serve("/cookie", pemOf(goldenRSA(t)...))
	fetcher := f.fetcher(t, nil)
	for range 2 {
		if _, err := fetcher.Fetch(t.Context(), X5U, "https://user:secret@payer.example/cookie", nil); err != nil {
			t.Fatal(err)
		}
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.headers) != 2 {
		t.Fatalf("%d requests; want 2", len(f.headers))
	}
	for _, h := range f.headers {
		if h.Get("Authorization") != "" || h.Get("Cookie") != "" {
			t.Fatalf("a credential was sent: %v", h)
		}
	}
}

// S8.3-AC3: a document is remembered by the header's x5t#S256 once its token was verified; the
// next token with that thumbprint skips the fetch, from any URL, but never the checks. A document
// that does not hold that certificate is not remembered, and a remembered one expires.
func TestFetchCacheByThumbprint(t *testing.T) {
	f := newFetchFixture(t)
	chain := goldenRSA(t)
	f.serve("/chain.pem", pemOf(chain...))
	fetcher := f.fetcher(t, func(c *FetcherConfig) { c.CacheTTL = time.Hour })
	x5t := thumb(chain[0])

	doc, err := fetcher.Fetch(t.Context(), X5U, "https://payer.example/chain.pem", x5t)
	if err != nil {
		t.Fatal(err)
	}
	fetcher.Remember(doc, "not-this-document") // holds no such certificate: not kept
	fetcher.Remember(doc, *x5t)
	hits := f.hits.Load()

	again, err := fetcher.Fetch(t.Context(), X5U, "https://elsewhere.example/other.pem", x5t)
	if err != nil || !again.Cached || f.hits.Load() != hits {
		t.Fatalf("the remembered document was fetched again (cached=%v, err=%v)", again != nil && again.Cached, err)
	}
	other := "not-this-document"
	if _, err := fetcher.Fetch(t.Context(), X5U, "https://payer.example/missing.pem", &other); err == nil {
		t.Fatal("a document was remembered under a thumbprint it does not hold")
	}

	// The cache skips the fetch, never the checks: the remembered chain is still verified, and a
	// verifier that does not trust it refuses it.
	certs, err := again.Certificates()
	if err != nil {
		t.Fatal(err)
	}
	untrusting, err := NewVerifier(Config{Truststore: NewStaticTruststore(), Clock: f.clock})
	if err != nil {
		t.Fatal(err)
	}
	_, err = untrusting.Verify(t.Context(), certs, x5t)
	wantProblem(t, err, UntrustedChain)

	f.clock.Advance(2 * time.Hour)
	expired, err := fetcher.Fetch(t.Context(), X5U, "https://payer.example/chain.pem", x5t)
	if err != nil || expired.Cached {
		t.Fatalf("the remembered document did not expire (err=%v)", err)
	}
}

// S8.3-AC3: a JWK set is remembered when one of its keys carries the thumbprint's certificate.
func TestFetchCacheJWKSet(t *testing.T) {
	f := newFetchFixture(t)
	ec := readChain(t, goldenDir+"/pki/signer-ec.pem")
	x5c := make([]string, 0, len(ec))
	for _, c := range ec {
		x5c = append(x5c, base64.StdEncoding.EncodeToString(c.Raw))
	}
	set, err := json.Marshal(map[string]any{"keys": []any{map[string]any{"kty": "EC", "kid": "x9", "x5c": x5c}}})
	if err != nil {
		t.Fatal(err)
	}
	f.serve("/jwks.json", set)
	fetcher := f.fetcher(t, nil)
	x5t := thumb(ec[0])

	doc, err := fetcher.Fetch(t.Context(), JKU, "https://payer.example/jwks.json", x5t)
	if err != nil {
		t.Fatal(err)
	}
	fetcher.Remember(doc, *x5t)
	hits := f.hits.Load()
	again, err := fetcher.Fetch(t.Context(), JKU, "https://payer.example/jwks.json", x5t)
	if err != nil || !again.Cached || f.hits.Load() != hits {
		t.Fatal("the JWK set was fetched again")
	}
	if cached, err := fetcher.Fetch(t.Context(), X5U, "https://payer.example/jwks.json", x5t); err == nil && cached.Cached {
		t.Fatal("a JWK set answered for an x5u")
	}
}
