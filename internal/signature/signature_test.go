// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package signature

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"io"
	"log/slog"
	"math/big"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"

	"github.com/matera-inc/x9.150-qrcode/internal/clock"
	"github.com/matera-inc/x9.150-qrcode/internal/config"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/jws"
	"github.com/matera-inc/x9.150-qrcode/internal/keys"
	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
	"github.com/matera-inc/x9.150-qrcode/internal/pki"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

const (
	host = "x9-150.example.com"
	cid  = "123e4567-e89b-12d3-a456-426614174000"
)

// The bundled demo certificate as the Java build publishes it (taken from a running Java build).
const (
	demoX5TS256 = "Z-nq5ZWB6ZyB03RP4IYEL3Nj4TwtFeBREwEdLXbl7Rs"
	demoX5T     = "RbhYv60txyKnHg2UTFTYW8loq9M"
	demoNBF     = 1784415914
	demoExp     = 2099775914
	// demoPEMSHA256 is the SHA-256 of the Java build's GET /pub/.well-known/certificate/x9-key.pem.
	demoPEMSHA256 = "aefae1078ed6aaf253bbb53639fefcce90dc414eaffe7874fd2c87d22ab90b16"
)

type businessClock struct {
	mu sync.Mutex
	at time.Time
}

func (c *businessClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.at
}

func (c *businessClock) add(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.at = c.at.Add(d)
}

type fixture struct {
	url   string
	clock *businessClock
	store *keys.Store
}

// serve runs the signature routes over HTTP, signing with store's key. With publication JWK_SET the
// verifier fetches this server's own JWK set, as a deployment reads its own host (S10.2-AC1).
func serve(t *testing.T, store *keys.Store, alg string, publication jws.Publication) *fixture {
	t.Helper()
	clk := &businessClock{at: time.Date(2026, 11, 15, 12, 0, 0, 0, time.UTC)}
	logger := slog.New(slog.DiscardHandler)
	api := httpapi.New(logger, clk.Now)
	srv := httptest.NewServer(api)
	t.Cleanup(srv.Close)
	signer, err := jws.New(jws.Config{Algorithm: alg, Publication: publication, Host: host}, clk)
	if err != nil {
		t.Fatal(err)
	}
	fetcher, err := pki.NewFetcher(pki.FetcherConfig{Clock: clock.New(), OwnHost: host, OwnBaseURL: srv.URL})
	if err != nil {
		t.Fatal(err)
	}
	verifier, err := jws.NewVerifier(jws.VerifierConfig{Truststore: store, IssuerName: "X9", Fetcher: fetcher}, clk)
	if err != nil {
		t.Fatal(err)
	}
	New(store, signer, verifier, logger).Register(api)
	return &fixture{url: srv.URL, clock: clk, store: store}
}

func demo(t *testing.T) *keys.Store {
	t.Helper()
	store, err := keys.Load(config.Certificate{}, slog.New(slog.DiscardHandler))
	if err != nil {
		t.Fatal(err)
	}
	return store
}

type answer struct {
	status int
	header http.Header
	body   string
}

func (f *fixture) do(t *testing.T, method, path string, header map[string]string, body string) answer {
	t.Helper()
	req, err := http.NewRequestWithContext(t.Context(), method, f.url+path, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	for k, v := range header {
		req.Header.Set(k, v)
	}
	resp, err := (&http.Client{Timeout: 10 * time.Second}).Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	return answer{status: resp.StatusCode, header: resp.Header, body: string(b)}
}

func (f *fixture) generate(t *testing.T, correlationID, ttl, body string) answer {
	t.Helper()
	h := map[string]string{"Content-Type": "application/json"}
	if correlationID != "-" {
		h[HeaderCorrelationID] = correlationID
	}
	if ttl != "-" {
		h[HeaderTTLSeconds] = ttl
	}
	return f.do(t, http.MethodPost, GeneratePath, h, body)
}

func (f *fixture) validate(t *testing.T, body string) answer {
	t.Helper()
	return f.do(t, http.MethodPost, ValidatePath, map[string]string{"Content-Type": "application/json"}, body)
}

func validateBody(token string) string {
	b, _ := json.Marshal(map[string]string{"content": token})
	return string(b)
}

// problem is a refusal as the Java build writes it.
func problem(typ, title, detail, path string, violations ...string) map[string]any {
	p := map[string]any{
		"type": "https://x9.matera.com/api/" + typ, "title": title, "status": float64(400), "detail": detail,
		"instance": path,
	}
	if violations != nil {
		v := make([]any, len(violations))
		for i, s := range violations {
			v[i] = s
		}
		p["violations"] = v
	}
	return p
}

func decode(t *testing.T, s string) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal([]byte(s), &m); err != nil {
		t.Fatalf("not JSON: %v: %s", err, s)
	}
	return m
}

func segment(t *testing.T, token string, i int) []byte {
	t.Helper()
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		t.Fatalf("not a compact JWS: %q", token)
	}
	b, err := base64.RawURLEncoding.DecodeString(parts[i])
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// S10.2-AC2: the JWK set is the signing key with its x5c chain, in Java's shape, member for member.
func TestJWKSetIsJavas(t *testing.T) {
	f := serve(t, demo(t), "PS512", jws.PublishJWKSet)
	a := f.do(t, http.MethodGet, jws.JWKSetPath, nil, "")
	if a.status != http.StatusOK || a.header.Get("Content-Type") != "application/json" {
		t.Fatalf("status %d, Content-Type %q: %s", a.status, a.header.Get("Content-Type"), a.body)
	}
	var set struct {
		Keys []map[string]any `json:"keys"`
	}
	if err := json.Unmarshal([]byte(a.body), &set); err != nil || len(set.Keys) != 1 {
		t.Fatalf("not a one-key JWK set: %v: %s", err, a.body)
	}
	key := set.Keys[0]
	names := make([]string, 0, len(key))
	for k := range key {
		names = append(names, k)
	}
	slices.Sort(names)
	want := []string{"alg", "e", "exp", "key_ops", "kid", "kty", "n", "nbf", "use", "x5c", "x5t", "x5t#S256"}
	if diff := cmp.Diff(want, names); diff != "" {
		t.Errorf("members (-Java +Go):\n%s", diff)
	}
	for k, v := range map[string]any{
		"kty": "RSA", "kid": "x9-key", "alg": "PS512", "use": "sig", "e": "AQAB", "x5t": demoX5T,
		"x5t#S256": demoX5TS256, "nbf": float64(demoNBF), "exp": float64(demoExp), "key_ops": []any{"verify"},
	} {
		if diff := cmp.Diff(v, key[k]); diff != "" {
			t.Errorf("%s (-Java +Go):\n%s", k, diff)
		}
	}
	chain := key["x5c"].([]any)
	der, err := base64.StdEncoding.DecodeString(chain[0].(string))
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil || !cert.Equal(f.store.Current().Identity.Certificate()) {
		t.Fatalf("x5c[0] is not the signing certificate: %v", err)
	}
}

// S10.2-AC2: an EC key is published with crv, x and y, each coordinate padded to its curve's size.
func TestJWKSetOfAnECKey(t *testing.T) {
	for _, curve := range []elliptic.Curve{elliptic.P256(), elliptic.P384(), elliptic.P521()} {
		id := newECIdentity(t, curve, "ec.example.com")
		set, err := JWKSet(id, "ES256")
		if err != nil {
			t.Fatal(err)
		}
		k := set.Keys[0]
		size := (curve.Params().BitSize + 7) / 8
		x, _ := base64.RawURLEncoding.DecodeString(k.X)
		y, _ := base64.RawURLEncoding.DecodeString(k.Y)
		if k.KTY != "EC" || k.Crv != curve.Params().Name || len(x) != size || len(y) != size || k.N != "" || k.E != "" {
			t.Fatalf("%s: %+v", curve.Params().Name, k)
		}
		pub, err := ecdsa.ParseUncompressedPublicKey(curve, append(append([]byte{4}, x...), y...))
		if err != nil || !pub.Equal(id.key.Public()) {
			t.Fatalf("%s: x and y are not the key: %v", curve.Params().Name, err)
		}
	}
}

// S10.2-AC3: the certificate is served in PEM under the name x5u gives it, byte for byte as the
// Java build serves it; any other name is a 404.
func TestCertificateIsServedUnderItsName(t *testing.T) {
	f := serve(t, demo(t), "PS512", jws.PublishPEM)
	a := f.do(t, http.MethodGet, jws.CertificatePath, nil, "")
	if a.status != http.StatusOK || a.header.Get("Content-Type") != ContentTypePEM ||
		a.header.Get("Content-Disposition") != `attachment; filename="x9-key.pem"` {
		t.Fatalf("status %d, headers %v", a.status, a.header)
	}
	if sum := sha256.Sum256([]byte(a.body)); hex.EncodeToString(sum[:]) != demoPEMSHA256 {
		t.Errorf("the PEM is not the Java build's:\n%s", a.body)
	}
	if !strings.HasSuffix(jws.CertificatePath, "/"+CertificateFileName) {
		t.Fatalf("x5u names %s, the certificate is served as %s", jws.CertificatePath, CertificateFileName)
	}
	for _, name := range []string{"x9-key.PEM", "x9.pem", "anything.txt", "x9-key.pem.bak"} {
		a := f.do(t, http.MethodGet, CertificatePrefix+name, nil, "")
		if a.status != http.StatusNotFound || decode(t, a.body)["error"] != "Not Found" {
			t.Errorf("%s: %d %s, want Spring's 404", name, a.status, a.body)
		}
	}
}

// S10.2-AC2, S10.2-AC3: a reloaded keystore is published, and signs, without a restart.
func TestPublishedKeyFollowsAReload(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "signer.pem")
	first, second := newECIdentity(t, elliptic.P256(), "first.example.com"), newECIdentity(t, elliptic.P256(), "second.example.com")
	writePEMKeystore(t, path, first)
	store, err := keys.Load(config.Certificate{PrivateKeystore: config.PrivateKeystore{Location: path}},
		slog.New(slog.DiscardHandler))
	if err != nil {
		t.Fatal(err)
	}
	f := serve(t, store, "ES256", jws.PublishChain)
	thumbprint := func() (jwk, pemCert, signed string) {
		var set JWKSetDocument
		if err := json.Unmarshal([]byte(f.do(t, http.MethodGet, jws.JWKSetPath, nil, "").body), &set); err != nil {
			t.Fatal(err)
		}
		block, _ := pem.Decode([]byte(f.do(t, http.MethodGet, jws.CertificatePath, nil, "").body))
		cert, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			t.Fatal(err)
		}
		var header map[string]any
		if err := json.Unmarshal(segment(t, f.generate(t, cid, "60", `{"a":1}`).body, 0), &header); err != nil {
			t.Fatal(err)
		}
		return set.Keys[0].X5TS256, jws.Thumbprint(cert), header["x5t#S256"].(string)
	}
	want := jws.Thumbprint(first.cert)
	if a, b, c := thumbprint(); a != want || b != want || c != want {
		t.Fatalf("before the reload: %s %s %s, want %s", a, b, c, want)
	}
	writePEMKeystore(t, path, second)
	if changed, err := store.Reload(); !changed || err != nil {
		t.Fatalf("changed=%v err=%v", changed, err)
	}
	want = jws.Thumbprint(second.cert)
	if a, b, c := thumbprint(); a != want || b != want || c != want {
		t.Fatalf("after the reload: %s %s %s, want %s", a, b, c, want)
	}
}

// S10.2-AC4, S10.2-AC5: a generated JWS carries the requested correlationId and ttl, is signed as
// Story 8.4 signs, and verifies, here through this deployment's own JWK set (jku on the public host,
// read from the deployment itself).
func TestGenerateThenValidate(t *testing.T) {
	for _, publication := range []jws.Publication{jws.PublishJWKSet, jws.PublishPEM, jws.PublishChain} {
		t.Run(string(publication), func(t *testing.T) {
			f := serve(t, demo(t), "PS512", publication)
			a := f.generate(t, cid, "120", ` {"b":[1,null,{"z":null}],"a":{"x":null,"y":1,"y":2},"c":"é","d":1.0}  ignored`)
			if a.status != http.StatusOK || a.header.Get("Content-Type") != ContentTypeJOSE {
				t.Fatalf("status %d, Content-Type %q: %s", a.status, a.header.Get("Content-Type"), a.body)
			}
			var header map[string]any
			if err := json.Unmarshal(segment(t, a.body, 0), &header); err != nil {
				t.Fatal(err)
			}
			for k, v := range map[string]any{
				"alg": "PS512", "typ": "JOSE", "kid": "x9-key", "correlationId": cid, "ttl": float64(120000),
				"iat": float64(f.clock.Now().UnixMilli()), "crit": []any{"correlationId", "iat", "ttl"},
			} {
				if diff := cmp.Diff(v, header[k]); diff != "" {
					t.Errorf("header %s (-want +got):\n%s", k, diff)
				}
			}
			if got, want := string(segment(t, a.body, 1)), `{"b":[1,null,{}],"a":{"y":2},"c":"é","d":1.0}`; got != want {
				t.Errorf("payload = %s, want %s", got, want)
			}

			v := f.validate(t, validateBody(" "+a.body+"\n"))
			if v.status != http.StatusOK || v.header.Get(HeaderCorrelationID) != cid || v.body != "" {
				t.Fatalf("validate: %d %v %s", v.status, v.header, v.body)
			}
		})
	}
}

// X9-SIG-534 (S11.4-AC1; ADR-0028, known difference 13): a number in the body is signed as it was
// sent, exponent and trailing zeros included, where the Java build signs Jackson's spelling (1e2
// becomes 100.0). Both are the same JSON number, so a token from either build verifies on the
// other; what is pinned here is that Go does not rewrite it.
func TestGenerateSignsANumberAsSent(t *testing.T) {
	const payload = `{"a":1e2,"b":-2.50E+3,"c":1.0,"d":100,"e":0.1e-2}`
	f := serve(t, demo(t), "PS512", jws.PublishChain)

	a := f.generate(t, cid, "120", payload)

	if a.status != http.StatusOK {
		t.Fatalf("status %d: %s", a.status, a.body)
	}
	if got := string(segment(t, a.body, 1)); got != payload {
		t.Errorf("payload = %s, want it as sent: %s", got, payload)
	}
	if v := f.validate(t, validateBody(a.body)); v.status != http.StatusOK {
		t.Fatalf("validate: %d %s", v.status, v.body)
	}
}

// S10.2-AC4: the headers are read, and refused, as Spring reads them; the checks run in Spring's
// order (media type, Correlation-Id, TTL-Seconds, body, then the ttl's value).
func TestGenerateRefusalsAreJavas(t *testing.T) {
	f := serve(t, demo(t), "PS512", jws.PublishChain)
	const path = GeneratePath
	headers := func(v string) map[string]any {
		return problem("validation-error", "Validation Failed", "One or more headers failed validation.", path, v)
	}
	fields := func(v string) map[string]any {
		return problem("validation-error", "Validation Failed", "One or more fields failed validation.", path, v)
	}
	malformed := problem("malformed-json", "Malformed JSON", "The request body is not readable or is incorrectly formatted.", path)
	badTTL := problem("business-rule-violation", "Business Rule Violation", "A business rule was violated.", path,
		"ttlTimeSeconds: Should inform a valid TTL time in seconds for generation !!")
	for _, tc := range []struct {
		name, cid, ttl, body string
		want                 map[string]any
	}{
		{"no Correlation-Id", "-", "5", `{"a":1}`,
			headers("Required request header 'Correlation-Id' for method parameter type UUID is not present")},
		{"empty Correlation-Id", "", "5", `{"a":1}`,
			headers("Required request header 'Correlation-Id' for method parameter type UUID is present but converted to null")},
		{"Correlation-Id not a UUID", "abc", "5", `{"a":1}`, fields("Correlation-Id has invalid value.")},
		{"Correlation-Id in braces", "{" + cid + "}", "5", `{"a":1}`, fields("Correlation-Id has invalid value.")},
		{"Correlation-Id without dashes", strings.ReplaceAll(cid, "-", ""), "5", `{"a":1}`, fields("Correlation-Id has invalid value.")},
		{"no TTL-Seconds", cid, "-", `{"a":1}`,
			headers("Required request header 'TTL-Seconds' for method parameter type Integer is not present")},
		{"empty TTL-Seconds", cid, "", `{"a":1}`,
			headers("Required request header 'TTL-Seconds' for method parameter type Integer is present but converted to null")},
		{"TTL-Seconds past an int", cid, "2147483648", `{"a":1}`, fields("TTL-Seconds has invalid value.")},
		{"TTL-Seconds a fraction", cid, "1.5", `{"a":1}`, fields("TTL-Seconds has invalid value.")},
		{"bad Correlation-Id before a bad ttl", "abc", "x", `x`, fields("Correlation-Id has invalid value.")},
		{"no body", cid, "5", ``, malformed},
		{"an array", cid, "5", `[1]`, malformed},
		{"a string", cid, "5", `"x"`, malformed},
		{"null", cid, "5", `null`, malformed},
		{"a malformed body before a zero ttl", cid, "0", `x`, malformed},
		{"ttl 0", cid, "0", `{"a":1}`, badTTL},
		{"ttl -1", cid, "-1", `{"a":1}`, badTTL},
		{"ttl -0x1", cid, "-0x1", `{"a":1}`, badTTL},
		// The Java build signs the nil UUID; this one refuses it as Java refuses a missing correlationId.
		{"the nil UUID", "00000000-0000-0000-0000-000000000000", "5", `{"a":1}`,
			problem("business-rule-violation", "Business Rule Violation", "A business rule was violated.", path,
				"correlationId: Should inform the JWS Token for generation !!")},
	} {
		t.Run(tc.name, func(t *testing.T) {
			a := f.generate(t, tc.cid, tc.ttl, tc.body)
			if a.status != http.StatusBadRequest || a.header.Get("Content-Type") != httpapi.ContentTypeProblem {
				t.Fatalf("status %d, Content-Type %q: %s", a.status, a.header.Get("Content-Type"), a.body)
			}
			if diff := cmp.Diff(tc.want, decode(t, a.body)); diff != "" {
				t.Errorf("(-Java +Go):\n%s", diff)
			}
		})
	}
	a := f.do(t, http.MethodPost, path, map[string]string{"Content-Type": "text/plain"}, `{"a":1}`)
	if a.status != http.StatusUnsupportedMediaType {
		t.Errorf("text/plain without headers: %d, want Spring's 415 first", a.status)
	}
}

// S10.2-AC4: Correlation-Id and TTL-Seconds take what Spring converts: UUID.fromString's short
// fields, either case, and Integer.decode's hexadecimal.
func TestGenerateHeadersAsSpringReadsThem(t *testing.T) {
	f := serve(t, demo(t), "PS512", jws.PublishChain)
	for _, tc := range []struct {
		cid, ttl, wantCID string
		wantTTL           float64
	}{
		{"1-2-3-4-5", "7", "00000001-0002-0003-0004-000000000005", 7000},
		{strings.ToUpper(cid), "0x10", cid, 16000},
		{cid, "#10", cid, 16000},
		{cid, "+5", cid, 5000},
		{cid, "010", cid, 10000},
		{cid, "2147483647", cid, 2147483647000},
	} {
		a := f.generate(t, tc.cid, tc.ttl, `{}`)
		if a.status != http.StatusOK {
			t.Fatalf("%s / %s: %d %s", tc.cid, tc.ttl, a.status, a.body)
		}
		var header map[string]any
		if err := json.Unmarshal(segment(t, a.body, 0), &header); err != nil {
			t.Fatal(err)
		}
		if header["correlationId"] != tc.wantCID || header["ttl"] != tc.wantTTL {
			t.Errorf("%s / %s: correlationId %v, ttl %v; want %s, %v", tc.cid, tc.ttl, header["correlationId"],
				header["ttl"], tc.wantCID, tc.wantTTL)
		}
		if got := string(segment(t, a.body, 1)); got != `{}` {
			t.Errorf("payload %s, want {}", got)
		}
	}
}

// S10.2-AC5: a JWS that does not verify, and a body without one, are refused with the Java build's
// body: a 400 business-rule-violation (the Java build never answers this endpoint 422).
func TestValidateRefusalsAreJavas(t *testing.T) {
	f := serve(t, demo(t), "PS512", jws.PublishChain)
	const path = ValidatePath
	signature := problem("business-rule-violation", "Business Rule Violation", "A business rule was violated.", path,
		"Signature validation failed for content !!!")
	noToken := problem("business-rule-violation", "Business Rule Violation", "A business rule was violated.", path,
		"jwsToken: Should inform the JWS Token for validation !!")
	notNull := problem("validation-error", "Validation Failed", "One or more fields failed validation.", path,
		"content: must not be null")
	malformed := problem("malformed-json", "Malformed JSON", "The request body is not readable or is incorrectly formatted.", path)

	good := f.generate(t, cid, "60", `{"a":1}`).body
	parts := strings.Split(good, ".")
	tampered := parts[0] + "." + base64.RawURLEncoding.EncodeToString([]byte(`{"a":2}`)) + "." + parts[2]
	short := f.generate(t, cid, "1", `{"a":1}`).body

	for _, tc := range []struct {
		name, body string
		want       map[string]any
	}{
		{"not a JWS", `{"content":"a.b.c"}`, signature},
		{"tampered", validateBody(tampered), signature},
		{"a number", `{"content":5}`, signature},
		{"empty", `{"content":""}`, noToken},
		{"blank", `{"content":"  "}`, noToken},
		{"absent", `{}`, notNull},
		{"null", `{"content":null}`, notNull},
		{"an object", `{"content":{}}`, malformed},
		{"no body", ``, malformed},
	} {
		t.Run(tc.name, func(t *testing.T) {
			a := f.validate(t, tc.body)
			if a.status != http.StatusBadRequest || a.header.Get(HeaderCorrelationID) != "" {
				t.Fatalf("status %d, headers %v: %s", a.status, a.header, a.body)
			}
			if diff := cmp.Diff(tc.want, decode(t, a.body)); diff != "" {
				t.Errorf("(-Java +Go):\n%s", diff)
			}
		})
	}

	f.clock.add(2 * time.Second) // the business clock decides the ttl (ADR-0033)
	if a := f.validate(t, validateBody(short)); a.status != http.StatusBadRequest {
		t.Errorf("an expired JWS: %d, want 400", a.status)
	}
	if a := f.do(t, http.MethodPost, path, map[string]string{"Content-Type": "text/plain"}, validateBody(good)); a.status != http.StatusUnsupportedMediaType {
		t.Errorf("text/plain: %d, want Spring's 415", a.status)
	}
}

func TestJavaUUID(t *testing.T) {
	for in, want := range map[string]string{
		cid:                                     cid,
		"123E4567-E89B-12D3-A456-426614174000":  cid,
		"1-2-3-4-5":                             "00000001-0002-0003-0004-000000000005",
		"+1-2-3-4-5":                            "00000001-0002-0003-0004-000000000005",
		"00000000-0000-0000-0000-000000000000":  "00000000-0000-0000-0000-000000000000",
		"123456789-1-1-1-1":                     "23456789-0001-0001-0001-000000000001",
		"":                                      "",
		"1-2-3-4":                               "",
		"1-2-3-4-5-6":                           "",
		"-1-2-3-4-5":                            "",
		"1--3-4-5":                              "",
		"g-2-3-4-5":                             "",
		"{" + cid + "}":                         "",
		"urn:uuid:" + cid:                       "",
		strings.ReplaceAll(cid, "-", ""):        "",
		"0000000000000001-2-3-4-5":              "00000001-0002-0003-0004-000000000005",
		"10000000000000000-1-1-1-1":             "",
		"123e4567-e89b-12d3-a456-4266141740001": "",
		"1-2-3-4-5" + strings.Repeat("0", 28):   "",
		"00000001-0002-0003-0004-00000000000005x": "",
	} {
		id, ok := javaUUID(in)
		got := ""
		if ok {
			got = id.String()
		}
		if got != want {
			t.Errorf("javaUUID(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestJavaInteger(t *testing.T) {
	for in, want := range map[string]int64{
		"9": 9, "+5": 5, "-1": -1, "010": 10, "0x10": 16, "0X1f": 31, "#10": 16, "-0x1": -1, "1 0": 10,
		"2147483647": 2147483647, "-2147483648": -2147483648, "-0x80000000": -2147483648,
	} {
		if got, ok := javaInteger(in); !ok || got != want {
			t.Errorf("javaInteger(%q) = %d, %v; want %d", in, got, ok, want)
		}
	}
	for _, in := range []string{"x", "1.5", "2147483648", "0x80000000", "+0x10", "0x", "0x-1", "#+1", "--1", "1e3"} {
		if got, ok := javaInteger(in); ok {
			t.Errorf("javaInteger(%q) = %d, want invalid", in, got)
		}
	}
}

// ecIdentity is a self-signed EC signing identity whose issuer names X9.
type ecIdentity struct {
	key  *ecdsa.PrivateKey
	cert *x509.Certificate
}

func (id ecIdentity) Chain() []*x509.Certificate { return []*x509.Certificate{id.cert} }

func newECIdentity(t *testing.T, curve elliptic.Curve, name string) ecIdentity {
	t.Helper()
	key, err := ecdsa.GenerateKey(curve, rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: name, Organization: []string{"X9 Example Test"}},
		NotBefore:    time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC),
		NotAfter:     time.Date(2036, 1, 1, 0, 0, 0, 0, time.UTC),
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, key.Public(), key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return ecIdentity{key: key, cert: cert}
}

func writePEMKeystore(t *testing.T, path string, id ecIdentity) {
	t.Helper()
	der, err := x509.MarshalPKCS8PrivateKey(id.key)
	if err != nil {
		t.Fatal(err)
	}
	data := append(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}),
		pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: id.cert.Raw})...)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
}
