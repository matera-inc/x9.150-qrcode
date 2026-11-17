// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package pki

import (
	"context"
	"crypto"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/asn1"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/crypto/ocsp"
)

// The repository's test PKI, as the JUnit tests and the golden set use it. NON-PRODUCTION: its
// private keys are public. Nothing is copied: the files are read where they live.
const (
	goldenDir   = "../../others/blackbox/golden"
	testCADir   = "../../testdata/pki"
	testCAPass  = "x9test123"
	crlHostPort = "127.0.0.1:18081" // the distribution point the test CA's leaves name
)

// testClock is a business clock standing still at a chosen instant, moved by hand.
type testClock struct {
	mu  sync.Mutex
	now time.Time
}

func newTestClock(at time.Time) *testClock { return &testClock{now: at} }

func (c *testClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *testClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

// manifest is the part of the golden manifest these tests read.
type manifest struct {
	Epoch    time.Time `json:"epoch"`
	Verifier struct {
		IssuerNameMustContain string `json:"issuerNameMustContain"`
	} `json:"verifier"`
	PKI struct {
		TrustAnchors []struct {
			File string `json:"file"`
		} `json:"trustAnchors"`
		CRLs []struct {
			URL  string `json:"url"`
			File string `json:"file"`
		} `json:"crls"`
		Signers map[string]struct {
			File                   string `json:"file"`
			Subject                string `json:"subject"`
			Serial                 string `json:"serial"`
			X5tS256                string `json:"x5tS256"`
			IssuedByTheTrustAnchor bool   `json:"issuedByTheTrustAnchor"`
			Revoked                bool   `json:"revoked"`
		} `json:"signers"`
	} `json:"pki"`
}

func loadManifest(t *testing.T) *manifest {
	t.Helper()
	var m manifest
	if err := json.Unmarshal(readFile(t, filepath.Join(goldenDir, "manifest.json")), &m); err != nil {
		t.Fatal(err)
	}
	return &m
}

func readFile(t *testing.T, path string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Clean(path))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func readChain(t *testing.T, path string) []*x509.Certificate {
	t.Helper()
	certs, err := parseChain(readFile(t, path))
	if err != nil {
		t.Fatal(err)
	}
	return certs
}

// testCA is the repository's test root CA, with its key, read from x9-test-ca.p12.
type testCA struct {
	cert *x509.Certificate
	key  crypto.Signer
}

func loadTestCA(t *testing.T) *testCA {
	t.Helper()
	key, certs := readP12(t, filepath.Join(testCADir, "x9-test-ca.p12"))
	if key == nil || len(certs) != 1 {
		t.Fatalf("x9-test-ca.p12: want a key and one certificate, got key=%v certs=%d", key != nil, len(certs))
	}
	return &testCA{cert: certs[0], key: key}
}

// leafOf reads the end-entity certificate of one of the test CA's payer keystores.
func leafOf(t *testing.T, p12 string) *x509.Certificate {
	t.Helper()
	_, certs := readP12(t, filepath.Join(testCADir, p12))
	for _, c := range certs {
		if !c.IsCA {
			return c
		}
	}
	t.Fatalf("%s holds no end-entity certificate", p12)
	return nil
}

// issue makes a payer certificate signed by the test CA, naming the given revocation sources
// (an empty string leaves that source out), as the black-box suite's x9client.ocsp.issue does.
func (ca *testCA) issue(t *testing.T, name, crlURL, ocspURL string, notBefore time.Time) *x509.Certificate {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 63))
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: name, Organization: []string{"Example PSP"}, Country: []string{"US"}},
		NotBefore:    notBefore,
		NotAfter:     notBefore.Add(365 * 24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageContentCommitment,
	}
	if crlURL != "" {
		tmpl.CRLDistributionPoints = []string{crlURL}
	}
	if ocspURL != "" {
		tmpl.OCSPServer = []string{ocspURL}
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, ca.cert, &key.PublicKey, ca.key)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return cert
}

// crl signs a CRL listing serials, valid from thisUpdate to nextUpdate.
func (ca *testCA) crl(t *testing.T, thisUpdate, nextUpdate time.Time, serials ...*big.Int) []byte {
	t.Helper()
	entries := make([]x509.RevocationListEntry, 0, len(serials))
	for _, s := range serials {
		entries = append(entries, x509.RevocationListEntry{SerialNumber: s, RevocationTime: thisUpdate.Add(-time.Hour)})
	}
	der, err := x509.CreateRevocationList(rand.Reader, &x509.RevocationList{
		Number:                    big.NewInt(thisUpdate.Unix()),
		ThisUpdate:                thisUpdate,
		NextUpdate:                nextUpdate,
		RevokedCertificateEntries: entries,
	}, ca.cert, ca.key)
	if err != nil {
		t.Fatal(err)
	}
	return der
}

// ---------------------------------------------------------------------------- the test network

// testNetwork is the only network the tests have: every dial goes through it, a host:port it was
// told about reaches an in-process httptest server, and anything else is refused at once, as
// 127.0.0.1:1 refuses the test CA's "unreachable" sources. Nothing leaves the process.
type testNetwork struct {
	mu     sync.Mutex
	routes map[string]string
	dials  map[string]int
}

func newTestNetwork() *testNetwork {
	return &testNetwork{routes: map[string]string{}, dials: map[string]int{}}
}

func (n *testNetwork) route(hostPort string, srv *httptest.Server) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.routes[hostPort] = srv.Listener.Addr().String()
}

func (n *testNetwork) dialed(hostPort string) int {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.dials[hostPort]
}

func (n *testNetwork) totalDials() int {
	n.mu.Lock()
	defer n.mu.Unlock()
	total := 0
	for _, c := range n.dials {
		total += c
	}
	return total
}

func (n *testNetwork) dial(ctx context.Context, network, addr string) (net.Conn, error) {
	n.mu.Lock()
	n.dials[addr]++
	to, ok := n.routes[addr]
	n.mu.Unlock()
	if !ok {
		return nil, &net.OpError{Op: "dial", Net: network, Err: errors.New("connection refused (test network)")}
	}
	var d net.Dialer
	return d.DialContext(ctx, network, to)
}

// server starts an in-process HTTP server, closed when the test ends.
func server(t *testing.T, h http.Handler) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return srv
}

// crlServer serves files by path and counts the fetches of each.
type crlServer struct {
	mu      sync.Mutex
	bodies  map[string][]byte
	fetches map[string]int
}

func newCRLServer() *crlServer {
	return &crlServer{bodies: map[string][]byte{}, fetches: map[string]int{}}
}

func (s *crlServer) set(path string, body []byte) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.bodies[path] = body
}

func (s *crlServer) count(path string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.fetches[path]
}

func (s *crlServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	s.fetches[r.URL.Path]++
	body, ok := s.bodies[r.URL.Path]
	s.mu.Unlock()
	if !ok {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/pkix-crl")
	_, _ = w.Write(body)
}

// ocspResponder answers for the test CA, signing with its key as an issuing CA's responder may
// (RFC 6960 §4.2.2.2), as the black-box suite's x9client.ocsp does. "good" unless told otherwise.
type ocspResponder struct {
	ca    *testCA
	clock Clock

	mu      sync.Mutex
	status  map[string]int // serial (decimal) → ocsp.Good / Revoked / Unknown
	raw     []byte         // when set, served as is
	signer  *testCA        // when set, signs instead of the CA
	queries int
}

func (o *ocspResponder) set(cert *x509.Certificate, status int) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.status == nil {
		o.status = map[string]int{}
	}
	o.status[cert.SerialNumber.String()] = status
}

func (o *ocspResponder) asked() int {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.queries
}

func (o *ocspResponder) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	o.mu.Lock()
	o.queries++
	raw, signer := o.raw, o.signer
	o.mu.Unlock()
	if raw != nil {
		_, _ = w.Write(raw)
		return
	}
	req, err := ocsp.ParseRequest(body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	o.mu.Lock()
	status, known := o.status[req.SerialNumber.String()]
	o.mu.Unlock()
	if !known {
		status = ocsp.Good
	}
	now := o.clock.Now()
	tmpl := ocsp.Response{
		Status:       status,
		SerialNumber: req.SerialNumber,
		ThisUpdate:   now.Add(-time.Minute),
		NextUpdate:   now.Add(time.Hour),
	}
	if status == ocsp.Revoked {
		tmpl.RevokedAt = now.Add(-24 * time.Hour)
		tmpl.RevocationReason = ocsp.KeyCompromise
	}
	by := o.ca
	if signer != nil {
		by = signer
		tmpl.Certificate = signer.cert
	}
	der, err := ocsp.CreateResponse(o.ca.cert, by.cert, tmpl, by.key)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/ocsp-response")
	_, _ = w.Write(der)
}

// queried counts requests to a handler.
func queried(h http.Handler, n *atomic.Int64) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n.Add(1)
		h.ServeHTTP(w, r)
	})
}

// ------------------------------------------------------------------ a PKCS#12 reader, for tests

// readP12 reads the private key and certificates of a keytool PKCS#12 file (PBES2, PBKDF2 with
// HMAC-SHA256, AES-256-CBC: what keytool writes since JDK 12). It is the least that reads the
// repository's test keystores; the MAC is not checked. The service never reads PKCS#12 here:
// internal/keys does (Story 8.2).
func readP12(t *testing.T, path string) (crypto.Signer, []*x509.Certificate) {
	t.Helper()
	var pfx struct {
		Version  int
		AuthSafe contentInfo
		MacData  asn1.RawValue `asn1:"optional"`
	}
	mustUnmarshal(t, readFile(t, path), &pfx)
	var authSafe []contentInfo
	mustUnmarshal(t, octets(t, pfx.AuthSafe.Content.Bytes), &authSafe)

	var (
		key   crypto.Signer
		certs []*x509.Certificate
	)
	for _, ci := range authSafe {
		var safeContents []byte
		switch {
		case ci.ContentType.Equal(oidData):
			safeContents = octets(t, ci.Content.Bytes)
		case ci.ContentType.Equal(oidEncryptedData):
			var ed struct {
				Version int
				Info    struct {
					ContentType asn1.ObjectIdentifier
					Algorithm   pkix.AlgorithmIdentifier
					Content     asn1.RawValue `asn1:"tag:0,optional"`
				}
			}
			mustUnmarshal(t, ci.Content.Bytes, &ed)
			safeContents = pbes2Decrypt(t, ed.Info.Algorithm, ed.Info.Content.Bytes)
		default:
			t.Fatalf("%s: unexpected content type %s", path, ci.ContentType)
		}
		var bags []struct {
			ID    asn1.ObjectIdentifier
			Value asn1.RawValue `asn1:"tag:0,explicit"`
			Attrs asn1.RawValue `asn1:"optional,set"`
		}
		mustUnmarshal(t, safeContents, &bags)
		for _, bag := range bags {
			switch {
			case bag.ID.Equal(oidShroudedKeyBag):
				var epki struct {
					Algorithm pkix.AlgorithmIdentifier
					Data      []byte
				}
				mustUnmarshal(t, bag.Value.Bytes, &epki)
				parsed, err := x509.ParsePKCS8PrivateKey(pbes2Decrypt(t, epki.Algorithm, epki.Data))
				if err != nil {
					t.Fatal(err)
				}
				signer, ok := parsed.(crypto.Signer)
				if !ok {
					t.Fatalf("%s: the key is not a crypto.Signer", path)
				}
				key = signer
			case bag.ID.Equal(oidCertBag):
				var cb struct {
					ID    asn1.ObjectIdentifier
					Value asn1.RawValue `asn1:"tag:0,explicit"`
				}
				mustUnmarshal(t, bag.Value.Bytes, &cb)
				cert, err := x509.ParseCertificate(octets(t, cb.Value.Bytes))
				if err != nil {
					t.Fatal(err)
				}
				certs = append(certs, cert)
			}
		}
	}
	return key, certs
}

type contentInfo struct {
	ContentType asn1.ObjectIdentifier
	Content     asn1.RawValue `asn1:"tag:0,explicit,optional"`
}

var (
	oidData           = asn1.ObjectIdentifier{1, 2, 840, 113549, 1, 7, 1}
	oidEncryptedData  = asn1.ObjectIdentifier{1, 2, 840, 113549, 1, 7, 6}
	oidShroudedKeyBag = asn1.ObjectIdentifier{1, 2, 840, 113549, 1, 12, 10, 1, 2}
	oidCertBag        = asn1.ObjectIdentifier{1, 2, 840, 113549, 1, 12, 10, 1, 3}
	oidPBES2          = asn1.ObjectIdentifier{1, 2, 840, 113549, 1, 5, 13}
	oidPBKDF2         = asn1.ObjectIdentifier{1, 2, 840, 113549, 1, 5, 12}
	oidHMACSHA256     = asn1.ObjectIdentifier{1, 2, 840, 113549, 2, 9}
	oidAES256CBC      = asn1.ObjectIdentifier{2, 16, 840, 1, 101, 3, 4, 1, 42}
)

func pbes2Decrypt(t *testing.T, alg pkix.AlgorithmIdentifier, data []byte) []byte {
	t.Helper()
	if !alg.Algorithm.Equal(oidPBES2) {
		t.Fatalf("unsupported PKCS#12 encryption %s (only PBES2)", alg.Algorithm)
	}
	var params struct {
		KDF struct {
			Algorithm asn1.ObjectIdentifier
			Params    struct {
				Salt       []byte
				Iterations int
				KeyLength  int                      `asn1:"optional"`
				PRF        pkix.AlgorithmIdentifier `asn1:"optional"`
			}
		}
		Scheme struct {
			Algorithm asn1.ObjectIdentifier
			IV        []byte
		}
	}
	mustUnmarshal(t, alg.Parameters.FullBytes, &params)
	if !params.KDF.Algorithm.Equal(oidPBKDF2) || !params.KDF.Params.PRF.Algorithm.Equal(oidHMACSHA256) ||
		!params.Scheme.Algorithm.Equal(oidAES256CBC) {
		t.Fatal("unsupported PBES2 parameters (only PBKDF2-HMAC-SHA256 with AES-256-CBC)")
	}
	key, err := pbkdf2.Key(sha256.New, testCAPass, params.KDF.Params.Salt, params.KDF.Params.Iterations, 32)
	if err != nil {
		t.Fatal(err)
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	if len(data)%aes.BlockSize != 0 || len(data) == 0 {
		t.Fatal("PBES2 ciphertext is not whole blocks")
	}
	out := make([]byte, len(data))
	cipher.NewCBCDecrypter(block, params.Scheme.IV).CryptBlocks(out, data)
	pad := int(out[len(out)-1])
	if pad == 0 || pad > aes.BlockSize {
		t.Fatal("bad PBES2 padding: wrong password?")
	}
	return out[:len(out)-pad]
}

func octets(t *testing.T, der []byte) []byte {
	t.Helper()
	var b []byte
	mustUnmarshal(t, der, &b)
	return b
}

func mustUnmarshal(t *testing.T, der []byte, v any) {
	t.Helper()
	if _, err := asn1.Unmarshal(der, v); err != nil {
		t.Fatalf("ASN.1: %v", err)
	}
}

// pemOf encodes certificates as a PEM chain.
func pemOf(certs ...*x509.Certificate) []byte {
	out := make([]byte, 0, 2048*len(certs))
	for _, c := range certs {
		out = append(out, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: c.Raw})...)
	}
	return out
}
