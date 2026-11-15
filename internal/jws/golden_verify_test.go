// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package jws

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/pki"
)

// goldenManifest is what the verifier tests read of manifest.json.
type goldenManifest struct {
	Verifier struct {
		ClockSkewMillis       int64    `json:"clockSkewMillis"`
		IssuerNameMustContain string   `json:"issuerNameMustContain"`
		SupportedCritical     []string `json:"supportedCritical"`
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
			Subject string `json:"subject"`
		} `json:"signers"`
	} `json:"pki"`
	JWS []struct {
		goldenFile
		Cases []struct {
			Name           string `json:"name"`
			VerifyAtMillis int64  `json:"verifyAtMillis"`
			Expected       struct {
				Verdict          string `json:"verdict"`
				SignatureProblem string `json:"signatureProblem"`
			} `json:"expected"`
		} `json:"cases"`
	} `json:"jws"`
}

func readGoldenManifest(t *testing.T) *goldenManifest {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(goldenDir, "manifest.json"))
	if err != nil {
		t.Fatal(err)
	}
	var m goldenManifest
	if err := json.Unmarshal(data, &m); err != nil {
		t.Fatal(err)
	}
	return &m
}

// goldenPKI is the golden set's PKI, offline: the trust anchor, and an in-process server for the
// CRLs, which every dial reaches whatever host and port the certificate names.
type goldenPKI struct {
	truststore *pki.StaticTruststore
	issuer     string
	crlsServed atomic.Int64
	dial       func(ctx context.Context, network, addr string) (net.Conn, error)
}

func newGoldenPKI(t *testing.T, m *goldenManifest) *goldenPKI {
	t.Helper()
	anchors := make([]*x509.Certificate, 0, len(m.PKI.TrustAnchors))
	for _, a := range m.PKI.TrustAnchors {
		anchors = append(anchors, readPEMCertificates(t, filepath.Join(goldenDir, a.File))...)
	}
	crls := map[string][]byte{}
	for _, c := range m.PKI.CRLs {
		u, err := url.Parse(c.URL)
		if err != nil {
			t.Fatal(err)
		}
		body, err := os.ReadFile(filepath.Join(goldenDir, c.File))
		if err != nil {
			t.Fatal(err)
		}
		crls[u.Path] = body
	}
	g := &goldenPKI{truststore: pki.NewStaticTruststore(anchors...), issuer: m.Verifier.IssuerNameMustContain}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		g.crlsServed.Add(1)
		body, ok := crls[r.URL.Path]
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/pkix-crl")
		_, _ = w.Write(body)
	}))
	t.Cleanup(srv.Close)
	addr := srv.Listener.Addr().String()
	g.dial = func(ctx context.Context, network, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, network, addr)
	}
	return g
}

// verifierAt is a verifier whose clocks both read at: the business clock (iat, ttl) and the
// certificates' (validity, CRL freshness). The golden set is judged at each case's verifyAt for
// everything (README, "A case, and its clock"); in production the certificates' clock is the
// real one, which NewVerifier sets and no configuration can change.
func (g *goldenPKI) verifierAt(t *testing.T, at time.Time, skew time.Duration) *Verifier {
	t.Helper()
	v, err := newVerifier(VerifierConfig{
		Truststore: g.truststore,
		IssuerName: g.issuer,
		ClockSkew:  skew,
		Revocation: pki.RevocationConfig{DialContext: g.dial},
	}, fixedClock{at: at}, fixedClock{at: at})
	if err != nil {
		t.Fatal(err)
	}
	return v
}

func readPEMCertificates(t *testing.T, path string) []*x509.Certificate {
	t.Helper()
	data, err := os.ReadFile(filepath.Clean(path))
	if err != nil {
		t.Fatal(err)
	}
	var certs []*x509.Certificate
	for {
		var block *pem.Block
		block, data = pem.Decode(data)
		if block == nil {
			break
		}
		c, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			t.Fatal(err)
		}
		certs = append(certs, c)
	}
	if len(certs) == 0 {
		t.Fatalf("no certificate in %s", path)
	}
	return certs
}

// goldenDivergences are the golden cases where Go deliberately does not give Java's answer,
// because Java's answer breaks ANSI X9.150 or an ADR (Story 8.5, precedence rule), each with the
// answer Go gives and why. Every other case must match the manifest exactly. The ADR-0027 parity
// codes (UNVERIFIABLE for ES256 over an RSA certificate, NOT_JWS for an empty signature) are not
// divergences: Go gives Java's answer there.
var goldenDivergences = map[string]struct {
	verdict, problem, why string
}{}

// S8.5-AC2: every one of the 89 golden JWS cases gets the manifest's verdict and
// signatureProblem code at its verifyAt instant. A notification answer is judged as Java judges
// one (CheckAnswer, with the HTTP status it came with and the correlationId of the question);
// everything else by Verify.
func TestGoldenVerdicts(t *testing.T) {
	m := readGoldenManifest(t)
	g := newGoldenPKI(t, m)
	skew := time.Duration(m.Verifier.ClockSkewMillis) * time.Millisecond
	if skew != DefaultClockSkew {
		t.Fatalf("the manifest's clock skew is %s; DefaultClockSkew is %s", skew, DefaultClockSkew)
	}
	if got, want := supportedCritical, m.Verifier.SupportedCritical; !sameSet(got, want) {
		t.Fatalf("supported critical headers %v; the manifest says %v", got, want)
	}

	cases, matched := 0, 0
	for _, f := range m.JWS {
		token, err := os.ReadFile(filepath.Join(goldenDir, f.File))
		if err != nil {
			t.Fatal(err)
		}
		for _, c := range f.Cases {
			cases++
			name := f.ID + "/" + c.Name
			want, wantProblem := c.Expected.Verdict, c.Expected.SignatureProblem
			if d, ok := goldenDivergences[name]; ok {
				t.Logf("%s: Java says %s %s; Go says %s %s: %s", name, want, wantProblem, d.verdict, d.problem, d.why)
				want, wantProblem = d.verdict, d.problem
			} else {
				matched++
			}
			t.Run(name, func(t *testing.T) {
				at := time.UnixMilli(c.VerifyAtMillis).UTC()
				v := g.verifierAt(t, at, 0)
				got, gotProblem, subject := judge(t.Context(), v, f.goldenFile, string(token))
				if got != want || gotProblem != Problem(wantProblem) {
					t.Fatalf("%s %s; the manifest says %s %s", got, gotProblem, want, wantProblem)
				}
				if got == "VERIFIED" {
					if s := m.PKI.Signers[f.Signer].Subject; subject != s {
						t.Fatalf("signer %q; the manifest says %q", subject, s)
					}
				}
			})
		}
	}
	if g.crlsServed.Load() == 0 {
		t.Fatal("no CRL was asked for: revocation was not checked")
	}
	if cases != 89 {
		t.Fatalf("%d golden JWS cases; Story 8.5 names 89", cases)
	}
	t.Logf("%d of %d golden cases give Java's verdict and code; %d diverge on purpose", matched, cases, cases-matched)
}

// judge gives a golden token Go's verdict, as Java gives it.
func judge(ctx context.Context, v *Verifier, f goldenFile, token string) (verdict string, problem Problem, subject string) {
	if f.Kind == "notification-answer" {
		var sent uuid.UUID
		if s, ok := f.Header[HeaderCorrelationID].(string); ok {
			sent, _ = uuid.Parse(s)
		}
		a := v.CheckAnswer(ctx, f.HTTPStatus, token, sent)
		if a.Signature == AnswerVerified {
			return "VERIFIED", "", a.Verified.Signer
		}
		return "REFUSED", a.Problem, ""
	}
	verified, err := v.Verify(ctx, token, Expect{})
	if err != nil {
		return "REFUSED", ProblemOf(err), ""
	}
	return "VERIFIED", "", verified.Signer
}

func sameSet(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	seen := map[string]bool{}
	for _, s := range a {
		seen[s] = true
	}
	for _, s := range b {
		if !seen[s] {
			return false
		}
	}
	return true
}
