// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package pki decides whether the certificate behind an X9.150 signature is trusted, with the
// answers the Java build gives (ADR-0027, "Revocation, parity with Java"; Story 8.3). It is the
// Go counterpart of JwsQRCodeSignatureService.validateExternalCertificate and the checks it
// calls, in the same order:
//
//  1. the chain, as given, leaf first, is built with x509.Certificate.Verify against the
//     truststore; while it is built the end entity's revocation is checked (revocation.go),
//     except for a self-signed end entity, which has no authority to ask;
//  2. the trust anchor's issuer must contain the configured issuer name (case-insensitive;
//     x9.certificate.issuer-name, "X9" by default) and be the subject of a trusted certificate;
//  3. the certificate the header's x5t#S256 names is picked from the chain;
//  4. that certificate must be within its validity period and, when it states a key usage,
//     allow digitalSignature.
//
// Every refusal is UNTRUSTED_CHAIN, except a missing x5t#S256, which is BAD_HEADER, as in Java.
// What it returns is the signer's subject in RFC 2253 form, the identity a reservation holder is
// known by (ADR-0021).
//
// The package also fetches a certificate a header points at with jku or x5u (fetch.go). It does
// not parse a JWS: internal/jws (Story 8.5) calls it.
//
// "Now" comes from the Clock it is given. Java judges certificates, CRLs and OCSP answers on the
// real clock, so main gives it a clock without the test offset (ADR-0033); tests give it a fixed
// instant.
package pki

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/asn1"
	"encoding/base64"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"
)

// Problem is the signatureProblem code a refusal maps to (the OpenAPI's SignatureProblem).
type Problem string

// The codes this package can give.
const (
	BadHeader              Problem = "BAD_HEADER"
	CertificateUnavailable Problem = "CERTIFICATE_UNAVAILABLE"
	UntrustedChain         Problem = "UNTRUSTED_CHAIN"
)

// Error is a refusal, with the signatureProblem code it maps to.
type Error struct {
	Problem Problem
	msg     string
	err     error
}

func (e *Error) Error() string {
	if e.err != nil {
		return e.msg + ": " + e.err.Error()
	}
	return e.msg
}

func (e *Error) Unwrap() error { return e.err }

// ProblemOf returns the signatureProblem code of an error this package returned, and false for any
// other error.
func ProblemOf(err error) (Problem, bool) {
	if e, ok := errors.AsType[*Error](err); ok {
		return e.Problem, true
	}
	return "", false
}

func refuse(problem Problem, err error, format string, args ...any) *Error {
	return &Error{Problem: problem, msg: fmt.Sprintf(format, args...), err: err}
}

// Clock is the source of "now"; *clock.Clock is one.
type Clock interface {
	Now() time.Time
}

// Truststore is what the chain is checked against. It is asked on every verification, so a
// truststore that reloads its files (Story 8.2) is used as soon as it has.
type Truststore interface {
	// Roots is the pool of trust anchors.
	Roots() *x509.CertPool
	// Certificates are the same anchors as a list, for the issuer check.
	Certificates() []*x509.Certificate
}

// StaticTruststore is a Truststore that never changes.
type StaticTruststore struct {
	pool  *x509.CertPool
	certs []*x509.Certificate
}

// NewStaticTruststore trusts exactly certs.
func NewStaticTruststore(certs ...*x509.Certificate) *StaticTruststore {
	pool := x509.NewCertPool()
	for _, c := range certs {
		pool.AddCert(c)
	}
	return &StaticTruststore{pool: pool, certs: append([]*x509.Certificate(nil), certs...)}
}

// Roots implements Truststore.
func (s *StaticTruststore) Roots() *x509.CertPool { return s.pool }

// Certificates implements Truststore.
func (s *StaticTruststore) Certificates() []*x509.Certificate {
	return append([]*x509.Certificate(nil), s.certs...)
}

// DefaultIssuerName is x9.certificate.issuer-name's default in the Java build.
const DefaultIssuerName = "X9"

// Config configures a Verifier.
type Config struct {
	// Truststore holds the trust anchors. Required.
	Truststore Truststore
	// Clock gives "now". Required.
	Clock Clock
	// IssuerName must appear, ignoring case, in the trust anchor's issuer. Empty skips the check,
	// as a blank x9.certificate.issuer-name does in Java.
	IssuerName string
	// Revocation configures CRL and OCSP fetching; its zero value is Java's defaults.
	Revocation RevocationConfig
	// Logger receives the warnings Java logs (a skipped or soft-failed revocation check). Nil
	// discards them.
	Logger *slog.Logger
}

// Verifier checks certificate chains. It is safe for concurrent use.
type Verifier struct {
	trust      Truststore
	clock      Clock
	issuerName string
	revocation *revocationChecker
	log        *slog.Logger
}

// NewVerifier returns a Verifier.
func NewVerifier(cfg Config) (*Verifier, error) {
	if cfg.Truststore == nil {
		return nil, errors.New("pki: a truststore is required")
	}
	if cfg.Clock == nil {
		return nil, errors.New("pki: a clock is required")
	}
	logger := cfg.Logger
	if logger == nil {
		logger = slog.New(slog.DiscardHandler)
	}
	return &Verifier{
		trust:      cfg.Truststore,
		clock:      cfg.Clock,
		issuerName: cfg.IssuerName,
		revocation: newRevocationChecker(cfg.Revocation, logger),
		log:        logger,
	}, nil
}

// Signer is a certificate the Verifier trusts.
type Signer struct {
	// Certificate is the one the header's x5t#S256 names: the key the signature is checked with.
	Certificate *x509.Certificate
	// Subject is Certificate's subject in RFC 2253 form ("CN=…,O=…,C=…"), as Java prints it.
	Subject string
	// Anchor is the trusted certificate the chain ends at.
	Anchor *x509.Certificate
}

// Verify runs Java's checks on chain (leaf first, as x5c, a jku key's x5c or an x5u document
// carries it) for the certificate x5tS256 names. x5tS256 is the header's value as it was sent;
// nil means the header has none.
func (v *Verifier) Verify(ctx context.Context, chain []*x509.Certificate, x5tS256 *string) (*Signer, error) {
	now := v.clock.Now()

	anchor, err := v.validateChain(ctx, chain, now)
	if err != nil {
		return nil, err
	}
	if err := v.checkIssuer(anchor); err != nil {
		return nil, err
	}
	cert, err := byThumbprint(chain, x5tS256)
	if err != nil {
		return nil, err
	}
	if now.Before(cert.NotBefore) {
		return nil, refuse(UntrustedChain, nil, "the certificate is not yet valid; valid from %s", cert.NotBefore.UTC().Format(time.RFC3339))
	}
	if now.After(cert.NotAfter) {
		return nil, refuse(UntrustedChain, nil, "the certificate has expired; expired at %s", cert.NotAfter.UTC().Format(time.RFC3339))
	}
	if hasExtension(cert, oidKeyUsage) && cert.KeyUsage&x509.KeyUsageDigitalSignature == 0 {
		return nil, refuse(UntrustedChain, nil, "the certificate does not allow digitalSignature, which a JWS needs")
	}
	return &Signer{Certificate: cert, Subject: cert.Subject.String(), Anchor: anchor}, nil
}

// validateChain is Java's PKIX validation of the path as given, with revocation of the end entity
// only, and returns the trust anchor.
func (v *Verifier) validateChain(ctx context.Context, chain []*x509.Certificate, now time.Time) (*x509.Certificate, error) {
	if len(chain) == 0 {
		return nil, refuse(UntrustedChain, nil, "the certificate chain is empty")
	}
	for i, c := range chain {
		if c == nil {
			return nil, refuse(UntrustedChain, nil, "certificate %d of the chain is missing", i)
		}
	}
	// Java validates the path in the order it was sent: each certificate is issued by the next.
	for i := 0; i+1 < len(chain); i++ {
		if !bytes.Equal(chain[i].RawIssuer, chain[i+1].RawSubject) {
			return nil, refuse(UntrustedChain, nil, "certificate %d of the chain is not issued by certificate %d", i, i+1)
		}
		if err := chain[i].CheckSignatureFrom(chain[i+1]); err != nil {
			return nil, refuse(UntrustedChain, err, "certificate %d of the chain is not signed by certificate %d", i, i+1)
		}
	}

	intermediates := x509.NewCertPool()
	for _, c := range chain[1:] {
		intermediates.AddCert(c)
	}
	chains, err := chain[0].Verify(x509.VerifyOptions{
		Roots:         v.trust.Roots(),
		Intermediates: intermediates,
		CurrentTime:   now,
		// Java's PKIX does not look at extended key usage; x509 asks for serverAuth unless told.
		KeyUsages: []x509.ExtKeyUsage{x509.ExtKeyUsageAny},
	})
	if err != nil {
		return nil, refuse(UntrustedChain, err, "the certificate chain does not validate")
	}
	verified := asSent(chains, chain)
	if verified == nil {
		return nil, refuse(UntrustedChain, nil, "the certificate chain, in the order sent, does not end at a trusted certificate")
	}

	if err := v.revocationOf(ctx, verified, now); err != nil {
		return nil, err
	}
	return verified[len(verified)-1], nil
}

// asSent picks the verified chain that is the chain as sent, followed by the anchor that issued
// its last certificate, or the chain as sent when its last certificate is a self-signed anchor.
// That is the path Java's CertPathValidator accepts; x509 may also find others.
func asSent(chains [][]*x509.Certificate, sent []*x509.Certificate) []*x509.Certificate {
	for _, c := range chains {
		if len(c) < len(sent) || len(c) > len(sent)+1 {
			continue
		}
		prefix := true
		for i := range sent {
			if !c[i].Equal(sent[i]) {
				prefix = false
				break
			}
		}
		if !prefix {
			continue
		}
		if len(c) == len(sent) && !selfSigned(c[len(c)-1]) {
			continue
		}
		return c
	}
	return nil
}

func (v *Verifier) revocationOf(ctx context.Context, verified []*x509.Certificate, now time.Time) error {
	leaf := verified[0]
	// Java: a self-signed end entity is its own issuer, so no CRL or OCSP authority exists to
	// consult; revocation is skipped. Decided by the certificate, never by configuration.
	if selfSigned(leaf) {
		v.log.WarnContext(ctx, "skipping the revocation check: the end-entity certificate is self-signed, so no CRL or OCSP authority exists to consult",
			slog.String("subject", leaf.Subject.String()))
		return nil
	}
	// ONLY_END_ENTITY: the JDK checks nothing that is a CA certificate, even at the head of the path.
	if leaf.BasicConstraintsValid && leaf.IsCA {
		return nil
	}
	if len(verified) < 2 {
		// Unreachable: a one-certificate path is a self-signed anchor (asSent).
		return refuse(UntrustedChain, nil, "the certificate's issuer is unknown")
	}
	if err := v.revocation.check(ctx, leaf, verified[1], now); err != nil {
		return refuse(UntrustedChain, err, "the certificate's revocation check failed")
	}
	return nil
}

// checkIssuer is Java's validateRootCertificateIssuer, on the trust anchor.
func (v *Verifier) checkIssuer(anchor *x509.Certificate) error {
	issuer := anchor.Issuer.String()
	if strings.TrimSpace(v.issuerName) != "" && !strings.Contains(strings.ToLower(issuer), strings.ToLower(v.issuerName)) {
		return refuse(UntrustedChain, nil, "the certificate's issuer must contain %q; it is %q", v.issuerName, issuer)
	}
	for _, trusted := range v.trust.Certificates() {
		if bytes.Equal(trusted.RawSubject, anchor.RawIssuer) {
			return nil
		}
	}
	return refuse(UntrustedChain, nil, "the certificate's issuer is not trusted: %q", issuer)
}

// Thumbprint is a certificate's x5t#S256: the unpadded base64url SHA-256 of its DER.
func Thumbprint(cert *x509.Certificate) string {
	sum := sha256.Sum256(cert.Raw)
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

// byThumbprint is Java's validateAndExtractCertificateByThumbprint: the first certificate of the
// chain whose thumbprint is exactly the header's string.
func byThumbprint(chain []*x509.Certificate, x5tS256 *string) (*x509.Certificate, error) {
	if x5tS256 == nil {
		return nil, refuse(BadHeader, nil, "the header has no x5t#S256 (certificate thumbprint)")
	}
	for _, c := range chain {
		if Thumbprint(c) == *x5tS256 {
			return c, nil
		}
	}
	return nil, refuse(UntrustedChain, nil, "no certificate of the chain matches the thumbprint %s", *x5tS256)
}

// selfSigned is Java's isSelfSigned: the certificate's signature verifies with its own key and its
// subject is its issuer.
func selfSigned(c *x509.Certificate) bool {
	if !bytes.Equal(c.RawSubject, c.RawIssuer) {
		return false
	}
	return c.CheckSignature(c.SignatureAlgorithm, c.RawTBSCertificate, c.Signature) == nil
}

var (
	oidKeyUsage              = asn1.ObjectIdentifier{2, 5, 29, 15}
	oidCRLDistributionPoints = asn1.ObjectIdentifier{2, 5, 29, 31}
)

func hasExtension(c *x509.Certificate, oid asn1.ObjectIdentifier) bool {
	for _, e := range c.Extensions {
		if e.Id.Equal(oid) {
			return true
		}
	}
	return false
}

// noRedirects is the CheckRedirect of every client that must not follow one: the 3xx answer is
// returned as it is, and refused as any other non-200.
func noRedirects(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
