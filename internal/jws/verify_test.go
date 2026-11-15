// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package jws

import (
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"math"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/go-jose/go-jose/v4"
	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/clock"
	"github.com/matera-inc/x9.150-qrcode/internal/emv"
	"github.com/matera-inc/x9.150-qrcode/internal/pki"
)

const testHost = "x9-150.example.com"

// newTestVerifier trusts the given keys' certificates and judges everything at at.
func newTestVerifier(t testing.TB, at time.Time, fetcher *pki.Fetcher, trusted ...testKey) *Verifier {
	t.Helper()
	var certs []*x509.Certificate
	for _, k := range trusted {
		certs = append(certs, k.chain...)
	}
	v, err := newVerifier(VerifierConfig{
		Truststore: pki.NewStaticTruststore(certs...),
		IssuerName: pki.DefaultIssuerName,
		Fetcher:    fetcher,
	}, fixedClock{at}, fixedClock{at})
	if err != nil {
		t.Fatal(err)
	}
	return v
}

// publisher serves a key's JWK set at JWKSetPath and its PEM chain at CertificatePath, as this
// service publishes its own, and counts what it is asked.
type publisher struct {
	srv     *httptest.Server
	fetched atomic.Int64
	jwks    atomic.Pointer[[]byte]
	pem     atomic.Pointer[[]byte]
}

func newPublisher(t *testing.T, key testKey, alg string) *publisher {
	t.Helper()
	p := &publisher{}
	p.publish(t, key, alg, KeyID)
	p.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p.fetched.Add(1)
		switch r.URL.Path {
		case JWKSetPath:
			_, _ = w.Write(*p.jwks.Load())
		case CertificatePath:
			_, _ = w.Write(*p.pem.Load())
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(p.srv.Close)
	return p
}

func (p *publisher) publish(t *testing.T, key testKey, alg, kid string) {
	t.Helper()
	set, err := json.Marshal(jose.JSONWebKeySet{Keys: []jose.JSONWebKey{{
		Key: key.Public(), KeyID: kid, Algorithm: alg, Use: "sig", Certificates: key.chain,
	}}})
	if err != nil {
		t.Fatal(err)
	}
	chain := make([]byte, 0, 2048*len(key.chain))
	for _, c := range key.chain {
		chain = append(chain, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: c.Raw})...)
	}
	p.jwks.Store(&set)
	p.pem.Store(&chain)
}

// fetcher reads this publisher for the test host, over plain HTTP, as the deployment reads its
// own certificate.
func (p *publisher) fetcher(t *testing.T) *pki.Fetcher {
	t.Helper()
	f, err := pki.NewFetcher(pki.FetcherConfig{Clock: fixedClock{epoch}, OwnHost: testHost, OwnBaseURL: p.srv.URL})
	if err != nil {
		t.Fatal(err)
	}
	return f
}

// S8.5-AC1: what Go signs, Go verifies, with every algorithm and every way of naming the
// certificate (x5c, x5u, jku): the header, the payload and the signer come back as signed.
func TestRoundTrip(t *testing.T) {
	for alg := range algorithms {
		for _, pub := range []Publication{PublishChain, PublishPEM, PublishJWKSet} {
			t.Run(string(alg)+"/"+string(pub), func(t *testing.T) {
				key := keyForAlg(t, string(alg))
				p := newPublisher(t, key, string(alg))
				s := newSigner(t, Config{Algorithm: string(alg), Publication: pub, Host: testHost}, fixedClock{epoch})
				id := uuid.New()
				payload := []byte(`{"amount":{"value":1790812800357}}`)
				token, err := s.Sign(key, Message{Payload: payload, CorrelationID: id, TTL: 30 * time.Second})
				if err != nil {
					t.Fatal(err)
				}
				v := newTestVerifier(t, epoch.Add(time.Second), p.fetcher(t), key)
				got, err := v.Verify(t.Context(), token, Expect{CorrelationID: id})
				if err != nil {
					t.Fatal(err)
				}
				h := got.Header
				if h.Algorithm != string(alg) || h.Type != TypeJOSE || h.KeyID != KeyID || h.CorrelationID != id ||
					h.IssuedAtMillis != epoch.UnixMilli() || h.TTLMillis != 30_000 || h.StatusCode != 0 ||
					h.X5TS256 != Thumbprint(key.chain[0]) || !h.ExpiresAt().Equal(epoch.Add(30*time.Second)) {
					t.Fatalf("header %+v", h)
				}
				if string(got.Payload) != string(payload) {
					t.Fatalf("payload %s", got.Payload)
				}
				if got.Signer != key.chain[0].Subject.String() || !got.Certificate.Equal(key.chain[0]) {
					t.Fatalf("signer %q", got.Signer)
				}
			})
		}
	}
}

// Story 2.3, the outbound check: a payee's signed answer is VERIFIED only when it verifies under
// the correlationId sent and signs the HTTP status it came with; otherwise the HTTP status stands,
// with the code and the detail. No body, or not JOSE, is ABSENT; anything else is INVALID.
func TestCheckAnswer(t *testing.T) {
	key := keyFor(t, "RSA")
	s := newSigner(t, Config{Publication: PublishChain}, fixedClock{epoch})
	v := newTestVerifier(t, epoch, nil, key)
	sent := uuid.New()
	yes, err := s.SignAnswer(key, sent, 200)
	if err != nil {
		t.Fatal(err)
	}
	noStatus, err := s.Sign(key, Message{Payload: []byte(`{"ok":true}`), CorrelationID: sent, StatusCode: 200})
	if err != nil {
		t.Fatal(err)
	}
	fraction, err := s.Sign(key, Message{Payload: []byte(`{"statusCode":200.5}`), CorrelationID: sent, StatusCode: 200})
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name      string
		http      int
		body      string
		sent      uuid.UUID
		signature AnswerSignature
		status    int
		problem   Problem
	}{
		{"verified", 200, yes, sent, AnswerVerified, 200, ""},
		{"verified with surrounding space", 200, "\n " + yes + " \n", sent, AnswerVerified, 200, ""},
		{"empty", 200, "  ", sent, AnswerAbsent, 200, AbsentBody},
		{"plain JSON", 200, `{"statusCode":200}`, sent, AnswerAbsent, 200, NotJWS},
		{"another question's answer", 200, yes, uuid.New(), AnswerInvalid, 200, CorrelationIDMismatch},
		{"signed 200, sent with 500", 500, yes, sent, AnswerInvalid, 500, StatusMismatch},
		{"no statusCode in the body", 200, noStatus, sent, AnswerInvalid, 200, StatusMissing},
		{"a fraction in the body", 200, fraction, sent, AnswerInvalid, 200, StatusMissing},
		{"altered", 200, yes[:len(yes)-2] + "AA", sent, AnswerInvalid, 200, BadSignature},
	} {
		t.Run(tc.name, func(t *testing.T) {
			a := v.CheckAnswer(t.Context(), tc.http, tc.body, tc.sent)
			if a.Signature != tc.signature || a.StatusCode != tc.status || a.Problem != tc.problem {
				t.Fatalf("%+v; want %s %d %s", a, tc.signature, tc.status, tc.problem)
			}
			if (a.Signature == AnswerVerified) != (a.Verified != nil) || (a.Signature != AnswerVerified) == (a.Detail == "") {
				t.Fatalf("%+v: a verified answer has no detail, any other has one", a)
			}
		})
	}
}

// Story 2.3, S10.6-AC3: a payee's answer refused by the certificate checks says why in its detail,
// not only whose certificate it was: the payer reads signatureDetail to tell an untrusted chain
// from a revoked certificate (X9-SIG-503).
func TestCheckAnswerSaysWhyTheCertificateWasRefused(t *testing.T) {
	key, stranger := keyFor(t, "RSA"), keyFor(t, "P-256")
	s := newSigner(t, Config{Algorithm: "ES256", Publication: PublishChain}, fixedClock{epoch})
	v := newTestVerifier(t, epoch, nil, key)
	sent := uuid.New()
	answer, err := s.SignAnswer(stranger, sent, 200)
	if err != nil {
		t.Fatal(err)
	}

	a := v.CheckAnswer(t.Context(), 200, answer, sent)

	if a.Problem != UntrustedChain {
		t.Fatalf("%+v; want %s", a, UntrustedChain)
	}
	if !strings.HasPrefix(a.Detail, "the signer's certificate: ") || len(a.Detail) <= len("the signer's certificate: ") {
		t.Fatalf("detail %q does not say why the certificate was refused", a.Detail)
	}
}

// A jku or x5u certificate is remembered only after the token it came with verified in full: a
// refused token never fills the cache, so the next one fetches again; a verified one does, so the
// next one with the same thumbprint does not (ADR-0027, "Certificate fetching").
func TestRefusedTokenNeverFillsTheCache(t *testing.T) {
	for _, pub := range []Publication{PublishPEM, PublishJWKSet} {
		t.Run(string(pub), func(t *testing.T) {
			key := keyFor(t, "P-256")
			p := newPublisher(t, key, "ES256")
			s := newSigner(t, Config{Algorithm: "ES256", Publication: pub, Host: testHost}, fixedClock{epoch})
			v := newTestVerifier(t, epoch, p.fetcher(t), key)
			good, err := s.Sign(key, Message{Payload: []byte(`{}`), CorrelationID: uuid.New()})
			if err != nil {
				t.Fatal(err)
			}
			parts := strings.Split(good, ".")
			altered := parts[0] + "." + base64.RawURLEncoding.EncodeToString([]byte(`{"x":1}`)) + "." + parts[2]

			for i := 1; i <= 2; i++ {
				if _, err := v.Verify(t.Context(), altered, Expect{}); ProblemOf(err) != BadSignature {
					t.Fatalf("altered: %v", err)
				}
				if got := p.fetched.Load(); got != int64(i) {
					t.Fatalf("after %d refused tokens, %d fetches: a refused token was remembered", i, got)
				}
			}
			for range 3 {
				if _, err := v.Verify(t.Context(), good, Expect{}); err != nil {
					t.Fatal(err)
				}
			}
			if got := p.fetched.Load(); got != 3 {
				t.Fatalf("%d fetches; the verified token's certificate should have been fetched once and remembered", got)
			}
			// The cache skips the fetch, never the checks.
			if _, err := v.Verify(t.Context(), altered, Expect{}); ProblemOf(err) != BadSignature {
				t.Fatalf("altered, from the cache: %v", err)
			}
		})
	}
}

// jku: the header's kid picks the key, whose alg must be the header's (Java's
// retrieveExternalJwkInformation). No fetcher refuses jku and x5u.
func TestJWKSet(t *testing.T) {
	key := keyFor(t, "RSA")
	p := newPublisher(t, key, "PS512")
	s := newSigner(t, Config{Algorithm: "PS512", Publication: PublishJWKSet, Host: testHost}, fixedClock{epoch})
	token, err := s.Sign(key, Message{Payload: []byte(`{}`), CorrelationID: uuid.New()})
	if err != nil {
		t.Fatal(err)
	}

	p.publish(t, key, "PS256", KeyID)
	if _, err := newTestVerifier(t, epoch, p.fetcher(t), key).Verify(t.Context(), token, Expect{}); ProblemOf(err) != BadHeader {
		t.Fatalf("the key's alg is PS256, the header's PS512: %v", err)
	}
	p.publish(t, key, "PS512", "another-key")
	if _, err := newTestVerifier(t, epoch, p.fetcher(t), key).Verify(t.Context(), token, Expect{}); ProblemOf(err) != CertificateUnavailable {
		t.Fatalf("no key with the header's kid: %v", err)
	}
	if _, err := newTestVerifier(t, epoch, nil, key).Verify(t.Context(), token, Expect{}); ProblemOf(err) != CertificateUnavailable {
		t.Fatalf("no fetcher: %v", err)
	}
}

// S8.6-AC3: iat and ttl are judged on the business clock, certificates on the real one. With the
// business clock ten years ahead, a token signed then verifies, although its certificate expires
// in an hour of real time; NewVerifier gives internal/pki no other clock.
func TestCertificatesAreJudgedOnTheRealClock(t *testing.T) {
	now := clock.New().Now()
	key := keyFor(t, "P-256")
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: "Go test real-clock signer", Organization: []string{"X9 test"}},
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     now.Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, key.Public(), key.Signer)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	shortLived := testKey{Signer: key.Signer, chain: []*x509.Certificate{cert}}

	business := clock.New(clock.AllowOffset())
	if err := business.SetOffset(10 * 365 * 24 * time.Hour); err != nil {
		t.Fatal(err)
	}
	s := newSigner(t, Config{Algorithm: "ES256", Publication: PublishChain}, business)
	token, err := s.Sign(shortLived, Message{Payload: []byte(`{}`), CorrelationID: uuid.New()})
	if err != nil {
		t.Fatal(err)
	}
	v, err := NewVerifier(VerifierConfig{Truststore: pki.NewStaticTruststore(cert), IssuerName: "X9"}, business)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := v.Verify(t.Context(), token, Expect{}); err != nil {
		t.Fatalf("a moved business clock judged the certificate: %v", err)
	}
	// And the business clock alone judges the token's window.
	if err := business.SetOffset(0); err != nil {
		t.Fatal(err)
	}
	if _, err := v.Verify(t.Context(), token, Expect{}); ProblemOf(err) != FutureIAT {
		t.Fatalf("a token from ten years ahead, on the real business clock: %v", err)
	}
}

// S8.5-AC1: the time window on the business clock, with the skew: an iat up to the skew ahead
// is accepted, further ahead is FUTURE_IAT; iat + ttl is EXPIRED. A negative skew is none.
func TestTimeWindow(t *testing.T) {
	key := keyFor(t, "P-256")
	s := newSigner(t, Config{Algorithm: "ES256", Publication: PublishChain}, fixedClock{epoch})
	token, err := s.Sign(key, Message{Payload: []byte(`{}`), CorrelationID: uuid.New(), TTL: time.Minute})
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		at   time.Duration // from iat
		skew time.Duration
		want Problem
	}{
		{-60 * time.Second, 0, ""},
		{-60*time.Second - time.Millisecond, 0, FutureIAT},
		{-5 * time.Second, 5 * time.Second, ""},
		{-5*time.Second - time.Millisecond, 5 * time.Second, FutureIAT},
		{-time.Millisecond, -1, FutureIAT},
		{0, -1, ""},
		{time.Minute - time.Millisecond, 0, ""},
		{time.Minute, 0, Expired},
	} {
		v, err := newVerifier(VerifierConfig{Truststore: pki.NewStaticTruststore(key.chain...), IssuerName: "X9", ClockSkew: tc.skew},
			fixedClock{epoch.Add(tc.at)}, fixedClock{epoch})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := v.Verify(t.Context(), token, Expect{}); ProblemOf(err) != tc.want {
			t.Errorf("at iat%+v, skew %s: %v; want %q", tc.at, tc.skew, err, tc.want)
		}
	}
}

// rawToken signs a header given field by field (a json.RawMessage is written as it is) and a
// payload, with key, as alg says.
func rawToken(t testing.TB, key testKey, alg string, header map[string]any, payload string) string {
	t.Helper()
	h, err := json.Marshal(header)
	if err != nil {
		t.Fatal(err)
	}
	input := base64.RawURLEncoding.EncodeToString(h) + "." + base64.RawURLEncoding.EncodeToString([]byte(payload))
	a := jose.SignatureAlgorithm(alg)
	sig, err := opaque{key: key, alg: a, hash: algorithms[a]}.SignPayload([]byte(input), a)
	if err != nil {
		t.Fatal(err)
	}
	return input + "." + base64.RawURLEncoding.EncodeToString(sig)
}

// profileHeader is a header that verifies at epoch, for the cases to change one thing in.
func profileHeader(key testKey, alg string) map[string]any {
	return map[string]any{
		"alg":           alg,
		"typ":           TypeJOSE,
		"kid":           KeyID,
		"correlationId": "64a7948e-6827-45db-8b20-d9162bcb0bc3",
		"iat":           epoch.UnixMilli(),
		"ttl":           300_000,
		"crit":          []string{HeaderCorrelationID, HeaderIssuedAt, HeaderTTL},
		HeaderX5TS256:   Thumbprint(key.chain[0]),
		HeaderX5C:       []string{base64.StdEncoding.EncodeToString(key.chain[0].Raw)},
	}
}

// S8.5-AC1: each check of the profile, with the code Java gives, beyond what the golden set
// pins; and where Java is lenient against the standard, the standard.
func TestProfile(t *testing.T) {
	rsaKey, ecKey := keyFor(t, "RSA"), keyFor(t, "P-256")
	v := newTestVerifier(t, epoch, nil, rsaKey, ecKey)
	raw := func(s string) json.RawMessage { return json.RawMessage(s) }
	type change func(h map[string]any)
	set := func(k string, val any) change { return func(h map[string]any) { h[k] = val } }
	del := func(k string) change { return func(h map[string]any) { delete(h, k) } }
	for _, tc := range []struct {
		name string
		alg  string
		with []change
		want Problem
	}{
		{"as made", "PS512", nil, ""},
		{"crit names a parameter twice", "PS512", []change{set("crit", []string{"iat", "correlationId", "ttl", "iat"})}, ""},
		{"crit is null", "PS512", []change{set("crit", nil)}, BadHeader},
		{"crit is a string", "PS512", []change{set("crit", "iat")}, NotJWS},
		{"crit names statusCode, a string of digits", "PS512", []change{
			set("crit", []string{"correlationId", "iat", "ttl", "statusCode"}), set("statusCode", "404")}, ""},
		{"statusCode 299, no status Spring knows", "PS512", []change{
			set("crit", []string{"correlationId", "iat", "ttl", "statusCode"}), set("statusCode", 299)}, BadHeader},
		{"statusCode 200.0", "PS512", []change{
			set("crit", []string{"correlationId", "iat", "ttl", "statusCode"}), set("statusCode", raw("200.0"))}, BadHeader},
		{"statusCode outside crit is not read", "PS512", []change{set("statusCode", "anything")}, ""},
		{"correlationId in capitals", "PS512", []change{set("correlationId", "64A7948E-6827-45DB-8B20-D9162BCB0BC3")}, ""},
		// Java's UUID.fromString takes these; X9.150 §10.7 asks for a valid UUID format.
		{"correlationId 1-2-3-4-5", "PS512", []change{set("correlationId", "1-2-3-4-5")}, BadHeader},
		{"correlationId without dashes", "PS512", []change{set("correlationId", "64a7948e682745db8b20d9162bcb0bc3")}, BadHeader},
		{"correlationId as a URN", "PS512", []change{set("correlationId", "urn:uuid:64a7948e-6827-45db-8b20-d9162bcb0bc3")}, BadHeader},
		{"correlationId a number", "PS512", []change{set("correlationId", 7)}, BadHeader},
		{"correlationId null", "PS512", []change{set("correlationId", nil)}, BadHeader},
		{"iat with an exponent", "PS512", []change{set("iat", raw("1.790812800000e12"))}, BadHeader},
		{"iat past 64 bits", "PS512", []change{set("iat", raw("9223372036854775808"))}, BadHeader},
		{"iat -0", "PS512", []change{set("iat", raw("-0"))}, BadHeader},
		{"iat the largest 64-bit integer", "PS512", []change{set("iat", raw("9223372036854775807"))}, FutureIAT},
		{"ttl past the largest instant", "PS512", []change{set("ttl", raw("9223372036854775807"))}, Expired},
		{"typ a number", "PS512", []change{set("typ", 5)}, NotJWS},
		{"typ absent", "PS512", []change{del("typ")}, ""},
		{"x5c empty, nothing else", "PS512", []change{set("x5c", []string{})}, BadHeader},
		{"x5c not a certificate, and expired", "PS512", []change{set("x5c", []string{"AAAA"}), set("iat", epoch.Add(-time.Hour).UnixMilli())}, Expired},
		{"x5t#S256 of no certificate", "PS512", []change{set(HeaderX5TS256, Thumbprint(ecKey.chain[0]))}, UntrustedChain},
		{"x5t#S256 a number", "PS512", []change{set(HeaderX5TS256, 1)}, NotJWS},
		{"kid a number", "PS512", []change{set("kid", 1)}, NotJWS},
		{"jwk a string", "PS512", []change{set("jwk", "key")}, NotJWS},
		{"alg HS256", "PS512", []change{set("alg", "HS256")}, BadHeader},
		{"alg RS256 over an EC certificate", "ES256", []change{set("alg", "RS256")}, Unverifiable},
		{"ES384 over a P-256 key", "ES384", nil, BadSignature},
	} {
		t.Run(tc.name, func(t *testing.T) {
			key := rsaKey
			if strings.HasPrefix(tc.alg, "ES") {
				key = ecKey
			}
			h := profileHeader(key, tc.alg)
			for _, c := range tc.with {
				c(h)
			}
			token := rawToken(t, key, tc.alg, h, `{}`)
			if _, err := v.Verify(t.Context(), token, Expect{}); ProblemOf(err) != tc.want {
				t.Fatalf("%v; want %q", err, tc.want)
			}
		})
	}
}

// S8.5-AC1: what is not a JWS says what it is.
func TestNotAJWS(t *testing.T) {
	v := newTestVerifier(t, epoch, nil, keyFor(t, "RSA"))
	b64 := func(s string) string { return base64.RawURLEncoding.EncodeToString([]byte(s)) }
	for _, tc := range []struct {
		name, token string
		want        Problem
	}{
		{"empty", "", AbsentBody},
		{"blank", " \n\t", AbsentBody},
		{"text", "hello", NotJWS},
		{"four parts", "a.b.c.d", NotJWS},
		{"a JWE", b64(`{"alg":"RSA-OAEP","enc":"A256GCM"}`) + ".a.b.c.d", Encrypted},
		{"five parts, no enc", b64(`{"alg":"PS512"}`) + ".a.b.c.d", NotJWS},
		{"header not JSON", b64("{") + "." + b64("{}") + ".c2ln", NotJWS},
		{"header an array", b64("[]") + "." + b64("{}") + ".c2ln", NotJWS},
		{"no alg", b64(`{"typ":"JOSE"}`) + "." + b64("{}") + ".c2ln", NotJWS},
		{"alg none", b64(`{"alg":"none"}`) + "." + b64("{}") + ".", AlgNone},
		{"alg none with a signature", b64(`{"alg":"none"}`) + "." + b64("{}") + ".c2ln", NotJWS},
		{"padded base64", b64(`{"alg":"PS512"}`) + "=." + b64("{}") + ".c2ln", NotJWS},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := v.Verify(t.Context(), tc.token, Expect{}); ProblemOf(err) != tc.want {
				t.Fatalf("%v; want %q", err, tc.want)
			}
		})
	}
}

// A payload request's QR content must name the location asked for (Java's validateLocationId).
func TestLocationContent(t *testing.T) {
	key := keyFor(t, "P-256")
	v := newTestVerifier(t, epoch, nil, key)
	qr, err := emv.Encode(emv.LocationURL("x9.example", "01JD4ZQ7XK"), emv.Merchant{CategoryCode: "5812", Country: "US", Name: "Test", City: "Austin"})
	if err != nil {
		t.Fatal(err)
	}
	content := `{"qrCodeContent":"` + base64.StdEncoding.EncodeToString([]byte(qr)) + `"}`
	for _, tc := range []struct {
		name, payload, location string
		want                    Problem
	}{
		{"its location, in another case", content, "01jd4zq7xk", ""},
		{"another location", content, "01JD4ZQ7XL", ContentMismatch},
		{"no qrCodeContent", `{}`, "01JD4ZQ7XK", ContentMismatch},
		{"not base64", `{"qrCodeContent":"%%%"}`, "01JD4ZQ7XK", ContentMismatch},
		{"not EMV", `{"qrCodeContent":"aGVsbG8="}`, "01JD4ZQ7XK", ContentMismatch},
		{"not an object", `[]`, "01JD4ZQ7XK", Unverifiable},
		{"not asked", `[]`, "", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			token := rawToken(t, key, "ES256", profileHeader(key, "ES256"), tc.payload)
			got, err := v.Verify(t.Context(), token, Expect{LocationID: tc.location})
			if ProblemOf(err) != tc.want {
				t.Fatalf("%v; want %q", err, tc.want)
			}
			if err == nil && tc.location != "" && got.QRCodeContent != qr {
				t.Fatalf("QR content %q", got.QRCodeContent)
			}
		})
	}
}

// S8.5-AC3: a millisecond iat is read exactly. 2^53 + 1 is the first integer a float64 cannot
// hold: through float64 it would read as 2^53, and the token would expire a millisecond early.
func TestIATNeverPassesThroughFloat64(t *testing.T) {
	const iat = int64(1)<<53 + 1
	h, r := parseHeader([]byte(`{"alg":"PS512","crit":["correlationId","iat","ttl"],"correlationId":"64a7948e-6827-45db-8b20-d9162bcb0bc3","iat":9007199254740993,"ttl":1}`), []byte{1})
	if r != nil {
		t.Fatal(r)
	}
	p, r := checkProfile(h, iat, 0)
	if r != nil {
		t.Fatalf("at iat itself: %v", r)
	}
	if p.iat != iat {
		t.Fatalf("iat read as %d, want %d", p.iat, iat)
	}
	if _, r := checkProfile(h, iat+1, 0); r == nil || r.Problem != Expired {
		t.Fatalf("at iat + ttl: %v", r)
	}
	if r := window(math.MaxInt64, 1, math.MaxInt64, math.MaxInt64); r == nil || r.Problem != Expired {
		t.Fatalf("the largest iat: %v", r)
	}
}
