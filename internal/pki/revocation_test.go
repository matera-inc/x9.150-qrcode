// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package pki

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"net/http"
	"path/filepath"
	"testing"
	"time"

	"golang.org/x/crypto/ocsp"
)

// S8.3-AC2, S8.3-AC4: ADR-0027's table, on the very certificates the JUnit tests use. Each names
// its sources on 127.0.0.1:1, which the test network refuses at once, as nothing listens there.
func TestADR0027Table(t *testing.T) {
	for _, tc := range []struct {
		keys   string
		p12    string
		accept bool
	}{
		{"X9-SIG-058, X9-LOC-307: CRL and OCSP named, neither reachable", "x9-test-payer-both-unreachable.p12", true},
		{"X9-SIG-057, X9-LOC-307: OCSP only, unreachable", "x9-test-payer-ocsp-unreachable.p12", true},
		{"X9-SIG-055, X9-SIG-056, X9-LOC-307: CRL only, unreachable", "x9-test-payer-crl-unreachable.p12", true},
		{"X9-SIG-059, X9-LOC-308: neither named", "x9-test-payer-no-revocation.p12", false},
	} {
		t.Run(tc.keys, func(t *testing.T) {
			f := newFixture(t)
			leaf := leafOf(t, tc.p12)
			_, err := f.verify(t, leaf)
			if tc.accept {
				if err != nil {
					t.Fatalf("an unreachable source must soft-fail: %v", err)
				}
				if f.net.dialed("127.0.0.1:1") == 0 {
					t.Fatal("the named source was never tried")
				}
				return
			}
			wantProblem(t, err, UntrustedChain)
		})
	}
}

// S8.3-AC2: a golden signer whose CRL server is down soft-fails too (X9-SIG-055), and is refused
// as soon as the server is back with the certificate listed (X9-SIG-053).
func TestCRLServerDown(t *testing.T) {
	f := newFixture(t)
	f.net = newTestNetwork() // nothing routed
	f.verifier = f.newVerifier(t, DefaultIssuerName, f.anchor)
	if _, err := f.verify(t, f.signer(t, "revoked")...); err != nil {
		t.Fatalf("soft-fail expected while the CRL cannot be reached: %v", err)
	}
	if f.net.dialed(crlHostPort) == 0 {
		t.Fatal("the CRL was never asked for")
	}
}

// S8.3-AC2, X9-LOC-306: a certificate naming only an OCSP responder is decided by it: good is
// accepted, revoked refused; "unknown" is no answer, and soft-fails.
func TestOCSPOnly(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		accept bool
	}{
		{"good", ocsp.Good, true},
		{"revoked", ocsp.Revoked, false},
		{"unknown", ocsp.Unknown, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newFixture(t)
			leaf := f.ca.issue(t, "Payer OCSP "+tc.name, "", ocspURL, f.clock.Now().Add(-time.Hour))
			f.ocsp.set(leaf, tc.status)
			_, err := f.verify(t, leaf)
			if f.ocsp.asked() == 0 {
				t.Fatal("the OCSP responder was never asked")
			}
			if tc.accept && err != nil {
				t.Fatal(err)
			}
			if !tc.accept {
				wantProblem(t, err, UntrustedChain)
			}
		})
	}
}

// S8.3-AC2: what the JDK forgives of an OCSP answer and what it does not. tryLater, an HTTP error
// and an answer that cannot be parsed are no answer (soft-fail); any other error status, a
// signature by a responder the issuer did not authorise, and an out-of-date answer refuse.
// Pinned by the JDK's OCSPResponse.verify and RevocationChecker.checkOCSP; no Java test key.
func TestOCSPAnswers(t *testing.T) {
	tryLater := []byte{0x30, 0x03, 0x0a, 0x01, 0x03}
	unauthorized := []byte{0x30, 0x03, 0x0a, 0x01, 0x06}
	for _, tc := range []struct {
		name   string
		setup  func(t *testing.T, f *fixture)
		accept bool
	}{
		{"tryLater", func(_ *testing.T, f *fixture) { f.ocsp.raw = tryLater }, true},
		{"garbage", func(_ *testing.T, f *fixture) { f.ocsp.raw = []byte("not an OCSP response") }, true},
		{"HTTP 500", func(t *testing.T, f *fixture) {
			f.net.route("ocsp.test-ca.example:80", server(t, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(http.StatusInternalServerError)
			})))
		}, true},
		{"unauthorized", func(_ *testing.T, f *fixture) { f.ocsp.raw = unauthorized }, false},
		{"signed by an impostor", func(t *testing.T, f *fixture) { f.ocsp.signer = impostor(t, f) }, false},
		{"out of date", func(_ *testing.T, f *fixture) {
			f.ocsp.clock = newTestClock(f.clock.Now().Add(-3 * time.Hour))
		}, false},
		{"a delegated responder", func(t *testing.T, f *fixture) { f.ocsp.signer = delegated(t, f, true) }, true},
		{"a delegated responder without OCSPSigning", func(t *testing.T, f *fixture) { f.ocsp.signer = delegated(t, f, false) }, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newFixture(t)
			leaf := f.ca.issue(t, "Payer OCSP", "", ocspURL, f.clock.Now().Add(-time.Hour))
			tc.setup(t, f)
			_, err := f.verify(t, leaf)
			if tc.accept && err != nil {
				t.Fatal(err)
			}
			if !tc.accept {
				wantProblem(t, err, UntrustedChain)
			}
		})
	}
}

func impostor(t *testing.T, f *fixture) *testCA {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	name := pkix.Name{CommonName: "Example Impostor Responder"}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(7), Subject: name, NotBefore: f.clock.Now().Add(-time.Hour),
		NotAfter: f.clock.Now().Add(time.Hour), ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageOCSPSigning},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return &testCA{cert: cert, key: key}
}

func delegated(t *testing.T, f *fixture, ocspSigning bool) *testCA {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(8), Subject: pkix.Name{CommonName: "Test CA OCSP Responder"},
		NotBefore: f.clock.Now().Add(-time.Hour), NotAfter: f.clock.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature,
	}
	if ocspSigning {
		tmpl.ExtKeyUsage = []x509.ExtKeyUsage{x509.ExtKeyUsageOCSPSigning}
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, f.ca.cert, &key.PublicKey, f.ca.key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return &testCA{cert: cert, key: key}
}

// S8.3-AC2: the CRL is asked first; OCSP only when no CRL is obtainable (PREFER_CRLS).
func TestCRLFirstThenOCSP(t *testing.T) {
	t.Run("a good CRL decides; OCSP is not asked", func(t *testing.T) {
		f := newFixture(t)
		leaf := f.ca.issue(t, "Payer Both", crlBase+"/both.crl", ocspURL, f.clock.Now().Add(-time.Hour))
		f.crls.set("/both.crl", f.ca.crl(t, f.clock.Now().Add(-time.Hour), f.clock.Now().Add(time.Hour)))
		f.ocsp.set(leaf, ocsp.Revoked)
		if _, err := f.verify(t, leaf); err != nil {
			t.Fatal(err)
		}
		if f.ocsp.asked() != 0 {
			t.Fatal("OCSP was asked although the CRL answered")
		}
	})
	t.Run("a CRL listing the certificate refuses; OCSP is not asked", func(t *testing.T) {
		f := newFixture(t)
		leaf := f.ca.issue(t, "Payer Both", crlBase+"/both.crl", ocspURL, f.clock.Now().Add(-time.Hour))
		f.crls.set("/both.crl", f.ca.crl(t, f.clock.Now().Add(-time.Hour), f.clock.Now().Add(time.Hour), leaf.SerialNumber))
		_, err := f.verify(t, leaf)
		wantProblem(t, err, UntrustedChain)
		if f.ocsp.asked() != 0 {
			t.Fatal("OCSP was asked although the CRL answered")
		}
	})
	t.Run("no CRL obtainable: OCSP's revoked refuses", func(t *testing.T) {
		f := newFixture(t)
		leaf := f.ca.issue(t, "Payer Both", crlBase+"/missing.crl", ocspURL, f.clock.Now().Add(-time.Hour))
		f.ocsp.set(leaf, ocsp.Revoked)
		_, err := f.verify(t, leaf)
		wantProblem(t, err, UntrustedChain)
		if f.ocsp.asked() == 0 {
			t.Fatal("OCSP was not asked")
		}
	})
}

// S8.3-AC2: a CRL is cached until its NextUpdate (ADR-0027): fetched once while current, again
// once NextUpdate has passed. The golden CRL, current until 2046, is fetched once for many checks.
func TestCRLCachedUntilNextUpdate(t *testing.T) {
	f := newFixture(t)
	leaf := f.ca.issue(t, "Payer Cached", crlBase+"/short.crl", "", f.clock.Now().Add(-time.Hour))
	now := f.clock.Now()
	f.crls.set("/short.crl", f.ca.crl(t, now.Add(-time.Minute), now.Add(time.Hour)))

	for range 3 {
		if _, err := f.verify(t, leaf); err != nil {
			t.Fatal(err)
		}
	}
	if n := f.crls.count("/short.crl"); n != 1 {
		t.Fatalf("fetched %d times while current; want 1", n)
	}

	f.clock.Advance(2 * time.Hour)
	f.crls.set("/short.crl", f.ca.crl(t, f.clock.Now().Add(-time.Minute), f.clock.Now().Add(time.Hour), leaf.SerialNumber))
	_, err := f.verify(t, leaf)
	wantProblem(t, err, UntrustedChain)
	if n := f.crls.count("/short.crl"); n != 2 {
		t.Fatalf("fetched %d times after NextUpdate; want 2", n)
	}

	rsa := f.signer(t, "rsa")
	for range 3 {
		if _, err := f.verify(t, rsa...); err != nil {
			t.Fatal(err)
		}
	}
	if n := f.crls.count("/test-ca.crl"); n != 1 {
		t.Fatalf("the golden CRL was fetched %d times; want 1", n)
	}
}

// S8.3-AC2: a CRL whose signature does not verify against the issuer is no CRL: it is neither
// believed nor cached. The revoked golden signer, whose only source is that CRL, then soft-fails,
// as Java does (the JDK drops a CRL that fails verification and finds no other).
func TestCRLWithABadSignature(t *testing.T) {
	f := newFixture(t)
	tampered := readFile(t, filepath.Join(goldenDir, "pki/test-ca-revoked.crl"))
	tampered[len(tampered)-1] ^= 0xff
	f.crls.set("/test-ca-revoked.crl", tampered)

	for range 2 {
		if _, err := f.verify(t, f.signer(t, "revoked")...); err != nil {
			t.Fatalf("a CRL with a bad signature is no CRL, which soft-fails: %v", err)
		}
	}
	if n := f.crls.count("/test-ca-revoked.crl"); n != 2 {
		t.Fatalf("fetched %d times; a CRL that failed verification must not be cached", n)
	}
}

// S8.3-AC2: what else the JDK forgives of a CRL, for a certificate whose only source it is. Not
// found, too large and out of date are no CRL (soft-fail); a body that is not a CRL at all is a
// CRLException, which SOFT_FAIL does not forgive. Pinned by the JDK's URICertStore and
// DistributionPointFetcher; no Java test key.
func TestCRLAnswers(t *testing.T) {
	for _, tc := range []struct {
		name   string
		body   func(f *fixture, t *testing.T) []byte
		accept bool
	}{
		{"not found", nil, true},
		{"out of date", func(f *fixture, t *testing.T) []byte {
			return f.ca.crl(t, f.clock.Now().Add(-3*time.Hour), f.clock.Now().Add(-2*time.Hour))
		}, true},
		{"too large", func(_ *fixture, _ *testing.T) []byte { return make([]byte, 64<<10) }, true},
		{"not a CRL", func(_ *fixture, _ *testing.T) []byte { return []byte("<html>maintenance</html>") }, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newFixture(t)
			f.verifier = func() *Verifier {
				v, err := NewVerifier(Config{
					Truststore: NewStaticTruststore(f.anchor),
					Clock:      f.clock,
					Revocation: RevocationConfig{DialContext: f.net.dial, MaxCRLBytes: 32 << 10},
				})
				if err != nil {
					t.Fatal(err)
				}
				return v
			}()
			leaf := f.ca.issue(t, "Payer CRL", crlBase+"/answer.crl", "", f.clock.Now().Add(-time.Hour))
			if tc.body != nil {
				f.crls.set("/answer.crl", tc.body(f, t))
			}
			_, err := f.verify(t, leaf)
			if tc.accept && err != nil {
				t.Fatal(err)
			}
			if !tc.accept {
				wantProblem(t, err, UntrustedChain)
			}
			if f.crls.count("/answer.crl") == 0 {
				t.Fatal("the CRL was never fetched")
			}
		})
	}
}
