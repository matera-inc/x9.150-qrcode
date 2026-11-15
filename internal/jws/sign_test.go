// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package jws

import (
	"bytes"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"math/big"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/clock"
	"github.com/matera-inc/x9.150-qrcode/internal/config"
	"github.com/matera-inc/x9.150-qrcode/internal/keys"
	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

// epoch is the golden set's: every Java fixture was signed with the clock moved there.
var epoch = time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)

// fixedClock is a business clock that does not move.
type fixedClock struct{ at time.Time }

func (c fixedClock) Now() time.Time { return c.at }

// testKey is a key with a self-signed certificate.
type testKey struct {
	crypto.Signer
	chain []*x509.Certificate
}

func (k testKey) Chain() []*x509.Certificate { return k.chain }

func newTestKey(t testing.TB, signer crypto.Signer, cn string) testKey {
	t.Helper()
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: cn, Organization: []string{"X9 test"}},
		NotBefore:    epoch.Add(-24 * time.Hour),
		NotAfter:     epoch.Add(365 * 24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, signer.Public(), signer)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return testKey{Signer: signer, chain: []*x509.Certificate{cert}}
}

// The test keys are made once per run: an RSA key takes a while.
var (
	keysOnce sync.Once
	testKeys map[string]testKey
	keysErr  error
)

func keyFor(t testing.TB, name string) testKey {
	t.Helper()
	keysOnce.Do(func() {
		testKeys = map[string]testKey{}
		rsaKey, err := rsa.GenerateKey(rand.Reader, 2048)
		if err != nil {
			keysErr = err
			return
		}
		testKeys["RSA"] = newTestKey(t, rsaKey, "Go test RSA signer")
		for name, curve := range map[string]elliptic.Curve{"P-256": elliptic.P256(), "P-384": elliptic.P384(), "P-521": elliptic.P521()} {
			ecKey, err := ecdsa.GenerateKey(curve, rand.Reader)
			if err != nil {
				keysErr = err
				return
			}
			testKeys[name] = newTestKey(t, ecKey, "Go test "+name+" signer")
		}
	})
	if keysErr != nil {
		t.Fatal(keysErr)
	}
	k, ok := testKeys[name]
	if !ok {
		t.Fatalf("no test key %s", name)
	}
	return k
}

// keyForAlg is the key an algorithm signs with.
func keyForAlg(t testing.TB, alg string) testKey {
	t.Helper()
	switch alg {
	case "ES256":
		return keyFor(t, "P-256")
	case "ES384":
		return keyFor(t, "P-384")
	case "ES512":
		return keyFor(t, "P-521")
	default:
		return keyFor(t, "RSA")
	}
}

func newSigner(t testing.TB, cfg Config, clk Clock) *Signer {
	t.Helper()
	if cfg.Host == "" {
		cfg.Host = "x9-150.example.com"
	}
	s, err := New(cfg, clk)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

// decoded is a compact JWS taken apart, its header decoded with every number kept as written.
type decoded struct {
	header    map[string]any
	rawHeader []byte
	payload   []byte
	input     []byte
	signature []byte
}

func decode(t testing.TB, token string) decoded {
	t.Helper()
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		t.Fatalf("not a compact JWS: %q", token)
	}
	var d decoded
	var err error
	if d.rawHeader, err = base64.RawURLEncoding.DecodeString(parts[0]); err != nil {
		t.Fatal(err)
	}
	if d.payload, err = base64.RawURLEncoding.DecodeString(parts[1]); err != nil {
		t.Fatal(err)
	}
	if d.signature, err = base64.RawURLEncoding.DecodeString(parts[2]); err != nil {
		t.Fatal(err)
	}
	d.input = []byte(parts[0] + "." + parts[1])
	dec := json.NewDecoder(bytes.NewReader(d.rawHeader))
	dec.UseNumber()
	if err := dec.Decode(&d.header); err != nil {
		t.Fatal(err)
	}
	return d
}

// verifyStdlib checks the signature with the standard library only: no go-jose on this side.
func verifyStdlib(t testing.TB, d decoded, pub crypto.PublicKey) {
	t.Helper()
	alg, _ := d.header["alg"].(string)
	hash := map[string]crypto.Hash{"256": crypto.SHA256, "384": crypto.SHA384, "512": crypto.SHA512}[alg[2:]]
	h := hash.New()
	h.Write(d.input)
	digest := h.Sum(nil)
	var err error
	switch alg[:2] {
	case "RS":
		err = rsa.VerifyPKCS1v15(pub.(*rsa.PublicKey), hash, digest, d.signature)
	case "PS":
		err = rsa.VerifyPSS(pub.(*rsa.PublicKey), hash, digest, d.signature, &rsa.PSSOptions{SaltLength: rsa.PSSSaltLengthAuto})
	case "ES":
		ec := pub.(*ecdsa.PublicKey)
		size := (ec.Curve.Params().BitSize + 7) / 8
		if len(d.signature) != 2*size {
			t.Fatalf("%s signature is %d bytes, want R || S of %d", alg, len(d.signature), 2*size)
		}
		r, s := new(big.Int).SetBytes(d.signature[:size]), new(big.Int).SetBytes(d.signature[size:])
		if !ecdsa.Verify(ec, digest, r, s) {
			err = errors.New("ECDSA signature does not verify")
		}
	}
	if err != nil {
		t.Fatalf("%s: %v", alg, err)
	}
}

// integer reports whether v is a JSON number written as an integer: no fraction, no exponent.
func integer(v any) (int64, bool) {
	n, ok := v.(json.Number)
	if !ok || strings.ContainsAny(string(n), ".eE") {
		return 0, false
	}
	i, err := n.Int64()
	return i, err == nil
}

// S8.4-AC1: the header is the X9.150 profile the Java build signs: alg, kid, typ JOSE,
// correlationId, iat and ttl as integer milliseconds, crit naming exactly those three in that
// order, x5t#S256, and the certificate named the configured way and only that way.
func TestHeaderProfile(t *testing.T) {
	key := keyFor(t, "RSA")
	at := epoch.Add(1234 * time.Millisecond)
	id := uuid.MustParse("64a7948e-6827-45db-8b20-d9162bcb0bc3")
	for _, tc := range []struct {
		publication Publication
		want        map[string]string // the one certificate header and its value
	}{
		{PublishJWKSet, map[string]string{"jku": "https://x9-150.example.com/pub/.well-known/jwks"}},
		{PublishPEM, map[string]string{"x5u": "https://x9-150.example.com/pub/.well-known/certificate/x9-key.pem"}},
		{PublishChain, map[string]string{"x5c": base64.StdEncoding.EncodeToString(key.chain[0].Raw)}},
	} {
		t.Run(string(tc.publication), func(t *testing.T) {
			s := newSigner(t, Config{Publication: tc.publication}, fixedClock{at})
			token, err := s.Sign(key, Message{Payload: []byte(`{"qrCodeContent":"MDA="}`), CorrelationID: id})
			if err != nil {
				t.Fatal(err)
			}
			d := decode(t, token)
			h := d.header
			if h["alg"] != "PS512" || h["kid"] != "x9-key" || h["typ"] != "JOSE" || h["correlationId"] != id.String() {
				t.Errorf("header = %s", d.rawHeader)
			}
			if iat, ok := integer(h["iat"]); !ok || iat != at.UnixMilli() {
				t.Errorf("iat = %v, want the integer %d (ms)", h["iat"], at.UnixMilli())
			}
			if ttl, ok := integer(h["ttl"]); !ok || ttl != 300000 {
				t.Errorf("ttl = %v, want the integer 300000 (ms)", h["ttl"])
			}
			if diff := cmp.Diff([]any{"correlationId", "iat", "ttl"}, h["crit"]); diff != "" {
				t.Errorf("crit (-want +got):\n%s", diff)
			}
			sum := sha256.Sum256(key.chain[0].Raw)
			if h["x5t#S256"] != base64.RawURLEncoding.EncodeToString(sum[:]) {
				t.Errorf("x5t#S256 = %v", h["x5t#S256"])
			}
			for _, name := range []string{"jku", "x5u", "x5c"} {
				want, wanted := tc.want[name]
				got, present := h[name]
				switch {
				case wanted && name == "x5c":
					if diff := cmp.Diff([]any{want}, got); diff != "" {
						t.Errorf("x5c (-want +got):\n%s", diff)
					}
				case wanted && got != want:
					t.Errorf("%s = %v, want %s", name, got, want)
				case !wanted && present:
					t.Errorf("%s is present with publication %s", name, tc.publication)
				}
			}
			if _, present := h["statusCode"]; present {
				t.Error("a question carries statusCode")
			}
			if string(d.payload) != `{"qrCodeContent":"MDA="}` {
				t.Errorf("payload = %s", d.payload)
			}
			verifyStdlib(t, d, key.Public())
		})
	}
}

// S8.4-AC1: PS512 by default, and every RS*, PS* and ES* algorithm signs, each ES on its own curve,
// with a signature the standard library verifies (ECDSA as JOSE's R || S, not ASN.1).
func TestEveryAlgorithmSigns(t *testing.T) {
	s, err := New(Config{Host: "x9.example"}, fixedClock{epoch})
	if err != nil {
		t.Fatal(err)
	}
	if s.Algorithm() != "PS512" {
		t.Errorf("default algorithm = %s, want PS512", s.Algorithm())
	}
	for _, alg := range []string{"RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512"} {
		t.Run(alg, func(t *testing.T) {
			key := keyForAlg(t, alg)
			s := newSigner(t, Config{Algorithm: alg, Publication: PublishChain}, fixedClock{epoch})
			token, err := s.Sign(key, Message{Payload: []byte(`{}`), CorrelationID: NewCorrelationID()})
			if err != nil {
				t.Fatal(err)
			}
			d := decode(t, token)
			if d.header["alg"] != alg {
				t.Fatalf("alg = %v, want %s", d.header["alg"], alg)
			}
			verifyStdlib(t, d, key.Public())
		})
	}
}

// S8.4-AC1: a key that cannot produce the configured algorithm is refused, naming both halves, as
// the Java build refuses it at start; so is a certificate that does not hold the key.
func TestKeyThatCannotSignIsRefused(t *testing.T) {
	other := keyFor(t, "P-384")
	for _, tc := range []struct {
		name string
		alg  string
		key  Key
		want string
	}{
		{"EC key, RSA algorithm", "PS512", keyFor(t, "P-256"), "PS512 needs an RSA key, the key is EC (P-256); use ES256"},
		{"RSA key, EC algorithm", "ES256", keyFor(t, "RSA"), "ES256 needs an EC key, the key is RSA"},
		{"ES384 on P-256", "ES384", keyFor(t, "P-256"), "ES384 signs on P-384, the key is on P-256; use ES256"},
		{"ES256 on P-521", "ES256", keyFor(t, "P-521"), "ES256 signs on P-256, the key is on P-521; use ES512"},
		{"certificate of another key", "ES256", testKey{Signer: keyFor(t, "P-256").Signer, chain: other.chain}, "does not hold the signing key"},
		{"no certificate", "ES256", testKey{Signer: keyFor(t, "P-256").Signer}, "the key has no certificate"},
		{"no key", "ES256", nil, "no key"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := newSigner(t, Config{Algorithm: tc.alg}, fixedClock{epoch})
			err := s.Check(tc.key)
			if !errors.Is(err, ErrKey) || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("Check = %v, want ErrKey containing %q", err, tc.want)
			}
			if _, err := s.Sign(tc.key, Message{Payload: []byte(`{}`), CorrelationID: NewCorrelationID()}); !errors.Is(err, ErrKey) {
				t.Fatalf("Sign = %v, want ErrKey", err)
			}
		})
	}
}

// CheckKey is what the service runs at start, before any Signer exists.
func TestCheckKey(t *testing.T) {
	if err := CheckKey("ES512", keyFor(t, "P-521")); err != nil {
		t.Fatal(err)
	}
	if err := CheckKey("PS512", keyFor(t, "P-521")); !errors.Is(err, ErrKey) {
		t.Fatalf("CheckKey(PS512, P-521) = %v, want ErrKey", err)
	}
	if err := CheckKey("HS512", keyFor(t, "RSA")); !errors.Is(err, ErrConfig) {
		t.Fatalf("CheckKey(HS512) = %v, want ErrConfig", err)
	}
}

func TestInvalidConfigurationIsRefused(t *testing.T) {
	for _, tc := range []struct {
		name string
		cfg  Config
		want string
	}{
		{"HS256", Config{Algorithm: "HS256", Host: "x9.example"}, `algorithm "HS256"`},
		{"none", Config{Algorithm: "none", Host: "x9.example"}, `algorithm "none"`},
		{"ES256K", Config{Algorithm: "ES256K", Host: "x9.example"}, `algorithm "ES256K"`},
		{"EdDSA", Config{Algorithm: "EdDSA", Host: "x9.example"}, `algorithm "EdDSA"`},
		{"publication", Config{Publication: "X5C", Host: "x9.example"}, `publication "X5C"`},
		{"jku without a host", Config{}, "publication JWK_SET needs the public host"},
		{"x5u without a host", Config{Publication: PublishPEM}, "publication PEM needs the public host"},
		{"negative ttl", Config{TTL: -time.Second, Host: "x9.example"}, "less than a millisecond"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := New(tc.cfg, fixedClock{epoch})
			if !errors.Is(err, ErrConfig) || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("New = %v, want ErrConfig containing %q", err, tc.want)
			}
		})
	}
	if _, err := New(Config{Publication: PublishChain}, nil); !errors.Is(err, ErrConfig) {
		t.Fatalf("New without a clock = %v", err)
	}
	// The chain needs no host.
	if _, err := New(Config{Publication: PublishChain}, fixedClock{epoch}); err != nil {
		t.Fatal(err)
	}
}

func TestInvalidMessageIsRefused(t *testing.T) {
	s := newSigner(t, Config{}, fixedClock{epoch})
	key := keyFor(t, "RSA")
	for _, tc := range []struct {
		name string
		m    Message
		want string
	}{
		{"no correlationId", Message{Payload: []byte(`{}`)}, "correlationId is required"},
		{"not JSON", Message{Payload: []byte(`{`), CorrelationID: NewCorrelationID()}, "not JSON"},
		{"no payload", Message{CorrelationID: NewCorrelationID()}, "not JSON"},
		{"negative ttl", Message{Payload: []byte(`{}`), CorrelationID: NewCorrelationID(), TTL: -time.Second}, "not a positive number of milliseconds"},
		{"ttl under a millisecond", Message{Payload: []byte(`{}`), CorrelationID: NewCorrelationID(), TTL: time.Microsecond}, "not a positive number of milliseconds"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := s.Sign(key, tc.m)
			if !errors.Is(err, ErrMessage) || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("Sign = %v, want ErrMessage containing %q", err, tc.want)
			}
		})
	}
}

// S8.4-AC1: a message's own ttl (the signing endpoint's ttlTimeSeconds) is written in milliseconds;
// without one, the configured ttl is.
func TestTTLIsMilliseconds(t *testing.T) {
	key := keyFor(t, "P-256")
	s := newSigner(t, Config{Algorithm: "ES256", TTL: 45 * time.Second}, fixedClock{epoch})
	for _, tc := range []struct {
		ttl  time.Duration
		want int64
	}{{0, 45000}, {30 * time.Second, 30000}, {1500 * time.Millisecond, 1500}} {
		token, err := s.Sign(key, Message{Payload: []byte(`{}`), CorrelationID: NewCorrelationID(), TTL: tc.ttl})
		if err != nil {
			t.Fatal(err)
		}
		if got, ok := integer(decode(t, token).header["ttl"]); !ok || got != tc.want {
			t.Errorf("TTL %s: ttl = %v, want %d", tc.ttl, got, tc.want)
		}
	}
}

// S8.4-AC1: iat is the business clock (internal/clock), in milliseconds: a clock moved to the
// golden epoch signs at the epoch, as Java does with its test clock moved.
func TestIATIsTheBusinessClock(t *testing.T) {
	clk := clock.New(clock.AllowOffset())
	if err := clk.SetNow(epoch); err != nil {
		t.Fatal(err)
	}
	s := newSigner(t, Config{}, clk)
	token, err := s.Sign(keyFor(t, "RSA"), Message{Payload: []byte(`{}`), CorrelationID: NewCorrelationID()})
	if err != nil {
		t.Fatal(err)
	}
	iat, ok := integer(decode(t, token).header["iat"])
	if !ok || iat < epoch.UnixMilli() || iat > epoch.Add(time.Minute).UnixMilli() {
		t.Fatalf("iat = %d, want the moved clock's %d (ms) or just after", iat, epoch.UnixMilli())
	}
}

// S8.4-AC2: the answer to a notification is a JWS over exactly {"statusCode":n}, typ JOSE, with
// statusCode in the header and in crit after correlationId, iat and ttl, and the notification's
// correlationId echoed.
func TestSignedAnswer(t *testing.T) {
	key := keyFor(t, "RSA")
	s := newSigner(t, Config{Publication: PublishChain}, fixedClock{epoch})
	id := uuid.MustParse("5e9188da-d7da-4e4e-b0e8-1897386ba738")
	for _, status := range []int{200, 400, 401, 404, 409, 500} {
		token, err := s.SignAnswer(key, id, status)
		if err != nil {
			t.Fatal(err)
		}
		d := decode(t, token)
		if want := `{"statusCode":` + strconv.Itoa(status) + `}`; string(d.payload) != want {
			t.Errorf("body = %s, want %s", d.payload, want)
		}
		if got, ok := integer(d.header["statusCode"]); !ok || got != int64(status) {
			t.Errorf("header statusCode = %v, want %d", d.header["statusCode"], status)
		}
		if diff := cmp.Diff([]any{"correlationId", "iat", "ttl", "statusCode"}, d.header["crit"]); diff != "" {
			t.Errorf("crit (-want +got):\n%s", diff)
		}
		if d.header["typ"] != "JOSE" || d.header["correlationId"] != id.String() {
			t.Errorf("header = %s", d.rawHeader)
		}
		verifyStdlib(t, d, key.Public())
	}
	for _, status := range []int{0, 99, 600} {
		if _, err := s.SignAnswer(key, id, status); !errors.Is(err, ErrMessage) {
			t.Errorf("SignAnswer(%d) = %v, want ErrMessage", status, err)
		}
	}
}

// S8.4-AC1: the configuration's defaults are Java's: PS512, jku, 300 s, and the public host
// x9-150.example.com when none is set; and the service's own signing key (the bundled demo
// identity, RSA) can sign with them.
func TestConfigFromDefaults(t *testing.T) {
	cfg, err := config.Load("", []string{
		"DATABASE_HOST=db.example.com", "DATABASE_NAME=bank", "DATABASE_USER=app",
		"DATABASE_PASSWORD=secret", "DATABASE_SSLMODE=require",
	})
	if err != nil {
		t.Fatal(err)
	}
	got := ConfigFrom(cfg)
	want := Config{Algorithm: "PS512", Publication: PublishJWKSet, Host: "x9-150.example.com", TTL: 300 * time.Second}
	if got != want {
		t.Fatalf("ConfigFrom = %+v, want %+v", got, want)
	}
	store, err := keys.Load(config.Certificate{}, slog.New(slog.DiscardHandler))
	if err != nil {
		t.Fatal(err)
	}
	s := newSigner(t, got, fixedClock{epoch})
	id := store.Current().Identity
	token, err := s.Sign(id, Message{Payload: []byte(`{}`), CorrelationID: NewCorrelationID()})
	if err != nil {
		t.Fatal(err)
	}
	d := decode(t, token)
	if d.header["jku"] != "https://x9-150.example.com/pub/.well-known/jwks" || d.header["x5t#S256"] != Thumbprint(id.Certificate()) {
		t.Errorf("header = %s", d.rawHeader)
	}
	verifyStdlib(t, d, id.Public())
}

// A host that already has a scheme keeps it, as Java's location service keeps it.
func TestPublicURL(t *testing.T) {
	for host, want := range map[string]string{
		"x9.example":               "https://x9.example/pub/.well-known/jwks",
		"qr.example.org:8443":      "https://qr.example.org:8443/pub/.well-known/jwks",
		"http://localhost:8080":    "http://localhost:8080/pub/.well-known/jwks",
		"https://x9.example/":      "https://x9.example/pub/.well-known/jwks",
		" x9-150.example.com ":     "https://x9-150.example.com/pub/.well-known/jwks",
		"https://x9.example:443//": "https://x9.example:443/pub/.well-known/jwks",
	} {
		if got := publicURL(host, JWKSetPath); got != want {
			t.Errorf("publicURL(%q) = %s, want %s", host, got, want)
		}
	}
}

func TestRawECDSARefusesMalformed(t *testing.T) {
	for _, der := range [][]byte{nil, {0x30, 0x00}, {0x30, 0x06, 0x02, 0x01, 0x00, 0x02, 0x01, 0x01}, []byte("not asn1")} {
		if _, err := rawECDSA(der, elliptic.P256()); err == nil {
			t.Errorf("rawECDSA(%x) accepted", der)
		}
	}
}
