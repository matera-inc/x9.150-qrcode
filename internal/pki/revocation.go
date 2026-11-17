// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package pki

import (
	"bytes"
	"context"
	"crypto/sha1" //nolint:gosec // OCSP names a responder by the SHA-1 of its key (RFC 6960 §4.2.1); not a security use
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/asn1"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ocsp"

	"github.com/matera-inc/x9.150-qrcode/internal/cpu"
)

// This file is the revocation half of Java's PKIX validation: a PKIXRevocationChecker with
// PREFER_CRLS, ONLY_END_ENTITY and SOFT_FAIL, plus NO_FALLBACK when the end entity names a CRL
// distribution point and no OCSP responder (BUG-003). What the JDK's RevocationChecker does with
// those options, this does:
//
//   - The CRL is tried first. Each answer is one of: no objection, REVOKED, UNDETERMINED (no
//     usable CRL: none named, unreachable, an HTTP error, too large, not a CRL at all, a bad
//     signature, out of date) or FAILED (an unknown critical extension on the entry that lists
//     the certificate).
//   - REVOKED refuses at once. Otherwise, with NO_FALLBACK, UNDETERMINED passes (soft-fail) and
//     FAILED refuses. Without it, OCSP is asked:
//   - OCSP's answers: good, REVOKED, UNDETERMINED (unreachable, an HTTP error, an answer that
//     cannot be parsed, tryLater, internalError, status unknown) or FAILED (no responder named,
//     any other error status, a bad or unauthorised signature, no answer for the certificate,
//     an out-of-date answer).
//   - The check passes when OCSP says good, or when both said UNDETERMINED; REVOKED refuses;
//     anything else refuses with the CRL's error.
//
// That gives ADR-0027's table: a source that is named but cannot be reached soft-fails, a revoked
// certificate is refused, and a certificate naming neither source is refused, because "no
// responder named" is FAILED, which SOFT_FAIL does not forgive.
//
// Where this differs from the JDK, on purpose: a body that is not a CRL soft-fails like an
// unreachable CRL ("a named source that can't be read soft-fails", owner, 2026-11-15; the JDK
// refuses it), a CRL is cached until its NextUpdate rather than
// for 30 seconds (ADR-0027), OCSP is always a POST (the JDK uses GET for short requests; RFC 6960
// requires responders to accept both), and an indirect CRL (one issued by another CA) is never
// used.

// RevocationConfig configures CRL and OCSP fetching. The zero value is the JDK's defaults.
type RevocationConfig struct {
	// ConnectTimeout bounds opening a connection (com.sun.security.crl.timeout and
	// com.sun.security.ocsp.timeout: 15 s).
	ConnectTimeout time.Duration
	// ReadTimeout bounds the answer once connected (15 s, as the JDK's read timeouts).
	ReadTimeout time.Duration
	// MaxCRLBytes is the largest CRL read; a larger one is no CRL (com.sun.security.crl.maxSize:
	// 20 MiB).
	MaxCRLBytes int64
	// MaxOCSPBytes is the largest OCSP answer read; a larger one is unreachable (1 MiB).
	MaxOCSPBytes int64
	// CRLCacheEntries bounds how many CRLs are cached (256).
	CRLCacheEntries int
	// DialContext replaces the dialer, for tests. Nil dials the network.
	DialContext func(ctx context.Context, network, addr string) (net.Conn, error)
	// TLSConfig is used for https distribution points and responders. Nil is the system roots.
	TLSConfig *tls.Config
}

const (
	defaultRevocationConnectTimeout = 15 * time.Second
	defaultRevocationReadTimeout    = 15 * time.Second
	defaultMaxCRLBytes              = 20 << 20
	defaultMaxOCSPBytes             = 1 << 20
	defaultCRLCacheEntries          = 256
	// maxClockSkew is the JDK's tolerance on CRL and OCSP validity intervals (15 minutes).
	maxClockSkew = 15 * time.Minute
	// maxRevocationRedirects is HttpURLConnection's default, which the JDK fetches with.
	maxRevocationRedirects = 20
)

type outcome int

const (
	// undetermined: the source gave no usable answer. SOFT_FAIL forgives it.
	undetermined outcome = iota + 1
	// revoked: the source lists the certificate.
	revoked
	// failed: any other failure. SOFT_FAIL does not forgive it.
	failed
)

type revocationError struct {
	outcome outcome
	msg     string
	err     error
}

func (e *revocationError) Error() string {
	if e.err != nil {
		return e.msg + ": " + e.err.Error()
	}
	return e.msg
}

func (e *revocationError) Unwrap() error { return e.err }

func revocationErr(o outcome, err error, format string, args ...any) *revocationError {
	return &revocationError{outcome: o, msg: fmt.Sprintf(format, args...), err: err}
}

type revocationChecker struct {
	client         *http.Client
	connectTimeout time.Duration
	readTimeout    time.Duration
	maxCRL         int64
	maxOCSP        int64
	log            *slog.Logger

	mu        sync.Mutex
	crls      map[string]cachedCRL
	cacheSize int
}

type cachedCRL struct {
	list    *crl
	expires time.Time
}

func newRevocationChecker(cfg RevocationConfig, logger *slog.Logger) *revocationChecker {
	c := &revocationChecker{
		connectTimeout: orDuration(cfg.ConnectTimeout, defaultRevocationConnectTimeout),
		readTimeout:    orDuration(cfg.ReadTimeout, defaultRevocationReadTimeout),
		maxCRL:         orInt64(cfg.MaxCRLBytes, defaultMaxCRLBytes),
		maxOCSP:        orInt64(cfg.MaxOCSPBytes, defaultMaxOCSPBytes),
		cacheSize:      int(orInt64(int64(cfg.CRLCacheEntries), defaultCRLCacheEntries)),
		log:            logger,
		crls:           map[string]cachedCRL{},
	}
	c.client = &http.Client{
		Transport: newTransport(c.connectTimeout, c.readTimeout, cfg.DialContext, cfg.TLSConfig),
		// The JDK follows a redirect to the same scheme, as HttpURLConnection does by default. A
		// CRL and an OCSP answer are signed, so where they came from does not matter.
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= maxRevocationRedirects {
				return errors.New("too many redirects")
			}
			if req.URL.Scheme != via[len(via)-1].URL.Scheme {
				return http.ErrUseLastResponse
			}
			return nil
		},
	}
	return c
}

// check is RevocationChecker.check for the end entity leaf issued by issuer.
func (c *revocationChecker) check(ctx context.Context, leaf, issuer *x509.Certificate, now time.Time) error {
	// NO_FALLBACK when the certificate names a CRL and no responder (BUG-003): the CRL is its only
	// source, so its own soft failure stands.
	fallback := !hasExtension(leaf, oidCRLDistributionPoints) || len(leaf.OCSPServer) > 0

	crlErr := c.checkCRLs(ctx, leaf, issuer, now)
	if crlErr == nil {
		return nil
	}
	if crlErr.outcome == revoked {
		return crlErr
	}
	crlSoft := crlErr.outcome == undetermined
	if !fallback {
		if crlSoft {
			c.softFail(ctx, leaf, crlErr)
			return nil
		}
		return crlErr
	}

	ocspErr := c.checkOCSP(ctx, leaf, issuer, now)
	switch {
	case ocspErr == nil:
		return nil
	case ocspErr.outcome == revoked:
		return ocspErr
	case ocspErr.outcome != undetermined:
		return errors.Join(crlErr, ocspErr)
	case !crlSoft:
		return crlErr
	default:
		// Only both soft failures pass.
		c.softFail(ctx, leaf, errors.Join(crlErr, ocspErr))
		return nil
	}
}

func (c *revocationChecker) softFail(ctx context.Context, leaf *x509.Certificate, err error) {
	c.log.WarnContext(ctx, "certificate revocation status could not be determined; accepted (soft-fail)",
		slog.String("subject", leaf.Subject.String()), slog.String("reason", err.Error()))
}

// checkCRLs is the CRL half: the CRLs at the certificate's distribution points, verified against
// its issuer.
func (c *revocationChecker) checkCRLs(ctx context.Context, leaf, issuer *x509.Certificate, now time.Time) *revocationError {
	var (
		approved *crl
		firstErr *revocationError
	)
	for _, uri := range leaf.CRLDistributionPoints {
		crl, err := c.crl(ctx, uri, leaf, issuer, now)
		if err != nil {
			if firstErr == nil {
				firstErr = err
			}
			continue
		}
		if crl != nil {
			approved = crl
			break
		}
	}
	if approved == nil {
		// Unreachable, unreadable or unusable: no CRL, which SOFT_FAIL forgives.
		if firstErr != nil {
			return firstErr
		}
		return revocationErr(undetermined, nil, "no usable CRL at the certificate's distribution points")
	}

	for _, entry := range approved.entries {
		if entry.SerialNumber == nil || entry.SerialNumber.Cmp(leaf.SerialNumber) != 0 {
			continue
		}
		for _, ext := range entry.Extensions {
			if ext.Critical && !ext.Id.Equal(oidCRLReason) && !ext.Id.Equal(oidCertificateIssuer) {
				return revocationErr(failed, nil, "unrecognised critical extension %s in the CRL entry", ext.Id)
			}
		}
		if entry.RevocationTime.Before(now) {
			return revocationErr(revoked, nil, "the certificate (serial %X) is revoked on the CRL, since %s",
				leaf.SerialNumber, entry.RevocationTime.UTC().Format(time.RFC3339))
		}
	}
	return nil
}

var (
	oidCRLReason          = asn1.ObjectIdentifier{2, 5, 29, 21}
	oidCertificateIssuer  = asn1.ObjectIdentifier{2, 5, 29, 29}
	oidDeltaCRLIndicator  = asn1.ObjectIdentifier{2, 5, 29, 27}
	oidIssuingDistPoint   = asn1.ObjectIdentifier{2, 5, 29, 28}
	oidExtKeyUsageOCSPSig = x509.ExtKeyUsageOCSPSigning
)

// crl returns the CRL at uri when it can vouch for leaf now: from the cache, or fetched. A nil CRL
// with a nil error is a CRL that was obtained but cannot be used (another issuer, a bad
// signature, out of date); the JDK drops those silently.
func (c *revocationChecker) crl(ctx context.Context, uri string, leaf, issuer *x509.Certificate, now time.Time) (*crl, *revocationError) {
	if cached := c.cached(uri, now); cached != nil {
		// The cache skips the fetch, never the checks.
		if usable := approveCRL(cached, leaf, issuer, now) == nil; usable {
			return cached, nil
		}
		return nil, nil
	}

	body, err := c.get(ctx, uri, c.maxCRL)
	if errors.Is(err, cpu.ErrGaveUp) {
		// The request ended while it waited for its CPU slot again: nothing is forgiven for that.
		return nil, revocationErr(failed, err, "the CRL at %s was not checked", uri)
	}
	if errors.Is(err, errTooLarge) {
		// The JDK treats a CRL over com.sun.security.crl.maxSize as no CRL, not as an error.
		return nil, nil
	}
	if err != nil {
		return nil, revocationErr(undetermined, err, "the CRL at %s could not be fetched", uri)
	}
	list, err := parseCRL(body)
	if err != nil {
		// A named source that cannot be read soft-fails, as one that cannot be reached does (owner,
		// 2026-11-15). The JDK refuses here (a CRLException); this is a deliberate difference.
		return nil, revocationErr(undetermined, err, "the CRL at %s cannot be read", uri)
	}
	if err := approveCRL(list, leaf, issuer, now); err != nil {
		c.log.WarnContext(ctx, "a CRL was ignored", slog.String("url", uri), slog.String("reason", err.Error()))
		return nil, nil
	}
	c.remember(uri, list, now)
	return list, nil
}

// crl is a parsed CRL, version 1 or 2. x509.ParseRevocationList reads only version 2, and keytool
// -gencrl, which made the test CA's CRLs, writes version 1, as some CAs still do; the JDK reads
// both.
type crl struct {
	rawIssuer  []byte
	thisUpdate time.Time
	nextUpdate time.Time
	entries    []pkix.RevokedCertificate
	extensions []pkix.Extension
	signed     *pkix.CertificateList
}

// tbsCertList is RFC 5280 §5.1's TBSCertList, with the issuer kept as it was encoded.
type tbsCertList struct {
	Raw                 asn1.RawContent
	Version             int `asn1:"optional,default:0"`
	Signature           pkix.AlgorithmIdentifier
	Issuer              asn1.RawValue
	ThisUpdate          time.Time
	NextUpdate          time.Time                 `asn1:"optional"`
	RevokedCertificates []pkix.RevokedCertificate `asn1:"optional"`
	Extensions          []pkix.Extension          `asn1:"tag:0,optional,explicit"`
}

func parseCRL(body []byte) (*crl, error) {
	if trimmed := bytes.TrimSpace(body); bytes.HasPrefix(trimmed, []byte("-----BEGIN")) {
		block, _ := pem.Decode(trimmed)
		if block == nil || block.Type != "X509 CRL" {
			return nil, errors.New("not a PEM X509 CRL")
		}
		body = block.Bytes
	}
	if len(body) == 0 {
		return nil, errors.New("empty CRL")
	}
	var signed pkix.CertificateList
	if rest, err := asn1.Unmarshal(body, &signed); err != nil {
		return nil, err
	} else if len(rest) > 0 {
		return nil, errors.New("trailing data after the CRL")
	}
	var tbs tbsCertList
	if rest, err := asn1.Unmarshal(signed.TBSCertList.Raw, &tbs); err != nil {
		return nil, err
	} else if len(rest) > 0 {
		return nil, errors.New("trailing data in the CRL")
	}
	if tbs.Version != 0 && tbs.Version != 1 {
		return nil, fmt.Errorf("unsupported CRL version %d", tbs.Version+1)
	}
	return &crl{
		rawIssuer:  tbs.Issuer.FullBytes,
		thisUpdate: tbs.ThisUpdate,
		nextUpdate: tbs.NextUpdate,
		entries:    tbs.RevokedCertificates,
		extensions: tbs.Extensions,
		signed:     &signed,
	}, nil
}

// approveCRL is what the JDK requires of a CRL before believing it: issued by the certificate's
// issuer, signed with its key (and the issuer allowed to sign CRLs when it states a key usage),
// within its validity interval (15 minutes' skew, and a nextUpdate), complete (not a delta) and
// with no critical extension it does not understand.
func approveCRL(list *crl, leaf, issuer *x509.Certificate, now time.Time) error {
	if !bytes.Equal(list.rawIssuer, leaf.RawIssuer) {
		return errors.New("the CRL is issued by another CA")
	}
	if hasExtension(issuer, oidKeyUsage) && issuer.KeyUsage&x509.KeyUsageCRLSign == 0 {
		return errors.New("the issuer may not sign CRLs")
	}
	//nolint:staticcheck // SA1019: the one standard-library check that takes a version 1 CRL; the signature algorithms it accepts are those of x509.Certificate.CheckSignature.
	if err := issuer.CheckCRLSignature(list.signed); err != nil {
		return fmt.Errorf("the CRL's signature does not verify against the issuer: %w", err)
	}
	if list.nextUpdate.IsZero() {
		return errors.New("the CRL has no nextUpdate")
	}
	if now.Add(-maxClockSkew).After(list.nextUpdate) || now.Add(maxClockSkew).Before(list.thisUpdate) {
		return errors.New("the CRL is out of date")
	}
	for _, ext := range list.extensions {
		if ext.Id.Equal(oidDeltaCRLIndicator) {
			return errors.New("the CRL is a delta CRL")
		}
		if ext.Critical && !ext.Id.Equal(oidIssuingDistPoint) {
			return fmt.Errorf("the CRL has an unrecognised critical extension %s", ext.Id)
		}
	}
	return nil
}

func (c *revocationChecker) cached(uri string, now time.Time) *crl {
	c.mu.Lock()
	defer c.mu.Unlock()
	entry, ok := c.crls[uri]
	if !ok {
		return nil
	}
	if !now.Before(entry.expires) {
		delete(c.crls, uri)
		return nil
	}
	return entry.list
}

// remember caches a verified CRL until its NextUpdate (ADR-0027).
func (c *revocationChecker) remember(uri string, list *crl, now time.Time) {
	if !now.Before(list.nextUpdate) {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, ok := c.crls[uri]; !ok && len(c.crls) >= c.cacheSize {
		for k, e := range c.crls {
			if !now.Before(e.expires) {
				delete(c.crls, k)
			}
		}
		for k := range c.crls {
			if len(c.crls) < c.cacheSize {
				break
			}
			delete(c.crls, k)
		}
	}
	c.crls[uri] = cachedCRL{list: list, expires: list.nextUpdate}
}

// checkOCSP is the OCSP half: the first responder of the certificate's Authority Information Access.
func (c *revocationChecker) checkOCSP(ctx context.Context, leaf, issuer *x509.Certificate, now time.Time) *revocationError {
	if len(leaf.OCSPServer) == 0 {
		return revocationErr(failed, nil, "the certificate names no OCSP responder")
	}
	responder := leaf.OCSPServer[0]
	request, err := ocsp.CreateRequest(leaf, issuer, nil)
	if err != nil {
		return revocationErr(failed, err, "the OCSP request cannot be built")
	}
	body, err := c.post(ctx, responder, request)
	if errors.Is(err, cpu.ErrGaveUp) {
		return revocationErr(failed, err, "the OCSP answer from %s was not checked", responder)
	}
	if err != nil {
		return revocationErr(undetermined, err, "the OCSP responder at %s could not be reached", responder)
	}

	resp, err := ocsp.ParseResponseForCert(body, leaf, nil)
	if err != nil {
		return classifyOCSPParseError(responder, err)
	}
	signer, rerr := ocspSigner(resp, issuer, now)
	if rerr != nil {
		return rerr
	}
	if err := resp.CheckSignatureFrom(signer); err != nil {
		return revocationErr(failed, err, "the OCSP answer's signature does not verify")
	}
	// The answer must be current: [thisUpdate − skew, max(thisUpdate, nextUpdate) + skew].
	until := resp.ThisUpdate
	if !resp.NextUpdate.IsZero() {
		until = resp.NextUpdate
	}
	if now.Add(maxClockSkew).Before(resp.ThisUpdate) || now.Add(-maxClockSkew).After(until) {
		return revocationErr(failed, nil, "the OCSP answer is out of date")
	}

	switch resp.Status {
	case ocsp.Revoked:
		if resp.RevokedAt.Before(now) {
			return revocationErr(revoked, nil, "the certificate (serial %X) is revoked, says the OCSP responder, since %s",
				leaf.SerialNumber, resp.RevokedAt.UTC().Format(time.RFC3339))
		}
		return nil
	case ocsp.Unknown:
		return revocationErr(undetermined, nil, "the OCSP responder does not know the certificate")
	case ocsp.Good:
		return nil
	default:
		return revocationErr(failed, nil, "the OCSP answer has an unknown status %d", resp.Status)
	}
}

// classifyOCSPParseError sorts what x/crypto/ocsp reports into the JDK's answers: an answer that
// cannot be parsed is an IOException there (UNDETERMINED); an error status other than tryLater
// and internalError, a missing answer for the certificate or a bad signature is not.
func classifyOCSPParseError(responder string, err error) *revocationError {
	if status, ok := errors.AsType[ocsp.ResponseError](err); ok {
		if status.Status == ocsp.TryLater || status.Status == ocsp.InternalError {
			return revocationErr(undetermined, err, "the OCSP responder at %s answered with an error", responder)
		}
		return revocationErr(failed, err, "the OCSP responder at %s refused", responder)
	}
	if parse, ok := errors.AsType[ocsp.ParseError](err); ok {
		msg := string(parse)
		for _, hard := range []string{
			"no response matching the supplied certificate",
			"bad signature on embedded certificate",
			"unsupported critical extension",
			"unsupported issuer hash algorithm",
		} {
			if strings.HasPrefix(msg, hard) {
				return revocationErr(failed, err, "the OCSP answer from %s is not acceptable", responder)
			}
		}
	}
	return revocationErr(undetermined, err, "the OCSP answer from %s cannot be read", responder)
}

// ocspSigner finds who may have signed the answer, as the JDK does: the issuer itself, or a
// responder certificate the issuer issued for OCSP signing that is valid now.
func ocspSigner(resp *ocsp.Response, issuer *x509.Certificate, now time.Time) (*x509.Certificate, *revocationError) {
	candidates := []*x509.Certificate{}
	if resp.Certificate != nil {
		candidates = append(candidates, resp.Certificate)
	}
	candidates = append(candidates, issuer)

	var named *x509.Certificate
	for _, cand := range candidates {
		if namesResponder(resp, cand) {
			named = cand
			break
		}
	}
	switch {
	case named == nil:
		return nil, revocationErr(failed, nil, "the OCSP answer's signer cannot be identified")
	case bytes.Equal(named.RawSubject, issuer.RawSubject) && bytes.Equal(named.RawSubjectPublicKeyInfo, issuer.RawSubjectPublicKeyInfo):
		return issuer, nil
	case !bytes.Equal(named.RawIssuer, issuer.RawSubject):
		return nil, revocationErr(failed, nil, "the OCSP responder's certificate is not authorised to sign OCSP answers")
	}
	ocspSigning := false
	for _, eku := range named.ExtKeyUsage {
		if eku == oidExtKeyUsageOCSPSig {
			ocspSigning = true
		}
	}
	if !ocspSigning {
		return nil, revocationErr(failed, nil, "the OCSP responder's certificate is not valid for signing OCSP answers")
	}
	if now.Before(named.NotBefore) || now.After(named.NotAfter) {
		return nil, revocationErr(failed, nil, "the OCSP responder's certificate is not within its validity period")
	}
	if err := issuer.CheckSignature(named.SignatureAlgorithm, named.RawTBSCertificate, named.Signature); err != nil {
		return nil, revocationErr(failed, err, "the OCSP responder's certificate is not signed by the issuer")
	}
	return named, nil
}

// namesResponder reports whether the answer's ResponderID (by name, or by the SHA-1 of the key)
// is cert.
func namesResponder(resp *ocsp.Response, cert *x509.Certificate) bool {
	if len(resp.RawResponderName) > 0 {
		return bytes.Equal(resp.RawResponderName, cert.RawSubject)
	}
	if len(resp.ResponderKeyHash) == 0 {
		return false
	}
	if len(cert.SubjectKeyId) > 0 && bytes.Equal(resp.ResponderKeyHash, cert.SubjectKeyId) {
		return true
	}
	var spki struct {
		Algorithm asn1.RawValue
		PublicKey asn1.BitString
	}
	if _, err := asn1.Unmarshal(cert.RawSubjectPublicKeyInfo, &spki); err != nil {
		return false
	}
	sum := sha1.Sum(spki.PublicKey.RightAlign()) //nolint:gosec // RFC 6960 KeyHash is SHA-1 by definition
	return bytes.Equal(resp.ResponderKeyHash, sum[:])
}

var errTooLarge = errors.New("the answer is larger than allowed")

func (c *revocationChecker) get(ctx context.Context, uri string, limit int64) ([]byte, error) {
	return c.exchange(ctx, http.MethodGet, uri, nil, limit)
}

func (c *revocationChecker) post(ctx context.Context, uri string, body []byte) ([]byte, error) {
	return c.exchange(ctx, http.MethodPost, uri, body, c.maxOCSP)
}

// exchange sends one request with the CPU slot ctx holds given back (cpu.Outside): a slot is never
// held while a CRL or an OCSP answer is fetched. The slot is taken again under ctx, not under the
// request's own timeout, so a source that times out still soft-fails as it did.
//
// PERFORMANCE: the fetch must stay inside cpu.Outside. A CPU slot held while waiting on the network
// keeps a processor's turn from the goroutines that hold database connections, which is the
// starvation internal/cpu was added to prevent (measured 2026-11-17). Never hold a slot across
// network or database I/O.
func (c *revocationChecker) exchange(ctx context.Context, method, uri string, body []byte, limit int64) ([]byte, error) {
	if err := httpURL(uri); err != nil {
		return nil, err
	}
	var answer []byte
	err := cpu.Outside(ctx, func() error {
		var err error
		answer, err = c.send(ctx, method, uri, body, limit)
		return err
	})
	return answer, err
}

func (c *revocationChecker) send(ctx context.Context, method, uri string, body []byte, limit int64) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, c.connectTimeout+c.readTimeout)
	defer cancel()
	var content io.Reader
	if body != nil {
		content = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, uri, content)
	if err != nil {
		return nil, err
	}
	if method == http.MethodPost {
		req.Header.Set("Content-Type", "application/ocsp-request")
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return readLimited(resp.Body, limit)
}

// httpURL accepts http and https, the schemes the JDK reads a CRL or asks a responder over (it
// would also try LDAP, which nothing here uses).
func httpURL(raw string) error {
	u, err := url.Parse(raw)
	if err != nil {
		return err
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return fmt.Errorf("unsupported scheme %q", u.Scheme)
	}
	return nil
}

func readLimited(r io.Reader, limit int64) ([]byte, error) {
	body, err := io.ReadAll(io.LimitReader(r, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(body)) > limit {
		return nil, errTooLarge
	}
	return body, nil
}

// newTransport is an http.Transport with no proxy and no cookies, the connect timeout on the dial
// and the TLS handshake, and the read timeout on the answer's headers.
func newTransport(connect, read time.Duration, dial func(ctx context.Context, network, addr string) (net.Conn, error), tlsConfig *tls.Config) *http.Transport {
	dialer := &net.Dialer{Timeout: connect}
	dialContext := dialer.DialContext
	if dial != nil {
		dialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			ctx, cancel := context.WithTimeout(ctx, connect)
			defer cancel()
			return dial(ctx, network, addr)
		}
	}
	if tlsConfig != nil {
		tlsConfig = tlsConfig.Clone()
	}
	return &http.Transport{
		Proxy:                 nil,
		DialContext:           dialContext,
		TLSClientConfig:       tlsConfig,
		TLSHandshakeTimeout:   connect,
		ResponseHeaderTimeout: read,
		MaxIdleConns:          16,
		IdleConnTimeout:       30 * time.Second,
		ForceAttemptHTTP2:     true,
	}
}

func orDuration(d, def time.Duration) time.Duration {
	if d > 0 {
		return d
	}
	return def
}

func orInt64(n, def int64) int64 {
	if n > 0 {
		return n
	}
	return def
}
