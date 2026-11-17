// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package pki

import (
	"context"
	"crypto/x509"
	"errors"
	"net/http"
	"sync/atomic"
	"testing"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/cpu"
)

// limitedVerifier is the fixture's verifier with a CPU limiter.
func (f *fixture) limitedVerifier(t *testing.T, slots *cpu.Limiter) *Verifier {
	t.Helper()
	v, err := NewVerifier(Config{
		Truststore: NewStaticTruststore(f.anchor),
		Clock:      f.clock,
		IssuerName: f.m.Verifier.IssuerNameMustContain,
		Revocation: RevocationConfig{DialContext: f.net.dial},
		CPU:        slots,
	})
	if err != nil {
		t.Fatal(err)
	}
	return v
}

// The chain's signatures are checked holding a CPU slot, before anything is fetched: with the only
// slot taken, a verification whose request ends gives up without having dialled, and the error is
// the request's, not a refusal of the certificate.
func TestChainWaitsForACPUSlot(t *testing.T) {
	f := newFixture(t)
	slots := cpu.NewLimiter(1)
	v := f.limitedVerifier(t, slots)
	chain := f.signer(t, "rsa")

	if err := slots.Acquire(t.Context()); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 50*time.Millisecond)
	defer cancel()
	_, err := v.Verify(ctx, chain, thumb(chain[0]))
	slots.Release()
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("Verify with the only slot held = %v, want the request's deadline", err)
	}
	if p, ok := ProblemOf(err); ok {
		t.Fatalf("a request that ended is not a refusal of the certificate; got %s", p)
	}
	if n := f.net.totalDials(); n != 0 {
		t.Fatalf("%d dials before the chain was checked", n)
	}

	if _, err := v.Verify(t.Context(), chain, thumb(chain[0])); err != nil {
		t.Fatalf("Verify with the slot free: %v", err)
	}
}

// slotFree wraps a handler: before answering, it takes the only CPU slot itself and gives it back,
// so a verifier that fetched while holding it would stall the fetch and be reported.
func slotFree(t *testing.T, slots *cpu.Limiter, held *atomic.Int64, h http.Handler) http.Handler {
	t.Helper()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		if err := slots.Acquire(ctx); err != nil {
			held.Add(1)
		} else {
			slots.Release()
		}
		h.ServeHTTP(w, r)
	})
}

// No CPU slot is held while a CRL or an OCSP answer is fetched.
func TestRevocationFetchesHoldNoSlot(t *testing.T) {
	for _, tc := range []struct {
		name  string
		chain func(t *testing.T, f *fixture) []*x509.Certificate
	}{
		{"CRL", func(t *testing.T, f *fixture) []*x509.Certificate { return f.signer(t, "rsa") }},
		{"OCSP", func(t *testing.T, f *fixture) []*x509.Certificate {
			return []*x509.Certificate{f.ca.issue(t, "Payer OCSP slot", "", ocspURL, f.clock.Now().Add(-time.Hour))}
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newFixture(t)
			slots := cpu.NewLimiter(1)
			var held atomic.Int64
			crlSrv := server(t, slotFree(t, slots, &held, f.crls))
			f.net.route(crlHostPort, crlSrv)
			f.net.route("crl.test-ca.example:80", crlSrv)
			f.net.route("ocsp.test-ca.example:80", server(t, slotFree(t, slots, &held, f.ocsp)))
			v := f.limitedVerifier(t, slots)

			chain := tc.chain(t, f)
			if _, err := v.Verify(t.Context(), chain, thumb(chain[0])); err != nil {
				t.Fatal(err)
			}
			if f.net.totalDials() == 0 {
				t.Fatal("nothing was fetched")
			}
			if n := held.Load(); n != 0 {
				t.Fatalf("%d fetches ran while the verifier held the CPU slot", n)
			}
		})
	}
}
