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
	"path/filepath"
	"testing"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

// fixture is a Verifier trusting the golden set's anchor, at a fixed instant, whose only network
// is the test network: the golden CRLs at the distribution point the test CA's leaves name, and
// an OCSP responder for the test CA at ocsp.test-ca.example.
type fixture struct {
	m        *manifest
	clock    *testClock
	net      *testNetwork
	crls     *crlServer
	ocsp     *ocspResponder
	anchor   *x509.Certificate
	ca       *testCA
	verifier *Verifier
}

// fixedNow is the instant the tests judge at. Not the golden epoch (2026-10-01): the JUnit
// keystores that name unreachable sources were issued after it. Every certificate of the test CA
// is valid then (until 2046).
var fixedNow = time.Date(2027, time.January, 1, 0, 0, 0, 0, time.UTC)

const (
	ocspURL = "http://ocsp.test-ca.example/ocsp"
	crlBase = "http://crl.test-ca.example"
)

func newFixture(t *testing.T) *fixture {
	t.Helper()
	m := loadManifest(t)
	f := &fixture{
		m:     m,
		clock: newTestClock(fixedNow),
		net:   newTestNetwork(),
		crls:  newCRLServer(),
		ca:    loadTestCA(t),
	}
	for _, c := range m.PKI.CRLs {
		u := c.URL[len("http://"+crlHostPort):]
		f.crls.set(u, readFile(t, filepath.Join(goldenDir, c.File)))
	}
	crlSrv := server(t, f.crls)
	f.net.route(crlHostPort, crlSrv)
	f.net.route("crl.test-ca.example:80", crlSrv)
	f.ocsp = &ocspResponder{ca: f.ca, clock: f.clock}
	f.net.route("ocsp.test-ca.example:80", server(t, f.ocsp))

	f.anchor = readChain(t, filepath.Join(goldenDir, m.PKI.TrustAnchors[0].File))[0]
	f.verifier = f.newVerifier(t, m.Verifier.IssuerNameMustContain, f.anchor)
	return f
}

func (f *fixture) newVerifier(t *testing.T, issuerName string, trusted ...*x509.Certificate) *Verifier {
	t.Helper()
	v, err := NewVerifier(Config{
		Truststore: NewStaticTruststore(trusted...),
		Clock:      f.clock,
		IssuerName: issuerName,
		Revocation: RevocationConfig{DialContext: f.net.dial},
	})
	if err != nil {
		t.Fatal(err)
	}
	return v
}

func (f *fixture) signer(t *testing.T, name string) []*x509.Certificate {
	t.Helper()
	s, ok := f.m.PKI.Signers[name]
	if !ok {
		t.Fatalf("no golden signer %q", name)
	}
	return readChain(t, filepath.Join(goldenDir, s.File))
}

// verify checks chain for its leaf, as a token carrying the leaf's x5t#S256 would.
func (f *fixture) verify(t *testing.T, chain ...*x509.Certificate) (*Signer, error) {
	t.Helper()
	if len(chain) == 0 || chain[0] == nil {
		t.Fatal("verify needs a leaf")
		return nil, nil
	}
	return f.verifier.Verify(t.Context(), chain, thumb(chain[0]))
}

func thumb(c *x509.Certificate) *string {
	s := Thumbprint(c)
	return &s
}

func wantProblem(t *testing.T, err error, want Problem) {
	t.Helper()
	if err == nil {
		t.Fatalf("accepted; want %s", want)
	}
	got, ok := ProblemOf(err)
	if !ok || got != want {
		t.Fatalf("got %v (%s); want %s", err, got, want)
	}
}

// S8.3-AC1, S8.3-AC4: the four golden signers get the verdicts the golden manifest gives them,
// and a trusted signer is known by its subject in RFC 2253 form, as Java prints it (ADR-0021).
// rsa: X9-SIG-050, X9-LOC-303. ec: X9-SIG-054. revoked (by CRL): X9-SIG-053, X9-LOC-305.
// untrusted: X9-SIG-052.
func TestGoldenSigners(t *testing.T) {
	f := newFixture(t)
	for name, s := range f.m.PKI.Signers {
		t.Run(name, func(t *testing.T) {
			chain := f.signer(t, name)
			if Thumbprint(chain[0]) != s.X5tS256 {
				t.Fatalf("thumbprint %s; the manifest says %s", Thumbprint(chain[0]), s.X5tS256)
			}
			before := f.net.totalDials()
			got, err := f.verify(t, chain...)
			if !s.IssuedByTheTrustAnchor || s.Revoked {
				wantProblem(t, err, UntrustedChain)
				if !s.IssuedByTheTrustAnchor && f.net.totalDials() != before {
					t.Fatal("an untrusted chain must be refused before any revocation source is asked")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if got.Subject != s.Subject {
				t.Fatalf("subject %q; want %q", got.Subject, s.Subject)
			}
			if !got.Anchor.Equal(f.anchor) || !got.Certificate.Equal(chain[0]) {
				t.Fatal("wrong anchor or signing certificate")
			}
		})
	}
	if f.crls.count("/test-ca-revoked.crl") == 0 {
		t.Fatal("the revoked signer must have been refused by its CRL, which was never fetched")
	}
}

// S8.3-AC1: the chain is the one sent, leaf first, ending at a trusted anchor. Java's
// CertPathValidator validates the path in the order it is given.
func TestChainShape(t *testing.T) {
	f := newFixture(t)
	chain := f.signer(t, "rsa")

	if _, err := f.verifier.Verify(t.Context(), nil, thumb(chain[0])); err == nil {
		t.Fatal("an empty chain was accepted")
	} else {
		wantProblem(t, err, UntrustedChain)
	}
	_, err := f.verifier.Verify(t.Context(), []*x509.Certificate{chain[1], chain[0]}, thumb(chain[0]))
	wantProblem(t, err, UntrustedChain)

	if _, err := f.verify(t, chain[0]); err != nil {
		t.Fatalf("the leaf alone chains to the trusted anchor: %v", err)
	}
	if _, err := f.verify(t, chain...); err != nil {
		t.Fatalf("the leaf with its anchor: %v", err)
	}
	other := f.ca.issue(t, "Unrelated", "", "", f.clock.Now().Add(-time.Hour))
	_, err = f.verifier.Verify(t.Context(), []*x509.Certificate{chain[0], other}, thumb(chain[0]))
	wantProblem(t, err, UntrustedChain)
}

// S8.3-AC1: the trust anchor's issuer must contain the configured name (x9.certificate.issuer-name,
// ignoring case; blank skips it) and be a trusted certificate's subject, as Java's
// validateRootCertificateIssuer requires. No Java test key pins this check.
func TestIssuerName(t *testing.T) {
	f := newFixture(t)
	chain := f.signer(t, "rsa")
	for _, tc := range []struct {
		name   string
		accept bool
	}{
		{"X9", true},
		{"x9 qrcode test root", true},
		{"", true},
		{"X9 Financial PKI", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			v := f.newVerifier(t, tc.name, f.anchor)
			_, err := v.Verify(t.Context(), chain, thumb(chain[0]))
			if tc.accept && err != nil {
				t.Fatal(err)
			}
			if !tc.accept {
				wantProblem(t, err, UntrustedChain)
			}
		})
	}
}

// S8.3-AC1: x5t#S256 picks the signing certificate out of the chain, after the chain is trusted.
// Missing is BAD_HEADER, matching nothing UNTRUSTED_CHAIN; picking the CA certificate, whose key
// usage lacks digitalSignature, is UNTRUSTED_CHAIN (Java's validateKeyUsage). The golden cases
// rsa-x5t-missing and rsa-x5t-wrong pin the first two through the whole verifier (Story 8.5).
func TestThumbprint(t *testing.T) {
	f := newFixture(t)
	chain := f.signer(t, "rsa")

	_, err := f.verifier.Verify(t.Context(), chain, nil)
	wantProblem(t, err, BadHeader)

	wrong := Thumbprint(f.signer(t, "ec")[0])
	_, err = f.verifier.Verify(t.Context(), chain, &wrong)
	wantProblem(t, err, UntrustedChain)

	_, err = f.verifier.Verify(t.Context(), chain, thumb(chain[1]))
	wantProblem(t, err, UntrustedChain)

	// Java validates the chain first: an untrusted chain is UNTRUSTED_CHAIN even without x5t#S256.
	_, err = f.verifier.Verify(t.Context(), f.signer(t, "untrusted"), nil)
	wantProblem(t, err, UntrustedChain)
}

// S8.3-AC1: a certificate is judged at the verifier's clock: the golden signers expire in 2046.
func TestValidity(t *testing.T) {
	f := newFixture(t)
	f.clock.Advance(21 * 365 * 24 * time.Hour)
	_, err := f.verify(t, f.signer(t, "rsa")...)
	wantProblem(t, err, UntrustedChain)
}

// S8.3-AC1: a self-signed end entity has no authority to consult, so revocation is skipped
// (Java's isSelfSigned; the shipped demo keystore is such a certificate). Even the distribution
// point it names is never asked. An untrusted self-signed certificate is still refused.
func TestSelfSignedSkipsRevocation(t *testing.T) {
	f := newFixture(t)
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	name := pkix.Name{CommonName: "X9 Demo Self-Signed", Organization: []string{"Example PSP"}, Country: []string{"US"}}
	der, err := x509.CreateCertificate(rand.Reader, &x509.Certificate{
		SerialNumber:          big.NewInt(42),
		Subject:               name,
		Issuer:                name,
		NotBefore:             f.clock.Now().Add(-time.Hour),
		NotAfter:              f.clock.Now().Add(time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
		CRLDistributionPoints: []string{"http://unreachable.example/demo.crl"},
	}, &x509.Certificate{Subject: name, PublicKey: &key.PublicKey}, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	self, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}

	v := f.newVerifier(t, DefaultIssuerName, self)
	got, err := v.Verify(t.Context(), []*x509.Certificate{self}, thumb(self))
	if err != nil {
		t.Fatal(err)
	}
	if got.Subject != "CN=X9 Demo Self-Signed,O=Example PSP,C=US" || f.net.totalDials() != 0 {
		t.Fatalf("subject %q, dials %d: revocation must be skipped", got.Subject, f.net.totalDials())
	}

	_, err = f.verifier.Verify(t.Context(), []*x509.Certificate{self}, thumb(self))
	wantProblem(t, err, UntrustedChain)
}
