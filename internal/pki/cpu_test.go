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

// slotFree wraps a handler: before answering, it takes the only CPU slot itself and gives it back,
// so a caller that fetched while holding the slot would stall the fetch and be counted in held.
func slotFree(slots *cpu.Limiter, held *atomic.Int64, h http.Handler) http.Handler {
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

// stillHeld reports whether the only slot of slots is taken: an Acquire that cannot get it in a
// moment.
func stillHeld(t *testing.T, slots *cpu.Limiter) bool {
	t.Helper()
	ctx, cancel := context.WithTimeout(t.Context(), 20*time.Millisecond)
	defer cancel()
	if slots.Acquire(ctx) != nil {
		return true
	}
	slots.Release()
	return false
}

func revocationCases() []struct {
	name  string
	chain func(t *testing.T, f *fixture) []*x509.Certificate
} {
	return []struct {
		name  string
		chain func(t *testing.T, f *fixture) []*x509.Certificate
	}{
		{"CRL", func(t *testing.T, f *fixture) []*x509.Certificate { return f.signer(t, "rsa") }},
		{"OCSP", func(t *testing.T, f *fixture) []*x509.Certificate {
			return []*x509.Certificate{f.ca.issue(t, "Payer OCSP slot", "", ocspURL, f.clock.Now().Add(-time.Hour))}
		}},
	}
}

// A CRL or an OCSP answer is fetched with the caller's CPU slot given back, and the slot is held
// again when the check returns.
func TestRevocationFetchesGiveTheSlotBack(t *testing.T) {
	for _, tc := range revocationCases() {
		t.Run(tc.name, func(t *testing.T) {
			f := newFixture(t)
			slots := cpu.NewLimiter(1)
			var held atomic.Int64
			crlSrv := server(t, slotFree(slots, &held, f.crls))
			f.net.route(crlHostPort, crlSrv)
			f.net.route("crl.test-ca.example:80", crlSrv)
			f.net.route("ocsp.test-ca.example:80", server(t, slotFree(slots, &held, f.ocsp)))

			ctx, release, err := slots.Hold(t.Context())
			if err != nil {
				t.Fatal(err)
			}
			defer release()
			chain := tc.chain(t, f)
			if _, err := f.verifier.Verify(ctx, chain, thumb(chain[0])); err != nil {
				t.Fatal(err)
			}
			if f.net.totalDials() == 0 {
				t.Fatal("nothing was fetched")
			}
			if n := held.Load(); n != 0 {
				t.Fatalf("%d fetches ran while the caller held the CPU slot", n)
			}
			if !stillHeld(t, slots) {
				t.Fatal("the slot was not taken again after the fetch")
			}
		})
	}
}

// A request that ends while it waits to take its slot again after a CRL or an OCSP fetch is
// refused (FAILED, never forgiven as a soft failure) with ErrGaveUp, and holds no slot.
func TestRevocationGivesUpWhenTheSlotCannotBeRetaken(t *testing.T) {
	for _, tc := range revocationCases() {
		t.Run(tc.name, func(t *testing.T) {
			f := newFixture(t)
			slots := cpu.NewLimiter(1)
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			// The source answers after another request took the slot, and this one ended.
			var taken atomic.Bool
			takeAndEnd := func(h http.Handler) http.Handler {
				return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					if err := slots.Acquire(r.Context()); err == nil {
						taken.Store(true)
					}
					cancel()
					h.ServeHTTP(w, r)
				})
			}
			crlSrv := server(t, takeAndEnd(f.crls))
			f.net.route(crlHostPort, crlSrv)
			f.net.route("crl.test-ca.example:80", crlSrv)
			f.net.route("ocsp.test-ca.example:80", server(t, takeAndEnd(f.ocsp)))

			held, release, err := slots.Hold(ctx)
			if err != nil {
				t.Fatal(err)
			}
			chain := tc.chain(t, f)
			_, err = f.verifier.Verify(held, chain, thumb(chain[0]))
			release()
			if !taken.Load() {
				t.Fatal("the source was not asked")
			}
			if err == nil || !errors.Is(err, cpu.ErrGaveUp) {
				t.Fatalf("Verify = %v, want a refusal for ErrGaveUp", err)
			}
			slots.Release() // the other request's
			if stillHeld(t, slots) {
				t.Fatal("a slot is still held")
			}
		})
	}
}
