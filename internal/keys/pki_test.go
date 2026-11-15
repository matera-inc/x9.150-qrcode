// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package keys

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
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// The keys these tests use are generated in memory and written to t.TempDir(): none is committed.

type testCA struct {
	key  crypto.Signer
	cert *x509.Certificate
}

var serials atomic.Int64

func nextSerial() *big.Int { return big.NewInt(serials.Add(1)) }

// notBefore is a validity window far from any real date: these tests never judge validity.
var notBefore = time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)

func newECKey(t *testing.T) *ecdsa.PrivateKey {
	t.Helper()
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func newRSAKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	k, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func newCA(t *testing.T, name string) testCA {
	t.Helper()
	key := newECKey(t)
	tmpl := &x509.Certificate{
		SerialNumber:          nextSerial(),
		Subject:               pkix.Name{CommonName: name, Organization: []string{"Example Test"}},
		NotBefore:             notBefore,
		NotAfter:              notBefore.AddDate(20, 0, 0),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, key.Public(), key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return testCA{key: key, cert: cert}
}

func (ca testCA) issue(t *testing.T, name string, key crypto.Signer) *x509.Certificate {
	t.Helper()
	tmpl := &x509.Certificate{
		SerialNumber: nextSerial(),
		Subject:      pkix.Name{CommonName: name},
		NotBefore:    notBefore,
		NotAfter:     notBefore.AddDate(10, 0, 0),
		KeyUsage:     x509.KeyUsageDigitalSignature,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, ca.cert, key.Public(), ca.key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return cert
}

func certPEM(c *x509.Certificate) []byte {
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: c.Raw})
}

func pkcs8PEM(t *testing.T, k crypto.PrivateKey) []byte {
	t.Helper()
	der, err := x509.MarshalPKCS8PrivateKey(k)
	if err != nil {
		t.Fatal(err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})
}

func writeFile(t *testing.T, dir, name string, parts ...[]byte) string {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, bytes.Join(parts, nil), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

// replaceFile swaps the file's content atomically, as a Kubernetes secret update does.
func replaceFile(t *testing.T, path string, parts ...[]byte) {
	t.Helper()
	tmp := writeFile(t, filepath.Dir(path), filepath.Base(path)+".new", parts...)
	if err := os.Rename(tmp, path); err != nil {
		t.Fatal(err)
	}
}

// signAndVerify signs a digest with id and checks it against the leaf certificate's key.
func signAndVerify(t *testing.T, id *Identity) {
	t.Helper()
	if err := verifySignature(id); err != nil {
		t.Fatal(err)
	}
}

func verifySignature(id *Identity) error {
	digest := sha256.Sum256([]byte("X9.150 signing input"))
	switch pub := id.Certificate().PublicKey.(type) {
	case *rsa.PublicKey:
		opts := &rsa.PSSOptions{SaltLength: rsa.PSSSaltLengthEqualsHash, Hash: crypto.SHA256}
		sig, err := id.Sign(rand.Reader, digest[:], opts)
		if err != nil {
			return err
		}
		if err := rsa.VerifyPSS(pub, crypto.SHA256, digest[:], sig, opts); err != nil {
			return fmt.Errorf("the RSA signature does not verify against the leaf: %w", err)
		}
	case *ecdsa.PublicKey:
		sig, err := id.Sign(rand.Reader, digest[:], crypto.SHA256)
		if err != nil {
			return err
		}
		if !ecdsa.VerifyASN1(pub, digest[:], sig) {
			return errors.New("the EC signature does not verify against the leaf")
		}
	default:
		return fmt.Errorf("unexpected leaf key %T", pub)
	}
	return nil
}

// logBuffer is a log sink safe to read while the store writes to it.
type logBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *logBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *logBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

func (b *logBuffer) count(s string) int { return strings.Count(b.String(), s) }

func newLogger(w io.Writer) *slog.Logger {
	return slog.New(slog.NewJSONHandler(w, &slog.HandlerOptions{Level: slog.LevelDebug}))
}
