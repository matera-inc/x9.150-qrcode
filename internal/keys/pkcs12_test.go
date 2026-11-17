// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package keys

import (
	"crypto/x509"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	gopkcs12 "software.sslmate.com/src/go-pkcs12"

	"github.com/matera-inc/x9.150-qrcode/internal/config"
)

const javaTestPassword = "x9test123" // the NON-PRODUCTION test PKI's published password

// S8.2-AC1: Java's real PKCS#12 files, written by keytool (PBES2 with AES, an HMAC-SHA-256 MAC),
// load with the golden test CA as the truststore: RSA and EC leaves, with the fingerprints Java's
// test-clock guard pins and the chain Java builds.
func TestJavaPKCS12Keystores(t *testing.T) {
	for _, tc := range []struct {
		file, keyType, fingerprint string
	}{
		{"x9-test-payer.p12", "RSA-3072", "e08b164ebd84a0a03701d20071bfdd4d7cd8f3185958201c577e6015d51e671a"},
		{"x9-test-payer-ec.p12", "EC-P-256", "19241b109b737421705d65e7d6cfc2fca7bc9425f0f263fe04643daa70bcf518"},
		{"x9-test-payer-revoked.p12", "RSA-3072", "f449774d768f3324ac23b99ce858663073fe455041494754075151e3b437f875"},
	} {
		t.Run(tc.file, func(t *testing.T) {
			cfg := config.Certificate{
				PrivateKeystore: config.PrivateKeystore{Location: testPKI + tc.file, Password: javaTestPassword},
				Truststore:      config.Truststore{Location: goldenPKI + "x9-test-ca.pem"},
			}
			s, err := Load(cfg, newLogger(io.Discard))
			if err != nil {
				t.Fatal(err)
			}
			id := s.Current().Identity
			if id.Fingerprint() != tc.fingerprint {
				t.Errorf("fingerprint = %s, want %s", id.Fingerprint(), tc.fingerprint)
			}
			if got := keyType(id); got != tc.keyType {
				t.Errorf("key = %s, want %s", got, tc.keyType)
			}
			chain := id.Chain()
			if len(chain) != 2 || chain[1].Subject.CommonName != "X9 QRCode Test Root CA" {
				t.Errorf("chain = %d certificates, want the leaf and the test CA", len(chain))
			}
			signAndVerify(t, id)
		})
	}
}

// S8.2-AC1: Java's demo files (PKCS#12 under .jks names, as a 2.x deployment may still hold them)
// load as keystore and truststore; the content decides the format, not the name.
func TestJavaDemoFilesLoadByContent(t *testing.T) {
	dir := t.TempDir()
	for name, data := range map[string][]byte{"x9-demo.jks": bundledKeystore, "x9-demo-truststore.jks": bundledTruststore} {
		if err := os.WriteFile(filepath.Join(dir, name), data, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	cfg := config.Certificate{
		PrivateKeystore: config.PrivateKeystore{Location: filepath.Join(dir, "x9-demo.jks"), Password: demoPassword, Type: "JKS"},
		Truststore:      config.Truststore{Location: filepath.Join(dir, "x9-demo-truststore.jks"), Password: demoPassword},
	}
	s, err := Load(cfg, newLogger(io.Discard))
	if err != nil {
		t.Fatal(err)
	}
	if s.Current().Identity.Fingerprint() != DemoFingerprint {
		t.Fatalf("fingerprint = %s", s.Current().Identity.Fingerprint())
	}
}

// S8.2-AC1: every encryption the library writes is read back: today's (AES, SHA-256 MAC) and the
// legacy 3DES and RC2 ones older tools wrote, for keystores and for truststores.
func TestPKCS12Encryptions(t *testing.T) {
	ca := newCA(t, "Example Test Root CA")
	key := newECKey(t)
	leaf := ca.issue(t, "p12.example.com", key)
	for name, enc := range map[string]*gopkcs12.Encoder{
		"Modern2023 (PBES2, AES-256, HMAC-SHA-256)": gopkcs12.Modern2023,
		"Modern2026":                     gopkcs12.Modern2026,
		"LegacyDES (3DES, HMAC-SHA-1)":   gopkcs12.LegacyDES,
		"LegacyRC2 (RC2-40, HMAC-SHA-1)": gopkcs12.LegacyRC2,
	} {
		t.Run(name, func(t *testing.T) {
			ks, err := enc.Encode(key, leaf, []*x509.Certificate{ca.cert}, "s3cret")
			if err != nil {
				t.Fatal(err)
			}
			ts, err := enc.EncodeTrustStore([]*x509.Certificate{ca.cert}, "trust")
			if err != nil {
				t.Fatal(err)
			}
			dir := t.TempDir()
			cfg := config.Certificate{
				PrivateKeystore: config.PrivateKeystore{Location: writeFile(t, dir, "signer.p12", ks), Password: "s3cret"},
				Truststore:      config.Truststore{Location: writeFile(t, dir, "trust.p12", ts), Password: "trust"},
			}
			s, err := Load(cfg, newLogger(io.Discard))
			if err != nil {
				t.Fatal(err)
			}
			if chain := s.Current().Identity.Chain(); len(chain) != 2 || !chain[0].Equal(leaf) || !chain[1].Equal(ca.cert) {
				t.Fatalf("chain = %d certificates, want the leaf and the CA", len(chain))
			}
			signAndVerify(t, s.Current().Identity)
		})
	}
}

func TestBadPKCS12IsRefused(t *testing.T) {
	ca := newCA(t, "Example Test Root CA")
	key := newECKey(t)
	leaf := ca.issue(t, "p12.example.com", key)
	ks, err := gopkcs12.Modern2023.Encode(key, leaf, nil, "s3cret")
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	ksPath := writeFile(t, dir, "signer.p12", ks)
	trustPEM := writeFile(t, dir, "trust.pem", certPEM(ca.cert))
	for _, tc := range []struct {
		name string
		cfg  config.Certificate
		want string
	}{
		{"wrong password", config.Certificate{
			PrivateKeystore: config.PrivateKeystore{Location: ksPath, Password: "wrong"},
			Truststore:      config.Truststore{Location: trustPEM},
		}, "the password does not open it"},
		{"another private-key password", config.Certificate{
			PrivateKeystore: config.PrivateKeystore{Location: ksPath, Password: "s3cret", PrivateKeyPassword: "other"},
			Truststore:      config.Truststore{Location: trustPEM},
		}, "a PKCS#12 keystore has one password"},
		{"a keystore used as a truststore", config.Certificate{
			Truststore: config.Truststore{Location: ksPath, Password: "s3cret"},
		}, "X9_CERTIFICATE_TRUSTSTORE_LOCATION"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Load(tc.cfg, newLogger(io.Discard))
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("err = %v, want it to contain %q", err, tc.want)
			}
		})
	}
}
