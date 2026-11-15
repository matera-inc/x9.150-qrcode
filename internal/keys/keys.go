// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package keys reads the signing key and the truststore from the files an operator mounts, under
// the Java build's property and variable names, and re-reads them on a timer (ADR-0027 "Keys",
// ADR-0031).
//
// The signer is a crypto.Signer (RSA or EC) with its certificate chain. A signature takes one
// snapshot (Store.Current) and signs with it: a reload swaps the snapshot for the next caller and
// never touches one in use, so a signature in progress finishes with the key it started with, and
// its certificate headers name that key.
//
// Formats: PEM, read here in full; PKCS#12, behind the PKCS12 interface (this build has no decoder,
// pending a dependency decision); JKS, refused with the conversion to run (ADR-0031). Go never
// decrypts Jasypt: internal/config refuses an ENC(…) value before this package runs.
//
// With no location configured, the bundled DEMO identity is used, as the Java build does: the
// demo key converted from Java's x9-demo.jks by others/scripts/convert-demo-keystores.sh. It is
// NON-PRODUCTION (its private key is public) and a WARN says so at every load.
package keys

import (
	"bytes"
	"context"
	"crypto"
	"crypto/sha256"
	"crypto/x509"
	_ "embed" // the bundled demo identity
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/clock"
	"github.com/matera-inc/x9.150-qrcode/internal/config"
)

// DemoFingerprint is the SHA-256 of the bundled demo certificate's DER: the one the Java build ships
// in x9-demo.jks (CN=x9.matera.com, OU=Demo NonProduction). The test-clock guard depends on it.
const DemoFingerprint = "67e9eae59581e99c81d3744fe086042f7363e13c2d15e05113011d2d76e5ed1b"

var (
	//go:embed bundled/x9-demo.pem
	bundledKeystore []byte
	//go:embed bundled/x9-demo-truststore.pem
	bundledTruststore []byte
)

// Identity is a signing key with its certificate chain, leaf first. It never changes once built;
// a reload builds another.
type Identity struct {
	key         crypto.Signer
	chain       []*x509.Certificate
	fingerprint string
}

// Public is the leaf certificate's public key.
func (id *Identity) Public() crypto.PublicKey { return id.key.Public() }

// Sign signs digest with the private key (crypto.Signer).
func (id *Identity) Sign(rand io.Reader, digest []byte, opts crypto.SignerOpts) ([]byte, error) {
	return id.key.Sign(rand, digest, opts)
}

// Certificate is the leaf: the certificate of the signing key.
func (id *Identity) Certificate() *x509.Certificate { return id.chain[0] }

// Chain is the leaf followed by its issuers found in the truststore, as the Java build builds it.
func (id *Identity) Chain() []*x509.Certificate { return slices.Clone(id.chain) }

// Fingerprint is the SHA-256 of the leaf's DER, in lowercase hex.
func (id *Identity) Fingerprint() string { return id.fingerprint }

// Truststore is the set of trusted certificates. It never changes once built.
type Truststore struct {
	certs []*x509.Certificate
	pool  *x509.CertPool
}

// Certificates are the trusted certificates, in file order.
func (t *Truststore) Certificates() []*x509.Certificate { return slices.Clone(t.certs) }

// Pool is a copy of the trusted certificates as a pool, for x509.VerifyOptions.Roots.
func (t *Truststore) Pool() *x509.CertPool { return t.pool.Clone() }

// Keys is one consistent snapshot: the identity's chain was built from this truststore.
type Keys struct {
	Identity   *Identity
	Truststore *Truststore
}

// Store holds the current Keys and re-reads the files.
type Store struct {
	cfg    config.Certificate
	pkcs12 PKCS12
	logger *slog.Logger

	current atomic.Pointer[Keys]

	mu           sync.Mutex // serialises Reload
	loadedDigest [sha256.Size]byte
	lastFailure  string // the last failed reload's error, logged once until it changes
}

// Option configures a Store.
type Option func(*Store)

// WithPKCS12 sets the PKCS#12 decoder. Without it, a PKCS#12 file is refused with the conversion to
// PEM to run.
func WithPKCS12(d PKCS12) Option { return func(s *Store) { s.pkcs12 = d } }

// Load reads the keystore and the truststore once, and refuses to start on any problem.
func Load(cfg config.Certificate, logger *slog.Logger, opts ...Option) (*Store, error) {
	s := &Store{cfg: cfg, pkcs12: unsupportedPKCS12{}, logger: logger}
	for _, o := range opts {
		o(s)
	}
	ks, ts, digest, err := s.read()
	if err != nil {
		return nil, err
	}
	k, err := build(cfg, ks, ts, s.pkcs12)
	if err != nil {
		return nil, err
	}
	s.current.Store(k)
	s.loadedDigest = digest
	s.logLoaded(false, k)
	return s, nil
}

// Current is the snapshot to use for one signature or one verification. Take it once and use both
// its identity and its truststore: a reload never changes a snapshot already taken.
func (s *Store) Current() *Keys { return s.current.Load() }

// Reload re-reads both files. When their content is unchanged it does nothing. When it changed and
// reads well, the new snapshot replaces the current one; when it does not, the current one stays,
// and the error is logged, once until it changes (Java logs a failed reload and keeps going).
func (s *Store) Reload() (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	ks, ts, digest, err := s.read()
	if err == nil && digest == s.loadedDigest {
		return false, nil
	}
	if err == nil {
		k, buildErr := build(s.cfg, ks, ts, s.pkcs12)
		if buildErr == nil {
			s.current.Store(k)
			s.loadedDigest, s.lastFailure = digest, ""
			s.logLoaded(true, k)
			return true, nil
		}
		err = buildErr
	}
	if msg := err.Error(); msg != s.lastFailure {
		s.logger.Error("x9-qrcode keystore reload failed; the last good keys stay in use",
			"err", err, "fingerprint", s.Current().Identity.Fingerprint())
		s.lastFailure = msg
	}
	return false, err
}

// Run re-reads the files every interval, measured on the real clock, until ctx ends. It runs
// Reload in this goroutine, one at a time, and waits the full interval after each (Java's
// fixed delay).
func (s *Store) Run(ctx context.Context, interval time.Duration) {
	for clock.Sleep(ctx, interval) == nil {
		_, _ = s.Reload() // Reload logs its failure and keeps the last good keys
	}
}

func (s *Store) logLoaded(reloaded bool, k *Keys) {
	id := k.Identity
	attrs := []any{
		"privateKeystore", source(s.cfg.PrivateKeystore.Location),
		"alias", s.cfg.PrivateKeystore.Alias,
		"subject", id.Certificate().Subject.String(),
		"keyType", keyType(id.key),
		"fingerprint", id.Fingerprint(),
		"chainLength", len(id.chain),
		"truststore", source(s.cfg.Truststore.Location),
		"trustedCertificates", len(k.Truststore.certs),
	}
	if reloaded {
		s.logger.Info("x9-qrcode signing keys reloaded", attrs...)
	} else {
		s.logger.Info("x9-qrcode signing keys loaded", attrs...)
	}
	if id.Fingerprint() == DemoFingerprint {
		s.logger.Warn("x9-qrcode is signing with the bundled DEMO identity (NON-PRODUCTION): its private key is public; "+
			"mount your own keystore (X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION)", "fingerprint", id.Fingerprint())
	}
	if s.cfg.Truststore.Location == "" {
		s.logger.Warn("x9-qrcode trusts the bundled DEMO truststore (NON-PRODUCTION); " +
			"mount your own (X9_CERTIFICATE_TRUSTSTORE_LOCATION)")
	}
}

func source(location string) string {
	if location == "" {
		return "bundled demo (NON-PRODUCTION)"
	}
	return location
}

// read returns both files' bytes and a digest of the pair, so that a change to either is seen.
func (s *Store) read() (keystore, truststore []byte, digest [sha256.Size]byte, err error) {
	keystore, err = readLocation(s.cfg.PrivateKeystore.Location, bundledKeystore, privateKeystoreName)
	if err != nil {
		return nil, nil, digest, err
	}
	truststore, err = readLocation(s.cfg.Truststore.Location, bundledTruststore, truststoreName)
	if err != nil {
		return nil, nil, digest, err
	}
	h := sha256.New()
	for _, b := range [][]byte{keystore, truststore} {
		_ = binary.Write(h, binary.BigEndian, uint64(len(b))) // a hash.Hash never fails to write
		h.Write(b)
	}
	copy(digest[:], h.Sum(nil))
	return keystore, truststore, digest, nil
}

func readLocation(location string, bundled []byte, name string) ([]byte, error) {
	if location == "" {
		return bytes.Clone(bundled), nil
	}
	data, err := os.ReadFile(location) //nolint:gosec // G304: the path is the operator's own keystore location.
	if err != nil {
		return nil, fmt.Errorf("%s: %w", name, err)
	}
	return data, nil
}

// build reads both files and puts the identity and its chain together.
func build(cfg config.Certificate, ks, ts []byte, p PKCS12) (*Keys, error) {
	trusted, err := readTruststore(ts, cfg.Truststore, p)
	if err != nil {
		return nil, err
	}
	key, certs, err := readKeystore(ks, cfg.PrivateKeystore, p)
	if err != nil {
		return nil, err
	}
	leaf := slices.IndexFunc(certs, func(c *x509.Certificate) bool { return samePublicKey(c.PublicKey, key.Public()) })
	if leaf < 0 {
		return nil, fmt.Errorf("%s: holds no certificate for its private key", privateKeystoreName)
	}
	pool := x509.NewCertPool()
	for _, c := range trusted {
		pool.AddCert(c)
	}
	sum := sha256.Sum256(certs[leaf].Raw)
	return &Keys{
		Identity: &Identity{
			key:         key,
			chain:       chainFrom(certs[leaf], trusted),
			fingerprint: hex.EncodeToString(sum[:]),
		},
		Truststore: &Truststore{certs: trusted, pool: pool},
	}, nil
}

// maxChain bounds chainFrom on a truststore whose certificates issue each other in a loop.
const maxChain = 10

// chainFrom is the Java build's chain (DefaultPrivateKeyRetriever.getCertificateChain): the leaf,
// then the truststore certificate whose subject is the current one's issuer, until a self-signed
// certificate or one whose issuer the truststore lacks. The keystore's own intermediates are not
// used, so that both builds publish the same x5c.
func chainFrom(leaf *x509.Certificate, trusted []*x509.Certificate) []*x509.Certificate {
	bySubject := make(map[string]*x509.Certificate, len(trusted))
	for _, c := range trusted {
		bySubject[string(c.RawSubject)] = c // the last one wins, as in Java's map
	}
	chain := []*x509.Certificate{leaf}
	for cur := leaf; len(chain) < maxChain; {
		next, ok := bySubject[string(cur.RawIssuer)]
		if !ok || next.Equal(cur) {
			break
		}
		chain = append(chain, next)
		cur = next
	}
	return chain
}

type publicKeyEqualer interface {
	Equal(x crypto.PublicKey) bool
}

func samePublicKey(a, b crypto.PublicKey) bool {
	e, ok := a.(publicKeyEqualer)
	return ok && e.Equal(b)
}

// ErrUnsupportedFormat is returned for a file this build cannot read: JKS, JCEKS, or PKCS#12 while
// no decoder is configured.
var ErrUnsupportedFormat = errors.New("unsupported keystore format")
