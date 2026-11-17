// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package jws

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math/big"
	"slices"
	"strings"
	"time"

	"github.com/go-jose/go-jose/v4"
	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/clock"
	"github.com/matera-inc/x9.150-qrcode/internal/cpu"
	"github.com/matera-inc/x9.150-qrcode/internal/emv"
	"github.com/matera-inc/x9.150-qrcode/internal/pki"
)

// Problem is a signatureProblem code: why a JWS did not verify, from the closed list the OpenAPI
// names SignatureProblem (Java's SignatureProblemEnumDTO).
type Problem string

// The signatureProblem codes.
const (
	// AbsentBody: there is nothing to check.
	AbsentBody Problem = "ABSENT_BODY"
	// NotJWS: not a JOSE object at all, or one Java's parser cannot read.
	NotJWS Problem = "NOT_JWS"
	// AlgNone: an unsecured JOSE object.
	AlgNone Problem = "ALG_NONE"
	// Encrypted: a JWE, not a JWS.
	Encrypted Problem = "ENCRYPTED"
	// BadHeader: the protected header breaks the X9.150 profile.
	BadHeader Problem = "BAD_HEADER"
	// CertificateUnavailable: the jku or x5u certificate could not be fetched or found.
	CertificateUnavailable Problem = "CERTIFICATE_UNAVAILABLE"
	// UntrustedChain: the signer's certificate is not one to trust.
	UntrustedChain Problem = "UNTRUSTED_CHAIN"
	// BadSignature: the signature does not verify.
	BadSignature Problem = "BAD_SIGNATURE"
	// Expired: iat + ttl has passed.
	Expired Problem = "EXPIRED"
	// FutureIAT: iat is ahead of this verifier's clock by more than the skew.
	FutureIAT Problem = "FUTURE_IAT"
	// CorrelationIDMismatch: a valid signature under another correlationId than the one expected.
	CorrelationIDMismatch Problem = "CORRELATION_ID_MISMATCH"
	// ContentMismatch: a payload request whose QR content does not name the location asked for.
	ContentMismatch Problem = "CONTENT_MISMATCH"
	// StatusMissing: a payee's answer whose signed body has no integer statusCode.
	StatusMissing Problem = "STATUS_MISSING"
	// StatusMismatch: a payee's answer whose signed statusCode is not its HTTP status.
	StatusMismatch Problem = "STATUS_MISMATCH"
	// Unverifiable: none of the above; the detail says what happened.
	Unverifiable Problem = "UNVERIFIABLE"
)

// Refusal is a JWS that did not verify: its code, and words for a log. The words may describe
// this verifier's internals, so only the code goes back to an unauthenticated caller.
type Refusal struct {
	Problem Problem
	Detail  string
	err     error
}

func (r *Refusal) Error() string {
	if r.err != nil {
		return fmt.Sprintf("%s: %s: %v", r.Problem, r.Detail, r.err)
	}
	return string(r.Problem) + ": " + r.Detail
}

func (r *Refusal) Unwrap() error { return r.err }

func refusal(p Problem, err error, format string, args ...any) *Refusal {
	return &Refusal{Problem: p, Detail: fmt.Sprintf(format, args...), err: err}
}

// ProblemOf is the code of an error Verify returned: "" for nil, UNVERIFIABLE for an error that
// carries no code.
func ProblemOf(err error) Problem {
	if err == nil {
		return ""
	}
	if r, ok := errors.AsType[*Refusal](err); ok {
		return r.Problem
	}
	return Unverifiable
}

// DefaultClockSkew is how far ahead of this verifier's clock an iat may be
// (X9_JWS_CLOCK_SKEW_SECONDS, 60 by default, as in Java).
const DefaultClockSkew = 60 * time.Second

// VerifierConfig is how this service verifies.
type VerifierConfig struct {
	// Truststore holds the trust anchors. Required.
	Truststore pki.Truststore
	// IssuerName must appear, ignoring case, in the trust anchor's issuer
	// (x9.certificate.issuer-name; pki.DefaultIssuerName). Empty skips the check, as in Java.
	IssuerName string
	// ClockSkew is how far ahead an iat may be. Zero is DefaultClockSkew; a negative skew is none
	// (Java clamps a negative setting to zero).
	ClockSkew time.Duration
	// Revocation configures CRL and OCSP fetching; its zero value is Java's defaults.
	Revocation pki.RevocationConfig
	// Fetcher reads the certificates jku and x5u point at. Build it on clock.New(), the real
	// clock. Nil refuses every jku and x5u token with CERTIFICATE_UNAVAILABLE.
	Fetcher *pki.Fetcher
	// Logger receives the warnings the certificate checks log. Nil discards them.
	Logger *slog.Logger
	// CPU bounds how many verifications run at once (internal/cpu explains why). One slot is held
	// for a whole verification, from the compact form's split to the signature, and given back
	// while a certificate, a CRL or an OCSP answer is fetched. Nil is unbounded.
	CPU *cpu.Limiter
}

// Verifier verifies X9.150 messages (ADR-0027, "Verification is our own sequence"). It is safe
// for concurrent use.
type Verifier struct {
	business Clock
	chains   *pki.Verifier
	fetcher  *pki.Fetcher
	skew     time.Duration
	cpu      *cpu.Limiter
}

// NewVerifier returns a Verifier. business is the business clock, which judges iat and ttl and
// may be moved in a test deployment (ADR-0033). Certificates, CRLs and OCSP answers are judged on
// the real clock, never the business one (S8.6-AC3): NewVerifier gives internal/pki clock.New().
func NewVerifier(cfg VerifierConfig, business Clock) (*Verifier, error) {
	return newVerifier(cfg, business, clock.New())
}

// newVerifier lets a test judge certificates at a chosen instant, as the golden set does.
func newVerifier(cfg VerifierConfig, business, certificates Clock) (*Verifier, error) {
	if business == nil {
		return nil, errors.New("jws: the verifier needs the business clock")
	}
	chains, err := pki.NewVerifier(pki.Config{
		Truststore: cfg.Truststore,
		Clock:      certificates,
		IssuerName: cfg.IssuerName,
		Revocation: cfg.Revocation,
		Logger:     cfg.Logger,
	})
	if err != nil {
		return nil, fmt.Errorf("jws: %w", err)
	}
	skew := cfg.ClockSkew
	switch {
	case skew == 0:
		skew = DefaultClockSkew
	case skew < 0:
		skew = 0
	}
	return &Verifier{business: business, chains: chains, fetcher: cfg.Fetcher, skew: skew, cpu: cfg.CPU}, nil
}

// Expect is what a caller knows of the JWS it is about to verify.
type Expect struct {
	// CorrelationID, when not uuid.Nil, is the correlationId the JWS must carry: an answer to a
	// question this service asked. Another is CORRELATION_ID_MISMATCH.
	CorrelationID uuid.UUID
	// LocationID, when not empty, is the location a payload request was sent to: the payload's
	// qrCodeContent must name it, or the JWS is CONTENT_MISMATCH.
	LocationID string
}

// Header is a verified protected header.
type Header struct {
	// Algorithm is alg.
	Algorithm string
	// Type is typ, as sent; it is not checked.
	Type string
	// KeyID is kid; empty when absent.
	KeyID string
	// CorrelationID is the conversation's id.
	CorrelationID uuid.UUID
	// IssuedAtMillis is iat, in Unix milliseconds.
	IssuedAtMillis int64
	// TTLMillis is ttl, in milliseconds.
	TTLMillis int64
	// StatusCode is the statusCode of a response; zero when crit does not name it.
	StatusCode int
	// Critical is crit, as sent.
	Critical []string
	// X5TS256 is x5t#S256.
	X5TS256 string
	// JKU, X5U: the URL the certificate was fetched from, when it was.
	JKU, X5U string
	// Raw is the protected header as signed, decoded from base64url.
	Raw []byte
}

// IssuedAt is iat as an instant.
func (h Header) IssuedAt() time.Time { return time.UnixMilli(h.IssuedAtMillis).UTC() }

// ExpiresAt is iat + ttl.
func (h Header) ExpiresAt() time.Time {
	return h.IssuedAt().Add(time.Duration(h.TTLMillis) * time.Millisecond)
}

// Verified is a JWS that verified.
type Verified struct {
	Header Header
	// Payload is the body, decoded from base64url, byte for byte as signed.
	Payload []byte
	// Signer is the signer certificate's subject in RFC 2253 form: the identity a reservation
	// holder is known by (ADR-0021).
	Signer string
	// Certificate is the signer's certificate.
	Certificate *x509.Certificate
	// QRCodeContent is the decoded EMV string of a payload request, when Expect.LocationID was set.
	QRCodeContent string
}

// allowedAlgorithms are the only algs this verifier accepts (ADR-0027 step 1): RS*, PS*, ES*.
var allowedAlgorithms = []jose.SignatureAlgorithm{
	jose.RS256, jose.RS384, jose.RS512,
	jose.PS256, jose.PS384, jose.PS512,
	jose.ES256, jose.ES384, jose.ES512,
}

// Verify runs ADR-0027's six steps on a compact JWS and returns it verified, or a *Refusal with
// its signatureProblem code:
//
//  1. parse it with go-jose and an explicit allowed-algorithm list;
//  2. split the compact string by hand for the raw header and the signing input, and decode the
//     header with every number kept as written;
//  3. check crit, correlationId, iat, ttl and the time window on the business clock, and
//     statusCode when crit names it (typ is not checked); then, for a payload request, that its
//     QR content names the location;
//  4. resolve the certificate (x5c, or a fetch of jku or x5u) and
//  5. run the chain, revocation, issuer, x5t#S256, validity and key-usage checks (internal/pki,
//     on the real clock);
//  6. verify the signature with the standard library, each ES algorithm on its own curve.
//
// A certificate fetched for jku or x5u is remembered only once the token verified in full.
func (v *Verifier) Verify(ctx context.Context, token string, want Expect) (*Verified, error) {
	verified, doc, r := v.verify(ctx, token, want)
	if r != nil {
		return nil, r
	}
	if doc != nil && v.fetcher != nil {
		v.fetcher.Remember(doc, verified.Header.X5TS256)
	}
	return verified, nil
}

func (v *Verifier) verify(ctx context.Context, token string, want Expect) (*Verified, *pki.Document, *Refusal) {
	// One CPU slot for the whole verification. internal/pki gives it back around every fetch
	// (cpu.Outside) and takes it again after; a request that ends while it waits is UNVERIFIABLE.
	//
	// PERFORMANCE: keep the slot here, at the top, around the whole verification (split, decode,
	// x5c parsing, chain and signature). It fixes a measured regression (2026-11-17): with the app at
	// 2 vCPU and 500 users on the full payment cycle, Go fell to 779 req/s and a 91 ms median,
	// because write transactions waited for the CPU behind JWS work while holding a database
	// connection. A slot around the signature arithmetic alone did not help (781 req/s, 97 ms); a
	// slot around the whole verification and the whole signature did (812 req/s, 64 ms). Do not
	// narrow it, move it inward or remove it without the same A/B on the benchmark machine;
	// internal/cpu explains why.
	ctx, release, err := v.cpu.Hold(ctx)
	if err != nil {
		return nil, nil, refusal(Unverifiable, err, "the JWS was not checked")
	}
	defer release()

	token = strings.TrimSpace(token)

	// Steps 1 and 2. The shape first, with the codes Java's parser gives, then go-jose.
	c, r := splitCompact(token)
	if r != nil {
		return nil, nil, r
	}
	h, r := parseHeader(c.header, c.signature)
	if r != nil {
		return nil, nil, r
	}
	if len(c.signature) == 0 {
		// Owner, 2026-11-14 (ADR-0027): Java's answer to a token whose signature was removed
		// with its dot kept, kept for parity.
		return nil, nil, refusal(NotJWS, nil, "the body is not a JWS: the signature is empty")
	}
	alg := jose.SignatureAlgorithm(h.alg)
	if !allowed(alg) {
		return nil, nil, refusal(BadHeader, nil, "alg %q is not one of RS256, RS384, RS512, PS256, PS384, PS512, ES256, ES384, ES512", h.alg)
	}
	// An x5c that holds something other than a certificate stops go-jose's parse. Java finds it
	// only when it reads the certificate, after the header and time checks, so the refusal waits
	// for step 4.
	var badX5C *Refusal
	if _, err := jose.ParseSignedCompact(token, allowedAlgorithms); err != nil {
		if badX5C = unreadableX5C(h); badX5C == nil {
			return nil, nil, refusal(NotJWS, err, "the body is not a JWS")
		}
	}

	// Step 3.
	p, r := checkProfile(h, v.business.Now().UnixMilli(), v.skew.Milliseconds())
	if r != nil {
		return nil, nil, r
	}
	var content string
	if want.LocationID != "" {
		if content, r = qrCodeContent(c.payload, want.LocationID); r != nil {
			return nil, nil, r
		}
	}

	// Step 4.
	chain, jwk, doc, r := v.certificate(ctx, h, badX5C)
	if r != nil {
		return nil, nil, r
	}

	// Step 5.
	signer, err := v.chains.Verify(ctx, chain, h.x5tS256)
	if err != nil {
		return nil, nil, fromPKI(err)
	}
	if jwk != nil && jwk.Algorithm != h.alg {
		return nil, nil, refusal(BadHeader, nil, "the header's alg %s is not the algorithm of the signer's key (%q)", h.alg, jwk.Algorithm)
	}

	// Step 6.
	if r := verifySignature(alg, signer.Certificate.PublicKey, []byte(c.input), c.signature); r != nil {
		return nil, nil, r
	}

	if want.CorrelationID != uuid.Nil && want.CorrelationID != p.correlationID {
		return nil, nil, refusal(CorrelationIDMismatch, nil, "correlationId mismatch: expected %s, found %s", want.CorrelationID, p.correlationID)
	}

	header := Header{
		Algorithm:      h.alg,
		Type:           h.typ,
		KeyID:          h.kid,
		CorrelationID:  p.correlationID,
		IssuedAtMillis: p.iat,
		TTLMillis:      p.ttl,
		StatusCode:     p.statusCode,
		Critical:       h.crit,
		X5TS256:        *h.x5tS256, // pki refused a header without it
		JKU:            h.jku,
		X5U:            h.x5u,
		Raw:            c.header,
	}
	return &Verified{
		Header:        header,
		Payload:       c.payload,
		Signer:        signer.Subject,
		Certificate:   signer.Certificate,
		QRCodeContent: content,
	}, doc, nil
}

func allowed(alg jose.SignatureAlgorithm) bool { return slices.Contains(allowedAlgorithms, alg) }

// unreadableX5C is the refusal for an x5c that holds something other than a certificate, or nil
// when x5c is readable (or absent).
func unreadableX5C(h *protected) *Refusal {
	if !h.hasX5C {
		return nil
	}
	if _, err := parseX5C(h.x5c); err != nil {
		return refusal(BadHeader, err, "x5c holds something that is not a certificate")
	}
	return nil
}

func parseX5C(x5c []string) ([]*x509.Certificate, error) {
	chain := make([]*x509.Certificate, 0, len(x5c))
	for i, b64 := range x5c {
		der, err := base64.StdEncoding.DecodeString(b64)
		if err != nil {
			return nil, fmt.Errorf("x5c[%d]: %w", i, err)
		}
		cert, err := x509.ParseCertificate(der)
		if err != nil {
			return nil, fmt.Errorf("x5c[%d]: %w", i, err)
		}
		chain = append(chain, cert)
	}
	return chain, nil
}

// certificate is step 4: the signer's chain, from jku, else x5u, else x5c, as Java prefers them.
// For jku it also returns the key, whose alg must be the header's.
func (v *Verifier) certificate(ctx context.Context, h *protected, badX5C *Refusal) ([]*x509.Certificate, *jose.JSONWebKey, *pki.Document, *Refusal) {
	switch {
	case h.jku != "":
		doc, r := v.fetch(ctx, pki.JKU, h.jku, h.x5tS256)
		if r != nil {
			return nil, nil, nil, r
		}
		key, r := keyByID(doc.Body, h)
		if r != nil {
			return nil, nil, nil, r
		}
		return key.Certificates, key, doc, nil
	case h.x5u != "":
		doc, r := v.fetch(ctx, pki.X5U, h.x5u, h.x5tS256)
		if r != nil {
			return nil, nil, nil, r
		}
		chain, err := doc.Certificates()
		if err != nil {
			return nil, nil, nil, fromPKI(err)
		}
		return chain, nil, doc, nil
	case len(h.x5c) > 0:
		if badX5C != nil {
			return nil, nil, nil, badX5C
		}
		chain, err := parseX5C(h.x5c)
		if err != nil {
			return nil, nil, nil, refusal(BadHeader, err, "x5c holds something that is not a certificate")
		}
		return chain, nil, nil, nil
	default:
		return nil, nil, nil, refusal(BadHeader, nil, "the header has no x5c, x5u or jku: nothing to verify with")
	}
}

func (v *Verifier) fetch(ctx context.Context, kind pki.Kind, rawURL string, x5t *string) (*pki.Document, *Refusal) {
	if v.fetcher == nil {
		return nil, refusal(CertificateUnavailable, nil, "this verifier fetches no %s certificate", kind)
	}
	doc, err := v.fetcher.Fetch(ctx, kind, rawURL, x5t)
	if err != nil {
		return nil, fromPKI(err)
	}
	return doc, nil
}

// keyByID finds the key of a JWK set whose kid is the header's. Java finds none when the header
// has no kid. A set or key that cannot be read, a key whose x5c holds another key among them, is
// CERTIFICATE_UNAVAILABLE.
func keyByID(body []byte, h *protected) (*jose.JSONWebKey, *Refusal) {
	var set struct {
		Keys []json.RawMessage `json:"keys"`
	}
	if err := json.Unmarshal(body, &set); err != nil || set.Keys == nil {
		return nil, refusal(CertificateUnavailable, err, "the signer's JWK set could not be read")
	}
	if !h.hasKid {
		return nil, refusal(CertificateUnavailable, nil, "the header has no kid, so no key of the signer's JWK set is its")
	}
	for _, raw := range set.Keys {
		var id struct {
			KID *string `json:"kid"`
		}
		if json.Unmarshal(raw, &id) != nil || id.KID == nil || *id.KID != h.kid {
			continue
		}
		var key jose.JSONWebKey
		if err := key.UnmarshalJSON(raw); err != nil {
			return nil, refusal(CertificateUnavailable, err, "the signer's key %q could not be read", h.kid)
		}
		return &key, nil
	}
	return nil, refusal(CertificateUnavailable, nil, "the signer's JWK set has no key with kid %q", h.kid)
}

// fromPKI carries internal/pki's code over, and its reason in the detail: the outbound answer
// check reports the detail to the payer (signatureDetail), who must be able to tell an untrusted
// chain from a revoked certificate (Story 2.3). An inbound 401 never shows it.
func fromPKI(err error) *Refusal {
	p, ok := pki.ProblemOf(err)
	if !ok {
		return refusal(Unverifiable, err, "the certificate could not be checked")
	}
	return refusal(Problem(p), err, "the signer's certificate: %v", err)
}

// hashes are the digest of each allowed algorithm.
var hashes = map[jose.SignatureAlgorithm]crypto.Hash{
	jose.RS256: crypto.SHA256, jose.RS384: crypto.SHA384, jose.RS512: crypto.SHA512,
	jose.PS256: crypto.SHA256, jose.PS384: crypto.SHA384, jose.PS512: crypto.SHA512,
	jose.ES256: crypto.SHA256, jose.ES384: crypto.SHA384, jose.ES512: crypto.SHA512,
}

// verifySignature is step 6. A key that is neither RSA nor EC is UNTRUSTED_CHAIN, as Java refuses
// to make a JWK of it; an algorithm of the other family than the key is UNVERIFIABLE, which is
// what Java answers (owner, 2026-11-14, kept for parity); an ES algorithm over a key on another
// curve is BAD_SIGNATURE although the mathematics may verify (RFC 7518 §3.4).
func verifySignature(alg jose.SignatureAlgorithm, pub crypto.PublicKey, input, sig []byte) *Refusal {
	hash := hashes[alg]
	family := string(alg)[:2]
	switch key := pub.(type) {
	case *rsa.PublicKey:
		if family == "ES" {
			return refusal(Unverifiable, nil, "the header's alg %s needs an EC key; the certificate holds an RSA key", alg)
		}
		h := hash.New()
		h.Write(input)
		digest := h.Sum(nil)
		var err error
		if family == "PS" {
			err = rsa.VerifyPSS(key, hash, digest, sig, &rsa.PSSOptions{SaltLength: rsa.PSSSaltLengthEqualsHash, Hash: hash})
		} else {
			err = rsa.VerifyPKCS1v15(key, hash, digest, sig)
		}
		if err != nil {
			return refusal(BadSignature, err, "the signature does not verify")
		}
		return nil
	case *ecdsa.PublicKey:
		if family != "ES" {
			return refusal(Unverifiable, nil, "the header's alg %s needs an RSA key; the certificate holds an EC key", alg)
		}
		want, ok := curves[alg]
		if !ok || want != key.Curve {
			return refusal(BadSignature, nil, "%s signs on its own curve (RFC 7518 §3.4); the certificate's key is on %s",
				alg, key.Curve.Params().Name)
		}
		size := (key.Curve.Params().BitSize + 7) / 8
		if len(sig) != 2*size {
			return refusal(BadSignature, nil, "an %s signature is %d bytes (R || S); this one is %d", alg, 2*size, len(sig))
		}
		h := hash.New()
		h.Write(input)
		rr := new(big.Int).SetBytes(sig[:size])
		ss := new(big.Int).SetBytes(sig[size:])
		if !ecdsa.Verify(key, h.Sum(nil), rr, ss) {
			return refusal(BadSignature, nil, "the signature does not verify")
		}
		return nil
	default:
		return refusal(UntrustedChain, nil, "the certificate holds a %T, which this verifier cannot verify with", pub)
	}
}

// lenientBase64 decodes base64 as Java's Nimbus Base64 does: the standard or the URL-safe alphabet,
// with or without its padding (X9-LIFE-126 sends base64url without padding).
func lenientBase64(s string) ([]byte, error) {
	s = strings.NewReplacer("-", "+", "_", "/").Replace(strings.TrimRight(s, "="))
	return base64.RawStdEncoding.DecodeString(s)
}

// qrCodeContent is Java's validateLocationId: the payload of a payload request carries
// qrCodeContent, the QR Code's EMV string in base64, whose location URL contains the location id
// asked for (ignoring case). It returns the decoded string. A payload that is not a JSON object is
// UNVERIFIABLE, as in Java; everything else that fails is CONTENT_MISMATCH.
func qrCodeContent(payload []byte, locationID string) (string, *Refusal) {
	fields, err := decodeObject(payload)
	if err != nil {
		return "", refusal(Unverifiable, err, "the payload is not a JSON object")
	}
	value, ok, _ := stringField(fields, "qrCodeContent")
	if !ok {
		return "", refusal(ContentMismatch, nil, "the payload has no qrCodeContent string")
	}
	decoded, err := lenientBase64(value)
	if err != nil {
		return "", refusal(ContentMismatch, err, "the payload's qrCodeContent is not base64")
	}
	q, err := emv.Decode(string(decoded))
	if err != nil {
		return "", refusal(ContentMismatch, err, "the payload's qrCodeContent is malformed")
	}
	if !strings.Contains(strings.ToLower(q.URL), strings.ToLower(locationID)) {
		return "", refusal(ContentMismatch, nil, "the payload's location URL does not name %s", locationID)
	}
	return string(decoded), nil
}
