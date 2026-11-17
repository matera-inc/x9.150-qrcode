// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package pki

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/cpu"
)

// Kind is the header a certificate is fetched for.
type Kind string

// The two headers that point at a certificate.
const (
	// JKU is a JWK set (RFC 7515 §4.1.2); the signer's chain is the x5c of its key.
	JKU Kind = "jku"
	// X5U is the chain itself, PEM or DER (RFC 7515 §4.1.5).
	X5U Kind = "x5u"
)

// FetcherConfig configures a Fetcher. Every field but Clock has a default.
type FetcherConfig struct {
	// Clock decides when a cached document expires. Required.
	Clock Clock
	// OwnHost is this deployment's public host, with its port if it has one
	// (x9.public-endpoints.host): the one host that may be fetched over plain HTTP.
	OwnHost string
	// OwnBaseURL, when set, is where a document on OwnHost is read from instead (the Java build
	// reads its own certificate from http://localhost:<port>). Path and query are kept.
	OwnBaseURL string
	// ConnectTimeout bounds opening the connection and the TLS handshake (5 s).
	ConnectTimeout time.Duration
	// ReadTimeout bounds the answer once the request is sent (15 s).
	ReadTimeout time.Duration
	// MaxBytes is the largest document read (256 KiB).
	MaxBytes int64
	// CacheTTL is how long a remembered document is used without fetching it again (1 h).
	CacheTTL time.Duration
	// CacheEntries bounds the number of remembered documents (1024).
	CacheEntries int
	// DialContext replaces the dialer, for tests. Nil dials the network.
	DialContext func(ctx context.Context, network, addr string) (net.Conn, error)
	// TLSConfig is the client's TLS configuration, for tests. Nil is the system roots.
	TLSConfig *tls.Config
}

const (
	defaultFetchConnectTimeout = 5 * time.Second
	defaultFetchReadTimeout    = 15 * time.Second
	defaultFetchMaxBytes       = 256 << 10
	defaultFetchCacheTTL       = time.Hour
	defaultFetchCacheEntries   = 1024
)

// Fetcher reads the certificate a jku or x5u header points at. The URL is unauthenticated input,
// so (ADR-0027, "Certificate fetching"): no redirect is followed, no credential is sent (no
// cookie, no Authorization, and a user name or password in the URL is dropped), the connection
// and the answer have timeouts, the answer has a size cap, and only HTTPS leaves the deployment:
// a plain http URL to any other host is fetched over HTTPS, as the Java build rewrites it.
//
// A document is remembered under the header's x5t#S256 once a token that used it was verified
// (Remember); the next token with the same thumbprint skips the fetch, never the checks.
//
// Every error it returns is CERTIFICATE_UNAVAILABLE. It is safe for concurrent use.
type Fetcher struct {
	clock    Clock
	ownHost  string
	ownBase  *url.URL
	client   *http.Client
	timeout  time.Duration
	maxBytes int64
	ttl      time.Duration
	entries  int

	mu    sync.Mutex
	cache map[cacheKey]cachedDocument
}

type cacheKey struct {
	kind Kind
	x5t  string
}

type cachedDocument struct {
	body    []byte
	expires time.Time
}

// NewFetcher returns a Fetcher.
func NewFetcher(cfg FetcherConfig) (*Fetcher, error) {
	if cfg.Clock == nil {
		return nil, errors.New("pki: the fetcher needs a clock")
	}
	f := &Fetcher{
		clock:    cfg.Clock,
		ownHost:  strings.ToLower(strings.TrimSpace(cfg.OwnHost)),
		maxBytes: orInt64(cfg.MaxBytes, defaultFetchMaxBytes),
		ttl:      orDuration(cfg.CacheTTL, defaultFetchCacheTTL),
		entries:  int(orInt64(int64(cfg.CacheEntries), defaultFetchCacheEntries)),
		cache:    map[cacheKey]cachedDocument{},
	}
	if cfg.OwnBaseURL != "" {
		base, err := url.Parse(cfg.OwnBaseURL)
		if err != nil || (base.Scheme != "http" && base.Scheme != "https") || base.Host == "" {
			return nil, fmt.Errorf("pki: the own base URL %q is not an absolute http(s) URL", cfg.OwnBaseURL)
		}
		f.ownBase = base
	}
	connect := orDuration(cfg.ConnectTimeout, defaultFetchConnectTimeout)
	read := orDuration(cfg.ReadTimeout, defaultFetchReadTimeout)
	f.timeout = connect + read
	f.client = &http.Client{
		Transport:     newTransport(connect, read, cfg.DialContext, cfg.TLSConfig),
		CheckRedirect: noRedirects,
		Jar:           nil,
	}
	return f, nil
}

// Document is what a jku or x5u URL served.
type Document struct {
	Kind Kind
	// Body is the document as served: a JWK set (JSON) or a certificate chain (PEM or DER).
	Body []byte
	// Cached reports that it was remembered rather than fetched.
	Cached bool
}

// Certificates parses an x5u document, as Java's CertificateFactory.generateCertificates does:
// every CERTIFICATE block of a PEM document, or DER certificates back to back.
func (d *Document) Certificates() ([]*x509.Certificate, error) {
	certs, err := parseChain(d.Body)
	if err != nil {
		return nil, refuse(CertificateUnavailable, err, "the certificate document cannot be read")
	}
	return certs, nil
}

// Fetch returns the document rawURL serves, or the one remembered for x5tS256 (nil when the header
// has none).
func (f *Fetcher) Fetch(ctx context.Context, kind Kind, rawURL string, x5tS256 *string) (*Document, error) {
	if kind != JKU && kind != X5U {
		return nil, refuse(CertificateUnavailable, nil, "unsupported certificate reference %q", kind)
	}
	if x5tS256 != nil {
		if body := f.remembered(kind, *x5tS256); body != nil {
			return &Document{Kind: kind, Body: body, Cached: true}, nil
		}
	}
	target, err := f.target(rawURL)
	if err != nil {
		return nil, refuse(CertificateUnavailable, err, "the %s URL %q cannot be used", kind, rawURL)
	}
	body, err := f.get(ctx, kind, target)
	if errors.Is(err, cpu.ErrGaveUp) {
		// The request ended while it waited for its CPU slot again: it was not refused, it is gone.
		return nil, fmt.Errorf("pki: the %s certificate was fetched, but not checked: %w", kind, err)
	}
	if err != nil {
		return nil, refuse(CertificateUnavailable, err, "the signer's certificate could not be retrieved from %s", target.Redacted())
	}
	if len(bytes.TrimSpace(body)) == 0 {
		return nil, refuse(CertificateUnavailable, nil, "%s served an empty document", target.Redacted())
	}
	if kind == JKU {
		// Java reads the set into a map and refuses one that is empty or is not a JSON object.
		var set map[string]json.RawMessage
		if err := json.Unmarshal(body, &set); err != nil || len(set) == 0 {
			return nil, refuse(CertificateUnavailable, err, "%s did not serve a JWK set", target.Redacted())
		}
	}
	return &Document{Kind: kind, Body: body}, nil
}

// Remember keeps doc under x5tS256, for the next token with the same thumbprint. Call it only
// after the token the document came with has been verified in full: a document is never
// remembered on the say-so of an unverified header. A document that holds no certificate with
// that thumbprint is not kept.
func (f *Fetcher) Remember(doc *Document, x5tS256 string) {
	if doc == nil || doc.Cached || x5tS256 == "" || !holds(doc, x5tS256) {
		return
	}
	now := f.clock.Now()
	key := cacheKey{kind: doc.Kind, x5t: x5tS256}
	f.mu.Lock()
	defer f.mu.Unlock()
	if _, ok := f.cache[key]; !ok && len(f.cache) >= f.entries {
		for k, e := range f.cache {
			if !now.Before(e.expires) {
				delete(f.cache, k)
			}
		}
		for k := range f.cache {
			if len(f.cache) < f.entries {
				break
			}
			delete(f.cache, k)
		}
	}
	f.cache[key] = cachedDocument{body: append([]byte(nil), doc.Body...), expires: now.Add(f.ttl)}
}

func (f *Fetcher) remembered(kind Kind, x5t string) []byte {
	now := f.clock.Now()
	key := cacheKey{kind: kind, x5t: x5t}
	f.mu.Lock()
	defer f.mu.Unlock()
	e, ok := f.cache[key]
	if !ok {
		return nil
	}
	if !now.Before(e.expires) {
		delete(f.cache, key)
		return nil
	}
	return append([]byte(nil), e.body...)
}

// target is the URL actually fetched: https, no credentials, and the deployment's own host read
// where it is configured to be read.
func (f *Fetcher) target(raw string) (*url.URL, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return nil, err
	}
	if u.Scheme == "" && u.Host == "" {
		// Java reads a URL without a scheme as https.
		if u, err = url.Parse("https://" + raw); err != nil {
			return nil, err
		}
	}
	u.Scheme = strings.ToLower(u.Scheme)
	if u.Scheme != "http" && u.Scheme != "https" {
		return nil, fmt.Errorf("the scheme %q is not http or https", u.Scheme)
	}
	if u.Host == "" {
		return nil, errors.New("the URL has no host")
	}
	u.User = nil
	u.Fragment = ""

	if f.ownHost != "" && strings.EqualFold(u.Host, f.ownHost) {
		if f.ownBase != nil {
			own := *f.ownBase
			own.Path = strings.TrimSuffix(own.Path, "/") + u.Path
			own.RawPath = ""
			own.RawQuery = u.RawQuery
			return &own, nil
		}
		return u, nil
	}
	u.Scheme = "https"
	return u, nil
}

// get fetches with the CPU slot ctx holds given back (cpu.Outside), and takes it again after.
func (f *Fetcher) get(ctx context.Context, kind Kind, target *url.URL) ([]byte, error) {
	var body []byte
	err := cpu.Outside(ctx, func() error {
		var err error
		body, err = f.fetch(ctx, kind, target)
		return err
	})
	return body, err
}

func (f *Fetcher) fetch(ctx context.Context, kind Kind, target *url.URL) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, f.timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target.String(), nil)
	if err != nil {
		return nil, err
	}
	if kind == JKU {
		req.Header.Set("Accept", "application/jwk-set+json, application/json")
	} else {
		req.Header.Set("Accept", "application/pem-certificate-chain, application/x-pem-file, */*")
	}
	resp, err := f.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	if resp.ContentLength > f.maxBytes {
		return nil, errTooLarge
	}
	return readLimited(resp.Body, f.maxBytes)
}

// parseChain reads PEM CERTIFICATE blocks, or DER certificates back to back.
func parseChain(body []byte) ([]*x509.Certificate, error) {
	trimmed := bytes.TrimSpace(body)
	if !bytes.HasPrefix(trimmed, []byte("-----BEGIN")) {
		return x509.ParseCertificates(trimmed)
	}
	var certs []*x509.Certificate
	rest := trimmed
	for {
		var block *pem.Block
		block, rest = pem.Decode(rest)
		if block == nil {
			break
		}
		if block.Type != "CERTIFICATE" {
			continue
		}
		cert, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			return nil, err
		}
		certs = append(certs, cert)
	}
	if len(certs) == 0 {
		return nil, errors.New("no certificate in the PEM document")
	}
	return certs, nil
}

// holds reports whether doc carries a certificate whose x5t#S256 is x5t.
func holds(doc *Document, x5t string) bool {
	switch doc.Kind {
	case X5U:
		certs, err := parseChain(doc.Body)
		if err != nil {
			return false
		}
		for _, c := range certs {
			if Thumbprint(c) == x5t {
				return true
			}
		}
	case JKU:
		var set struct {
			Keys []struct {
				X5C []string `json:"x5c"`
			} `json:"keys"`
		}
		if json.Unmarshal(doc.Body, &set) != nil {
			return false
		}
		for _, k := range set.Keys {
			for _, b64 := range k.X5C {
				der, err := base64.StdEncoding.DecodeString(b64)
				if err != nil {
					continue
				}
				if c, err := x509.ParseCertificate(der); err == nil && Thumbprint(c) == x5t {
					return true
				}
			}
		}
	}
	return false
}
