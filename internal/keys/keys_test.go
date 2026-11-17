// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package keys

import (
	"context"
	"crypto"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/pem"
	"errors"
	"io"
	"strings"
	"sync"
	"testing"
	"testing/synctest"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/config"
	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

const (
	testPKI   = "../../testdata/pki/"
	goldenPKI = "../../others/blackbox/golden/pki/"
)

func quiet() *logBuffer { return &logBuffer{} }

// S8.2-AC4: with nothing configured, the bundled demo identity loads, as in Java, with the
// certificate Java ships (the fingerprint the test-clock guard depends on) and a NON-PRODUCTION
// warning.
func TestBundledDemoIdentity(t *testing.T) {
	logs := quiet()
	s, err := Load(config.Certificate{}, newLogger(logs))
	if err != nil {
		t.Fatal(err)
	}
	k := s.Current()
	id := k.Identity
	if id.Fingerprint() != DemoFingerprint {
		t.Fatalf("fingerprint = %s, want Java's %s", id.Fingerprint(), DemoFingerprint)
	}
	if got := id.Certificate().Subject.String(); got != "CN=x9.matera.com,OU=Demo NonProduction,O=Matera Systems Inc,C=US" {
		t.Errorf("subject = %s", got)
	}
	if got := keyType(id); got != "RSA-3072" {
		t.Errorf("key = %s, want RSA-3072 as in x9-demo.jks", got)
	}
	if len(id.Chain()) != 1 {
		t.Errorf("a self-signed identity's chain is itself; got %d certificates", len(id.Chain()))
	}
	if trusted := k.Truststore.Certificates(); len(trusted) != 1 || !trusted[0].Equal(id.Certificate()) {
		t.Errorf("the demo truststore trusts the demo certificate alone; got %d certificates", len(trusted))
	}
	signAndVerify(t, id)
	if logs.count("NON-PRODUCTION") < 2 || !strings.Contains(logs.String(), `"level":"WARN"`) {
		t.Errorf("no NON-PRODUCTION warning for the demo identity and truststore:\n%s", logs)
	}
}

// S8.2-AC4: the bundled truststore trusts the bundled demo certificate and nothing else. (Until
// 3.0.0 the two files were also checked byte for byte against the Java build's demo files; the Java
// build is retired, and these are now the only copies.)
func TestBundledTruststoreHoldsTheDemoCertificate(t *testing.T) {
	s, err := Load(config.Certificate{}, newLogger(io.Discard))
	if err != nil {
		t.Fatal(err)
	}
	if trusted := s.Current().Truststore.Certificates(); len(trusted) != 1 || !trusted[0].Equal(s.Current().Identity.Certificate()) {
		t.Fatal("the bundled truststore does not hold the bundled demo certificate alone")
	}
}

// S8.2-AC1: a PEM keystore (RSA or EC, any key encoding, key and certificates in any order) and a
// PEM truststore load; the signer is a crypto.Signer with the chain Java would build.
func TestPEMKeystores(t *testing.T) {
	ca := newCA(t, "Example Test Root CA")
	rsaKey, ecKey := newRSAKey(t), newECKey(t)
	rsaLeaf, ecLeaf := ca.issue(t, "rsa.example.com", rsaKey), ca.issue(t, "ec.example.com", ecKey)
	pkcs1 := pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(rsaKey)})
	sec1DER, err := x509.MarshalECPrivateKey(ecKey)
	if err != nil {
		t.Fatal(err)
	}
	sec1 := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: sec1DER})
	ecParams := pem.EncodeToMemory(&pem.Block{Type: "EC PARAMETERS", Bytes: []byte{0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07}})
	bagAttributes := []byte("Bag Attributes\n    friendlyName: x9-test-payer\n")

	for _, tc := range []struct {
		name    string
		parts   [][]byte
		keyType string
	}{
		{"RSA PKCS#8, key first", [][]byte{pkcs8PEM(t, rsaKey), certPEM(rsaLeaf)}, "RSA-2048"},
		{"RSA PKCS#1, certificate first", [][]byte{certPEM(rsaLeaf), pkcs1}, "RSA-2048"},
		{"EC PKCS#8", [][]byte{pkcs8PEM(t, ecKey), certPEM(ecLeaf)}, "EC-P-256"},
		{"EC SEC 1 with its parameters", [][]byte{ecParams, sec1, certPEM(ecLeaf)}, "EC-P-256"},
		{"openssl pkcs12 -nodes output, chain included", [][]byte{bagAttributes, certPEM(ecLeaf), bagAttributes, certPEM(ca.cert), bagAttributes, pkcs8PEM(t, ecKey)}, "EC-P-256"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			cfg := config.Certificate{
				PrivateKeystore: config.PrivateKeystore{Location: writeFile(t, dir, "signer.pem", tc.parts...), Alias: "x9-private-keystore"},
				Truststore:      config.Truststore{Location: writeFile(t, dir, "trust.pem", certPEM(ca.cert))},
			}
			s, err := Load(cfg, newLogger(io.Discard))
			if err != nil {
				t.Fatal(err)
			}
			id := s.Current().Identity
			var _ crypto.Signer = id
			if got := keyType(id); got != tc.keyType {
				t.Errorf("key = %s, want %s", got, tc.keyType)
			}
			chain := id.Chain()
			if len(chain) != 2 || !chain[1].Equal(ca.cert) {
				t.Fatalf("chain = %d certificates, want the leaf and the CA", len(chain))
			}
			signAndVerify(t, id)
		})
	}
}

// S8.2-AC1: the chain is Java's: the leaf, then issuers from the truststore only. An intermediate
// that only the keystore carries is not published, as in Java.
func TestChainComesFromTheTruststore(t *testing.T) {
	root := newCA(t, "Example Root")
	interKey := newECKey(t)
	inter := testCA{key: interKey, cert: root.issueCA(t, "Example Intermediate", interKey)}
	leafKey := newECKey(t)
	leaf := inter.issue(t, "signer.example.com", leafKey)
	dir := t.TempDir()
	ks := writeFile(t, dir, "signer.pem", pkcs8PEM(t, leafKey), certPEM(leaf), certPEM(inter.cert))

	load := func(trusted ...*x509.Certificate) []*x509.Certificate {
		t.Helper()
		parts := make([][]byte, 0, len(trusted))
		for _, c := range trusted {
			parts = append(parts, certPEM(c))
		}
		cfg := config.Certificate{
			PrivateKeystore: config.PrivateKeystore{Location: ks},
			Truststore:      config.Truststore{Location: writeFile(t, t.TempDir(), "trust.pem", parts...)},
		}
		s, err := Load(cfg, newLogger(io.Discard))
		if err != nil {
			t.Fatal(err)
		}
		return s.Current().Identity.Chain()
	}
	if got := load(root.cert, inter.cert); len(got) != 3 || !got[1].Equal(inter.cert) || !got[2].Equal(root.cert) {
		t.Errorf("with both CAs trusted, chain = %d certificates, want leaf, intermediate, root", len(got))
	}
	if got := load(root.cert); len(got) != 1 {
		t.Errorf("with the intermediate only in the keystore, chain = %d certificates, want the leaf alone (Java)", len(got))
	}
}

func (ca testCA) issueCA(t *testing.T, name string, key crypto.Signer) *x509.Certificate {
	t.Helper()
	c := ca.issue(t, name, key)
	tmpl := *c
	tmpl.IsCA, tmpl.BasicConstraintsValid, tmpl.KeyUsage = true, true, x509.KeyUsageCertSign
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, ca.cert, key.Public(), ca.key)
	if err != nil {
		t.Fatal(err)
	}
	out, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return out
}

// S8.2-AC1: the golden set's test CA, as a PEM truststore, is read as Java reads it.
func TestGoldenTestCATruststore(t *testing.T) {
	cfg := config.Certificate{Truststore: config.Truststore{Location: goldenPKI + "x9-test-ca.pem"}}
	s, err := Load(cfg, newLogger(io.Discard))
	if err != nil {
		t.Fatal(err)
	}
	trusted := s.Current().Truststore.Certificates()
	if len(trusted) != 1 || trusted[0].Subject.CommonName != "X9 QRCode Test Root CA" {
		t.Fatalf("trusted = %v", trusted)
	}
	if s.Current().Truststore.Pool() == nil {
		t.Fatal("no pool")
	}
}

// S8.2-AC1, S13.2-AC2: a JKS or JCEKS file is refused at startup, naming the keytool conversion and
// the mapping document that shows it (ADR-0031, ADR-0038).
func TestJKSIsRefusedWithItsConversion(t *testing.T) {
	// A JKS keystore starts with 0xFEEDFEED (JCEKS with 0xCECECECE); the rest is never read.
	jks := writeFile(t, t.TempDir(), "signer.jks", []byte{0xFE, 0xED, 0xFE, 0xED, 0, 0, 0, 2})
	jceks := writeFile(t, t.TempDir(), "signer.jceks", []byte{0xCE, 0xCE, 0xCE, 0xCE, 0, 0, 0, 2})
	for _, tc := range []struct {
		name string
		cfg  config.Certificate
		want []string
	}{
		{"JKS keystore", config.Certificate{PrivateKeystore: config.PrivateKeystore{Location: jks}},
			[]string{"X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION", "JKS", config.MappingDoc, "keytool -importkeystore", "ADR-0031"}},
		{"JCEKS keystore", config.Certificate{PrivateKeystore: config.PrivateKeystore{Location: jceks}},
			[]string{"X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION", "JKS", config.MappingDoc}},
		{"the JKS test truststore", config.Certificate{Truststore: config.Truststore{Location: testPKI + "x9-test-truststore.jks"}},
			[]string{"X9_CERTIFICATE_TRUSTSTORE_LOCATION", "JKS", config.MappingDoc, "keytool -importkeystore"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Load(tc.cfg, newLogger(io.Discard))
			if !errors.Is(err, ErrUnsupportedFormat) {
				t.Fatalf("err = %v, want ErrUnsupportedFormat", err)
			}
			for _, w := range tc.want {
				if !strings.Contains(err.Error(), w) {
					t.Errorf("the refusal does not say %q: %v", w, err)
				}
			}
		})
	}
}

// fakePKCS12 stands in for the decoder that awaits approval, to prove the seam.
type fakePKCS12 struct {
	key                               crypto.PrivateKey
	certs                             []*x509.Certificate
	password, keyPassword, alias, got string
}

func (f *fakePKCS12) DecodeKeystore(_ []byte, password, keyPassword, alias string) (crypto.PrivateKey, []*x509.Certificate, error) {
	f.password, f.keyPassword, f.alias = password, keyPassword, alias
	return f.key, f.certs, nil
}

func (f *fakePKCS12) DecodeTruststore(_ []byte, password string) ([]*x509.Certificate, error) {
	f.got = password
	return f.certs[1:], nil
}

// S8.2-AC1: a PKCS#12 decoder plugs in behind the interface, and receives Java's passwords and
// enforced alias (the key password defaulting to the store's, as in Java).
func TestPKCS12DecoderSeam(t *testing.T) {
	ca := newCA(t, "Example Test Root CA")
	key := newECKey(t)
	fake := &fakePKCS12{key: key, certs: []*x509.Certificate{ca.issue(t, "p12.example.com", key), ca.cert}}
	cfg := config.Certificate{
		PrivateKeystore: config.PrivateKeystore{Location: testPKI + "x9-test-payer.p12", Password: "store-pw", EnforcedAlias: "x9-test-payer"},
		Truststore:      config.Truststore{Location: testPKI + "x9-test-ca.p12", Password: "trust-pw"},
	}
	s, err := Load(cfg, newLogger(io.Discard), WithPKCS12(fake))
	if err != nil {
		t.Fatal(err)
	}
	if fake.password != "store-pw" || fake.keyPassword != "store-pw" || fake.alias != "x9-test-payer" || fake.got != "trust-pw" {
		t.Errorf("decoder got %+v", fake)
	}
	if len(s.Current().Identity.Chain()) != 2 {
		t.Errorf("chain = %d", len(s.Current().Identity.Chain()))
	}
	signAndVerify(t, s.Current().Identity)
}

func TestBadKeystoresAreRefused(t *testing.T) {
	ca := newCA(t, "Example Test Root CA")
	key, other := newECKey(t), newECKey(t)
	leaf := ca.issue(t, "signer.example.com", key)
	_, edKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	encrypted := pem.EncodeToMemory(&pem.Block{Type: "ENCRYPTED PRIVATE KEY", Bytes: []byte{0x30, 0x00}})
	legacyEncrypted := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Headers: map[string]string{
		"Proc-Type": "4,ENCRYPTED", "DEK-Info": "AES-256-CBC,00000000000000000000000000000000",
	}, Bytes: []byte{0x00}})
	for _, tc := range []struct {
		name       string
		keystore   [][]byte
		truststore [][]byte
		want       string
	}{
		{"encrypted PKCS#8", [][]byte{encrypted, certPEM(leaf)}, nil, "encrypted PEM key"},
		{"encrypted legacy PEM", [][]byte{legacyEncrypted, certPEM(leaf)}, nil, "encrypted PEM key"},
		{"two keys", [][]byte{pkcs8PEM(t, key), pkcs8PEM(t, other), certPEM(leaf)}, nil, "holds 2 private keys"},
		{"no key", [][]byte{certPEM(leaf)}, nil, "holds no private key"},
		{"no certificate", [][]byte{pkcs8PEM(t, key)}, nil, "holds no certificate"},
		{"certificate of another key", [][]byte{pkcs8PEM(t, other), certPEM(leaf)}, nil, "no certificate for its private key"},
		{"Ed25519", [][]byte{pkcs8PEM(t, edKey), certPEM(leaf)}, nil, "must be RSA or EC"},
		{"unexpected block", [][]byte{pkcs8PEM(t, key), certPEM(leaf), pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: []byte{0}})}, nil, "PUBLIC KEY"},
		{"neither PEM nor PKCS#12", [][]byte{[]byte("hello")}, nil, "neither PEM nor PKCS#12"},
		{"truststore with a private key", [][]byte{pkcs8PEM(t, key), certPEM(leaf)}, [][]byte{certPEM(ca.cert), pkcs8PEM(t, other)}, "certificates only"},
		{"empty truststore", [][]byte{pkcs8PEM(t, key), certPEM(leaf)}, [][]byte{[]byte("-----BEGIN nothing\n")}, "X9_CERTIFICATE_TRUSTSTORE_LOCATION"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			ts := tc.truststore
			if ts == nil {
				ts = [][]byte{certPEM(ca.cert)}
			}
			cfg := config.Certificate{
				PrivateKeystore: config.PrivateKeystore{Location: writeFile(t, dir, "signer.pem", tc.keystore...)},
				Truststore:      config.Truststore{Location: writeFile(t, dir, "trust.pem", ts...)},
			}
			_, err := Load(cfg, newLogger(io.Discard))
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want it to contain %q", err, tc.want)
			}
		})
	}
}

func TestMissingFileIsRefused(t *testing.T) {
	cfg := config.Certificate{PrivateKeystore: config.PrivateKeystore{Location: t.TempDir() + "/absent.pem"}}
	_, err := Load(cfg, newLogger(io.Discard))
	if err == nil || !strings.Contains(err.Error(), "X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION") {
		t.Fatalf("err = %v", err)
	}
}

// identityFiles writes a keystore and a truststore for one fresh key, and returns their contents.
type identityFiles struct {
	keystore, truststore []byte
	leaf                 *x509.Certificate
}

func newIdentity(t *testing.T, ca testCA, name string) identityFiles {
	t.Helper()
	key := newECKey(t)
	leaf := ca.issue(t, name, key)
	return identityFiles{
		keystore:   append(pkcs8PEM(t, key), certPEM(leaf)...),
		truststore: certPEM(ca.cert),
		leaf:       leaf,
	}
}

func loadFiles(t *testing.T, logs *logBuffer, f identityFiles) (*Store, string) {
	t.Helper()
	dir := t.TempDir()
	cfg := config.Certificate{
		PrivateKeystore: config.PrivateKeystore{Location: writeFile(t, dir, "signer.pem", f.keystore)},
		Truststore:      config.Truststore{Location: writeFile(t, dir, "trust.pem", f.truststore)},
	}
	s, err := Load(cfg, newLogger(logs))
	if err != nil {
		t.Fatal(err)
	}
	return s, cfg.PrivateKeystore.Location
}

// S8.2-AC3: an unchanged file is not reloaded; a changed one replaces the keys; a snapshot taken
// before the reload still signs with its own key and names its own certificate.
func TestReloadSwapsTheKeysButNotASnapshotInUse(t *testing.T) {
	ca := newCA(t, "Example Test Root CA")
	first, second := newIdentity(t, ca, "first.example.com"), newIdentity(t, ca, "second.example.com")
	s, path := loadFiles(t, quiet(), first)

	if changed, err := s.Reload(); changed || err != nil {
		t.Fatalf("an unchanged file reloaded: changed=%v err=%v", changed, err)
	}
	inUse := s.Current() // a signature starts with this snapshot
	replaceFile(t, path, second.keystore)
	if changed, err := s.Reload(); !changed || err != nil {
		t.Fatalf("a changed file did not reload: changed=%v err=%v", changed, err)
	}
	if !s.Current().Identity.Certificate().Equal(second.leaf) {
		t.Fatal("the store does not serve the new key")
	}
	if !inUse.Identity.Certificate().Equal(first.leaf) {
		t.Fatal("the reload changed a snapshot already taken")
	}
	signAndVerify(t, inUse.Identity) // the signature in progress finishes with the old key
	signAndVerify(t, s.Current().Identity)
}

// S8.2-AC3: a reload that fails keeps the last good keys and logs the error, once until it
// changes; a later good file is picked up.
func TestFailedReloadKeepsTheLastGoodKeys(t *testing.T) {
	ca := newCA(t, "Example Test Root CA")
	first, second := newIdentity(t, ca, "first.example.com"), newIdentity(t, ca, "second.example.com")
	logs := quiet()
	s, path := loadFiles(t, logs, first)
	good := s.Current()

	replaceFile(t, path, []byte("-----BEGIN PRIVATE KEY-----\nnot base64\n"))
	for range 3 {
		if changed, err := s.Reload(); changed || err == nil {
			t.Fatalf("a broken file reloaded: changed=%v err=%v", changed, err)
		}
	}
	if s.Current() != good {
		t.Fatal("a failed reload replaced the last good keys")
	}
	if n := logs.count("keystore reload failed"); n != 1 {
		t.Fatalf("the failure was logged %d times, want once:\n%s", n, logs)
	}
	if !strings.Contains(logs.String(), `"level":"ERROR"`) {
		t.Fatalf("the failure was not an ERROR:\n%s", logs)
	}

	replaceFile(t, path, second.keystore)
	if changed, err := s.Reload(); !changed || err != nil {
		t.Fatalf("the repaired file did not reload: changed=%v err=%v", changed, err)
	}
	if !s.Current().Identity.Certificate().Equal(second.leaf) {
		t.Fatal("the store does not serve the repaired key")
	}
}

// S8.2-AC3: a changed truststore reloads too, and the new snapshot pairs it with the identity.
func TestTruststoreChangeReloads(t *testing.T) {
	ca, other := newCA(t, "Example Test Root CA"), newCA(t, "Example Other CA")
	first := newIdentity(t, ca, "first.example.com")
	s, path := loadFiles(t, quiet(), first)
	trustPath := strings.TrimSuffix(path, "signer.pem") + "trust.pem"
	replaceFile(t, trustPath, certPEM(ca.cert), certPEM(other.cert))
	if changed, err := s.Reload(); !changed || err != nil {
		t.Fatalf("changed=%v err=%v", changed, err)
	}
	if n := len(s.Current().Truststore.Certificates()); n != 2 {
		t.Fatalf("trusted = %d, want 2", n)
	}
}

// S10.2-AC1: the Store is the verifier's truststore, and answers from the current snapshot, so a
// reloaded truststore is used without a restart.
func TestStoreIsTheCurrentTruststore(t *testing.T) {
	ca, other := newCA(t, "Example Test Root CA"), newCA(t, "Example Other CA")
	first := newIdentity(t, ca, "first.example.com")
	s, path := loadFiles(t, quiet(), first)
	trusts := func(c *x509.Certificate) bool {
		_, err := c.Verify(x509.VerifyOptions{Roots: s.Roots()})
		return err == nil
	}
	if len(s.Certificates()) != 1 || !trusts(ca.cert) || trusts(other.cert) {
		t.Fatalf("before the reload: %d certificates", len(s.Certificates()))
	}
	replaceFile(t, strings.TrimSuffix(path, "signer.pem")+"trust.pem", certPEM(other.cert))
	if changed, err := s.Reload(); !changed || err != nil {
		t.Fatalf("changed=%v err=%v", changed, err)
	}
	if len(s.Certificates()) != 1 || !s.Certificates()[0].Equal(other.cert) || trusts(ca.cert) || !trusts(other.cert) {
		t.Fatal("the store still answers with the old truststore")
	}
}

// S8.2-AC3: Run re-reads on its timer (real time, here a synctest bubble's) every interval, and
// stops with its context, leaving no goroutine behind.
func TestRunReloadsOnItsTimer(t *testing.T) {
	ca := newCA(t, "Example Test Root CA")
	first, second := newIdentity(t, ca, "first.example.com"), newIdentity(t, ca, "second.example.com")
	s, path := loadFiles(t, quiet(), first)
	synctest.Test(t, func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan struct{})
		go func() {
			defer close(done)
			s.Run(ctx, 30*time.Second)
		}()
		synctest.Wait()
		replaceFile(t, path, second.keystore)

		time.Sleep(29 * time.Second)
		synctest.Wait()
		if !s.Current().Identity.Certificate().Equal(first.leaf) {
			t.Fatal("reloaded before the interval")
		}
		time.Sleep(time.Second)
		synctest.Wait()
		if !s.Current().Identity.Certificate().Equal(second.leaf) {
			t.Fatal("not reloaded after the interval")
		}
		cancel()
		<-done
	})
}

// S8.2-AC3: signatures run while the keys are reloaded over and over; every signature verifies
// against the certificate of the snapshot it was made with (run under -race).
func TestConcurrentSignaturesDuringReloads(t *testing.T) {
	ca := newCA(t, "Example Test Root CA")
	ids := []identityFiles{newIdentity(t, ca, "a.example.com"), newIdentity(t, ca, "b.example.com")}
	s, path := loadFiles(t, quiet(), ids[0])

	var wg sync.WaitGroup
	stop := make(chan struct{})
	for range 4 {
		wg.Go(func() {
			for {
				select {
				case <-stop:
					return
				default:
					if err := verifySignature(s.Current().Identity); err != nil {
						t.Error(err)
						return
					}
				}
			}
		})
	}
	for i := range 20 {
		replaceFile(t, path, ids[(i+1)%2].keystore)
		if _, err := s.Reload(); err != nil {
			t.Error(err)
		}
	}
	close(stop)
	wg.Wait()
}
