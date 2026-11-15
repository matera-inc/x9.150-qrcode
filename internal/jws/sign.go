// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package jws signs X9.150 messages in the profile the Java build signs them (ADR-0027
// "Signing"): a compact JWS whose protected header carries alg, kid, typ JOSE, the custom headers
// correlationId, iat and ttl (both in milliseconds, as integers), statusCode on a response, all of
// them named in crit, x5t#S256, and the certificate published as jku, x5u or x5c, as configured.
//
// The signature is go-jose v4's, with the key behind an opaque signer: the key is
// internal/keys' crypto.Signer, which never leaves that package. iat is the business clock
// (internal/clock), so a moved test clock signs at the moved time, as Java does.
//
// Verification is in verify.go (Story 8.5); the header names below are shared.
package jws

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/asn1"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"strings"
	"time"

	"github.com/go-jose/go-jose/v4"
	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/config"
)

// The header parameters X9.150 adds to a JWS, every one of them named in crit.
const (
	HeaderCorrelationID = "correlationId"
	HeaderIssuedAt      = "iat"
	HeaderTTL           = "ttl"
	HeaderStatusCode    = "statusCode"
)

// The registered header parameters this package writes besides alg, kid, typ and crit.
const (
	HeaderX5TS256 = "x5t#S256"
	HeaderX5C     = "x5c"
	HeaderX5U     = "x5u"
	HeaderJKU     = "jku"
)

const (
	// TypeJOSE is the typ of every JWS this service signs, as in the Java build (INTERPRETATION
	// I-12: "typ stays JOSE" until the owner reviews the profile values).
	TypeJOSE = "JOSE"
	// KeyID is the kid of this service's key, the Java build's X9_KEY_ID.
	KeyID = "x9-key"
	// DefaultAlgorithm is the alg when none is configured (x9.certificate.jwk-algorithm).
	DefaultAlgorithm = "PS512"
	// DefaultTTL is the ttl when none is configured (x9.public-endpoints.jws-ttl-seconds).
	DefaultTTL = 300 * time.Second

	// JWKSetPath is where the JWK set is published on the public host: jku points to it.
	JWKSetPath = "/pub/.well-known/jwks"
	// CertificatePath is where the PEM certificate is published on the public host: x5u points
	// to it.
	CertificatePath = "/pub/.well-known/certificate/" + KeyID + ".pem"
)

// Publication says how a signed JWS names its certificate: Java's x9.certificate.endpoint-type.
type Publication string

const (
	// PublishJWKSet writes jku, the URL of this service's JWK set (Java's JWK_SET, the default).
	PublishJWKSet Publication = "JWK_SET"
	// PublishPEM writes x5u, the URL of this service's PEM certificate (Java's PEM).
	PublishPEM Publication = "PEM"
	// PublishChain writes x5c, the certificate chain itself, leaf first (Java's NONE).
	PublishChain Publication = "NONE"
)

// Key is what signs: a crypto.Signer (RSA or EC) with its certificate chain, leaf first.
// internal/keys' *Identity is one.
type Key interface {
	crypto.Signer
	Chain() []*x509.Certificate
}

// Clock is the business clock; internal/clock's *Clock is one.
type Clock interface {
	Now() time.Time
}

// Config is how this service signs.
type Config struct {
	// Algorithm is the alg: RS256, RS384, RS512, PS256, PS384, PS512, ES256, ES384 or ES512.
	// Empty is DefaultAlgorithm.
	Algorithm string
	// Publication is how the certificate is named. Empty is PublishJWKSet.
	Publication Publication
	// Host is the public host that jku and x5u point to, as configured for the QR Codes'
	// location URLs. Without a scheme, https:// is prefixed, as the Java build does.
	Host string
	// TTL is the ttl of a message that does not set its own. Zero is DefaultTTL.
	TTL time.Duration
}

// ConfigFrom reads the signing settings of the service's configuration.
func ConfigFrom(c config.Config) Config {
	return Config{
		Algorithm:   c.Certificate.JWKAlgorithm,
		Publication: Publication(c.Certificate.EndpointType),
		Host:        c.PublicEndpoints.Host,
		TTL:         time.Duration(c.PublicEndpoints.JWSTTLSeconds) * time.Second,
	}
}

// Message is what to sign.
type Message struct {
	// Payload is the JSON body, signed byte for byte as given.
	Payload []byte
	// CorrelationID is the conversation's id: a question's own, or the one an answer echoes.
	CorrelationID uuid.UUID
	// TTL is how long the JWS is valid from iat. Zero is the configured ttl. It is written in
	// milliseconds, so it must be at least one.
	TTL time.Duration
	// StatusCode, when not zero, marks a response: the header carries it and crit names it.
	StatusCode int
}

// Errors a signature can fail with.
var (
	ErrConfig      = errors.New("jws: invalid signing configuration")
	ErrKey         = errors.New("jws: the key cannot sign with the configured algorithm")
	ErrMessage     = errors.New("jws: invalid message")
	errUnsupported = errors.New("unsupported")
)

// Signer signs X9.150 messages. It holds no key: each signature is given the key snapshot to use,
// so a key reload never changes a signature in progress.
type Signer struct {
	alg         jose.SignatureAlgorithm
	hash        crypto.Hash
	publication Publication
	url         string // jku or x5u; empty for x5c
	ttl         time.Duration
	clock       Clock
}

// algorithms are the algorithms this service signs with (ADR-0027), with their hash.
var algorithms = map[jose.SignatureAlgorithm]crypto.Hash{
	jose.RS256: crypto.SHA256, jose.RS384: crypto.SHA384, jose.RS512: crypto.SHA512,
	jose.PS256: crypto.SHA256, jose.PS384: crypto.SHA384, jose.PS512: crypto.SHA512,
	jose.ES256: crypto.SHA256, jose.ES384: crypto.SHA384, jose.ES512: crypto.SHA512,
}

// curves binds each ES algorithm to its one curve (RFC 7518 §3.4).
var curves = map[jose.SignatureAlgorithm]elliptic.Curve{
	jose.ES256: elliptic.P256(), jose.ES384: elliptic.P384(), jose.ES512: elliptic.P521(),
}

// New returns a Signer. It refuses an algorithm outside RS*, PS* and ES*, an unknown publication,
// a jku or x5u publication without a host, and a negative ttl.
func New(cfg Config, clock Clock) (*Signer, error) {
	if cfg.Algorithm == "" {
		cfg.Algorithm = DefaultAlgorithm
	}
	if cfg.Publication == "" {
		cfg.Publication = PublishJWKSet
	}
	if cfg.TTL == 0 {
		cfg.TTL = DefaultTTL
	}
	alg := jose.SignatureAlgorithm(cfg.Algorithm)
	hash, ok := algorithms[alg]
	if !ok {
		return nil, fmt.Errorf("%w: algorithm %q is not one of RS256, RS384, RS512, PS256, PS384, PS512, ES256, ES384, ES512",
			ErrConfig, cfg.Algorithm)
	}
	if cfg.TTL < time.Millisecond {
		return nil, fmt.Errorf("%w: ttl %s is less than a millisecond", ErrConfig, cfg.TTL)
	}
	if clock == nil {
		return nil, fmt.Errorf("%w: no clock", ErrConfig)
	}
	s := &Signer{alg: alg, hash: hash, publication: cfg.Publication, ttl: cfg.TTL, clock: clock}
	switch cfg.Publication {
	case PublishJWKSet:
		s.url = publicURL(cfg.Host, JWKSetPath)
	case PublishPEM:
		s.url = publicURL(cfg.Host, CertificatePath)
	case PublishChain:
	default:
		return nil, fmt.Errorf("%w: publication %q is not JWK_SET, PEM or NONE", ErrConfig, cfg.Publication)
	}
	if cfg.Publication != PublishChain && strings.TrimSpace(cfg.Host) == "" {
		return nil, fmt.Errorf("%w: publication %s needs the public host", ErrConfig, cfg.Publication)
	}
	return s, nil
}

// publicURL is the URL of path on the public host. A host without a scheme is https, as the Java
// build makes it (DefaultQRCodeLocationService).
func publicURL(host, path string) string {
	host = strings.TrimRight(strings.TrimSpace(host), "/")
	if !strings.Contains(host, "://") {
		host = "https://" + host
	}
	return host + path
}

// Algorithm is the alg this Signer writes.
func (s *Signer) Algorithm() string { return string(s.alg) }

// Check reports whether key can sign with the configured algorithm: an RSA key for RS* and PS*, an
// EC key on the algorithm's own curve for ES*, and a chain whose leaf holds the key's public key.
// Sign runs it before every signature.
func (s *Signer) Check(key Key) error { return checkKey(s.alg, key) }

// CheckKey is Check for an algorithm by name. The service runs it at start, so a contradiction
// between x9.certificate.jwk-algorithm and the key stops it there, naming both halves, as the
// Java build does.
func CheckKey(algorithm string, key Key) error {
	alg := jose.SignatureAlgorithm(algorithm)
	if _, ok := algorithms[alg]; !ok {
		return fmt.Errorf("%w: algorithm %q is not one of RS256, RS384, RS512, PS256, PS384, PS512, ES256, ES384, ES512",
			ErrConfig, algorithm)
	}
	return checkKey(alg, key)
}

func checkKey(alg jose.SignatureAlgorithm, key Key) error {
	if key == nil {
		return fmt.Errorf("%w: no key", ErrKey)
	}
	chain := key.Chain()
	if len(chain) == 0 || chain[0] == nil {
		return fmt.Errorf("%w: the key has no certificate", ErrKey)
	}
	pub := key.Public()
	switch k := pub.(type) {
	case *rsa.PublicKey:
		if _, ec := curves[alg]; ec {
			return fmt.Errorf("%w: %s needs an EC key, the key is RSA; use RS256, RS384, RS512, PS256, PS384 or PS512",
				ErrKey, alg)
		}
	case *ecdsa.PublicKey:
		want, ok := curves[alg]
		if !ok {
			return fmt.Errorf("%w: %s needs an RSA key, the key is EC (%s); use %s",
				ErrKey, alg, k.Curve.Params().Name, esFor(k.Curve))
		}
		if k.Curve != want {
			return fmt.Errorf("%w: %s signs on %s, the key is on %s; use %s",
				ErrKey, alg, want.Params().Name, k.Curve.Params().Name, esFor(k.Curve))
		}
	default:
		return fmt.Errorf("%w: the key is %T, not RSA or EC", ErrKey, pub)
	}
	if eq, ok := chain[0].PublicKey.(interface{ Equal(crypto.PublicKey) bool }); !ok || !eq.Equal(pub) {
		return fmt.Errorf("%w: the certificate %q does not hold the signing key", ErrKey, chain[0].Subject)
	}
	return nil
}

func esFor(c elliptic.Curve) string {
	for alg, curve := range curves {
		if curve == c {
			return string(alg)
		}
	}
	return "a P-256, P-384 or P-521 key"
}

// NewCorrelationID returns a fresh random correlationId, for a question this service asks or an
// answer to a question whose own could not be verified.
func NewCorrelationID() uuid.UUID { return uuid.New() }

// Sign signs m with key and returns the compact JWS.
func (s *Signer) Sign(key Key, m Message) (string, error) {
	if err := s.Check(key); err != nil {
		return "", err
	}
	if m.CorrelationID == uuid.Nil {
		return "", fmt.Errorf("%w: correlationId is required", ErrMessage)
	}
	if !json.Valid(m.Payload) {
		return "", fmt.Errorf("%w: the payload is not JSON", ErrMessage)
	}
	ttl := m.TTL
	if ttl == 0 {
		ttl = s.ttl
	}
	if ttl < time.Millisecond {
		return "", fmt.Errorf("%w: ttl %s is not a positive number of milliseconds", ErrMessage, ttl)
	}

	opts := (&jose.SignerOptions{}).WithType(TypeJOSE)
	opts.WithHeader(HeaderCorrelationID, m.CorrelationID.String())
	opts.WithHeader(HeaderIssuedAt, s.clock.Now().UnixMilli())
	opts.WithHeader(HeaderTTL, ttl.Milliseconds())
	if m.StatusCode != 0 {
		opts.WithHeader(HeaderStatusCode, m.StatusCode)
		opts.WithCritical(HeaderCorrelationID, HeaderIssuedAt, HeaderTTL, HeaderStatusCode)
	} else {
		opts.WithCritical(HeaderCorrelationID, HeaderIssuedAt, HeaderTTL)
	}
	chain := key.Chain()
	opts.WithHeader(HeaderX5TS256, Thumbprint(chain[0]))
	switch s.publication {
	case PublishJWKSet:
		opts.WithHeader(HeaderJKU, s.url)
	case PublishPEM:
		opts.WithHeader(HeaderX5U, s.url)
	case PublishChain:
		x5c := make([]string, len(chain))
		for i, c := range chain {
			x5c[i] = base64.StdEncoding.EncodeToString(c.Raw)
		}
		opts.WithHeader(HeaderX5C, x5c)
	}

	signer, err := jose.NewSigner(jose.SigningKey{Algorithm: s.alg, Key: opaque{key: key, alg: s.alg, hash: s.hash}}, opts)
	if err != nil {
		return "", fmt.Errorf("jws: building the signer: %w", err)
	}
	obj, err := signer.Sign(m.Payload)
	if err != nil {
		return "", fmt.Errorf("jws: signing: %w", err)
	}
	return obj.CompactSerialize()
}

// AnswerBody is the body of a signed answer to a payment notification: {"statusCode":n}
// (INTERPRETATION I-12, the A.4.5 note).
func AnswerBody(statusCode int) []byte {
	return fmt.Appendf(nil, `{"%s":%d}`, HeaderStatusCode, statusCode)
}

// SignAnswer signs the answer to a payment notification (Story 2.3): body {"statusCode":n}, the
// header carrying statusCode too and naming it in crit, and correlationId echoing the
// notification's (a fresh one when the notification could not be verified).
func (s *Signer) SignAnswer(key Key, correlationID uuid.UUID, statusCode int) (string, error) {
	if statusCode < 100 || statusCode > 599 {
		return "", fmt.Errorf("%w: status code %d is not an HTTP status", ErrMessage, statusCode)
	}
	return s.Sign(key, Message{Payload: AnswerBody(statusCode), CorrelationID: correlationID, StatusCode: statusCode})
}

// Thumbprint is x5t#S256: the base64url SHA-256 of the certificate's DER.
func Thumbprint(c *x509.Certificate) string {
	sum := sha256.Sum256(c.Raw)
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

// opaque lets go-jose sign with a crypto.Signer it does not know: go-jose writes the header and
// serialises, the key signs the digest. An ECDSA signature is turned from ASN.1 into JOSE's fixed
// R || S (RFC 7518 §3.4); a PSS salt is as long as the hash, as go-jose and the Java build use.
type opaque struct {
	key  Key
	alg  jose.SignatureAlgorithm
	hash crypto.Hash
}

func (o opaque) Public() *jose.JSONWebKey {
	return &jose.JSONWebKey{Key: o.key.Public(), KeyID: KeyID, Algorithm: string(o.alg), Use: "sig"}
}

func (o opaque) Algs() []jose.SignatureAlgorithm { return []jose.SignatureAlgorithm{o.alg} }

func (o opaque) SignPayload(payload []byte, alg jose.SignatureAlgorithm) ([]byte, error) {
	if alg != o.alg {
		return nil, fmt.Errorf("%w algorithm %s", errUnsupported, alg)
	}
	h := o.hash.New()
	h.Write(payload)
	digest := h.Sum(nil)
	switch string(alg)[:2] {
	case "RS":
		return o.key.Sign(rand.Reader, digest, o.hash)
	case "PS":
		return o.key.Sign(rand.Reader, digest, &rsa.PSSOptions{SaltLength: rsa.PSSSaltLengthEqualsHash, Hash: o.hash})
	case "ES":
		curve, ok := curves[alg]
		if !ok {
			return nil, fmt.Errorf("%w algorithm %s", errUnsupported, alg)
		}
		der, err := o.key.Sign(rand.Reader, digest, o.hash)
		if err != nil {
			return nil, err
		}
		return rawECDSA(der, curve)
	default:
		return nil, fmt.Errorf("%w algorithm %s", errUnsupported, alg)
	}
}

// rawECDSA turns an ASN.1 ECDSA signature into R || S, each as long as the curve's order.
func rawECDSA(der []byte, curve elliptic.Curve) ([]byte, error) {
	var sig struct{ R, S *big.Int }
	rest, err := asn1.Unmarshal(der, &sig)
	if err != nil {
		return nil, fmt.Errorf("jws: an ECDSA signature that is not ASN.1: %w", err)
	}
	size := (curve.Params().BitSize + 7) / 8
	if len(rest) != 0 || sig.R == nil || sig.S == nil || sig.R.Sign() <= 0 || sig.S.Sign() <= 0 ||
		sig.R.BitLen() > size*8 || sig.S.BitLen() > size*8 {
		return nil, errors.New("jws: a malformed ECDSA signature")
	}
	out := make([]byte, 2*size)
	sig.R.FillBytes(out[:size])
	sig.S.FillBytes(out[size:])
	return out, nil
}
