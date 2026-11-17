// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package jws

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/cpu"
	"github.com/matera-inc/x9.150-qrcode/internal/pki"
)

// A whole signature runs holding a CPU slot: with the only slot taken, Sign waits, and finishes
// once it is released.
func TestSignWaitsForACPUSlot(t *testing.T) {
	key := keyFor(t, "RSA") // made outside the bubble: shared by the whole run
	synctest.Test(t, func(t *testing.T) {
		slots := cpu.NewLimiter(1)
		s := newSigner(t, Config{Algorithm: "PS512", Publication: PublishChain, CPU: slots}, fixedClock{epoch})
		if err := slots.Acquire(t.Context()); err != nil {
			t.Fatal(err)
		}
		type result struct {
			token string
			err   error
		}
		done := make(chan result, 1)
		go func() {
			token, err := s.Sign(t.Context(), key, Message{Payload: []byte(`{}`), CorrelationID: uuid.New()})
			done <- result{token, err}
		}()
		synctest.Wait()
		select {
		case r := <-done:
			t.Fatalf("Sign finished (%v) while the only CPU slot was held", r.err)
		default:
		}
		slots.Release()
		r := <-done
		if r.err != nil {
			t.Fatalf("Sign after the slot was released: %v", r.err)
		}
		verifyStdlib(t, decode(t, r.token), key.Public())
		if slots.Acquire(t.Context()) != nil {
			t.Fatal("Sign kept its slot")
		}
		slots.Release()
	})
}

// A request that ends while its signature waits for a slot fails, holding nothing.
func TestSignGivesUpWhenItsContextEnds(t *testing.T) {
	key := keyFor(t, "RSA")
	synctest.Test(t, func(t *testing.T) {
		slots := cpu.NewLimiter(1)
		s := newSigner(t, Config{Algorithm: "RS256", Publication: PublishChain, CPU: slots}, fixedClock{epoch})
		if err := slots.Acquire(t.Context()); err != nil {
			t.Fatal(err)
		}
		defer slots.Release()
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan error, 1)
		go func() {
			_, err := s.Sign(ctx, key, Message{Payload: []byte(`{}`), CorrelationID: uuid.New()})
			done <- err
		}()
		synctest.Wait()
		cancel()
		if err := <-done; !errors.Is(err, context.Canceled) {
			t.Fatalf("Sign = %v, want context.Canceled", err)
		}
	})
}

// A whole verification runs holding a CPU slot: with the only slot taken, Verify waits, and
// verifies once it is released.
func TestVerifyWaitsForACPUSlot(t *testing.T) {
	key := keyFor(t, "RSA")
	token, err := newSigner(t, Config{Algorithm: "PS512", Publication: PublishChain}, fixedClock{epoch}).
		Sign(t.Context(), key, Message{Payload: []byte(`{}`), CorrelationID: uuid.New()})
	if err != nil {
		t.Fatal(err)
	}
	synctest.Test(t, func(t *testing.T) {
		slots := cpu.NewLimiter(1)
		v, err := newVerifier(VerifierConfig{
			Truststore: pki.NewStaticTruststore(key.chain...),
			IssuerName: pki.DefaultIssuerName,
			CPU:        slots,
		}, fixedClock{epoch}, fixedClock{epoch})
		if err != nil {
			t.Fatal(err)
		}
		if err := slots.Acquire(t.Context()); err != nil {
			t.Fatal(err)
		}
		done := make(chan error, 1)
		go func() {
			_, err := v.Verify(t.Context(), token, Expect{})
			done <- err
		}()
		synctest.Wait()
		select {
		case err := <-done:
			t.Fatalf("Verify finished (%v) while the only CPU slot was held", err)
		default:
		}
		slots.Release()
		if err := <-done; err != nil {
			t.Fatalf("Verify after the slot was released: %v", err)
		}
		if slots.Acquire(t.Context()) != nil {
			t.Fatal("Verify kept its slot")
		}
		slots.Release()
	})
}

// A request that ends while its verification waits for a slot is UNVERIFIABLE: it was not checked.
func TestVerifyGivesUpWhenItsContextEnds(t *testing.T) {
	key := keyFor(t, "RSA")
	token, err := newSigner(t, Config{Algorithm: "PS512", Publication: PublishChain}, fixedClock{epoch}).
		Sign(t.Context(), key, Message{Payload: []byte(`{}`), CorrelationID: uuid.New()})
	if err != nil {
		t.Fatal(err)
	}
	synctest.Test(t, func(t *testing.T) {
		slots := cpu.NewLimiter(1)
		v, err := newVerifier(VerifierConfig{
			Truststore: pki.NewStaticTruststore(key.chain...),
			IssuerName: pki.DefaultIssuerName,
			CPU:        slots,
		}, fixedClock{epoch}, fixedClock{epoch})
		if err != nil {
			t.Fatal(err)
		}
		if err := slots.Acquire(t.Context()); err != nil {
			t.Fatal(err)
		}
		defer slots.Release()
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan error, 1)
		go func() {
			_, err := v.Verify(ctx, token, Expect{})
			done <- err
		}()
		synctest.Wait()
		cancel()
		err = <-done
		if ProblemOf(err) != Unverifiable || !errors.Is(err, context.Canceled) {
			t.Fatalf("Verify = %v (%s), want UNVERIFIABLE for context.Canceled", err, ProblemOf(err))
		}
	})
}

// A signature or a verification run on a context that already holds the only slot (a verify that
// also signs, say) takes no second slot, so it cannot deadlock.
func TestNoNestedSlot(t *testing.T) {
	key := keyFor(t, "RSA")
	synctest.Test(t, func(t *testing.T) {
		slots := cpu.NewLimiter(1)
		s := newSigner(t, Config{Algorithm: "PS512", Publication: PublishChain, CPU: slots}, fixedClock{epoch})
		v, err := newVerifier(VerifierConfig{
			Truststore: pki.NewStaticTruststore(key.chain...),
			IssuerName: pki.DefaultIssuerName,
			CPU:        slots,
		}, fixedClock{epoch}, fixedClock{epoch})
		if err != nil {
			t.Fatal(err)
		}
		ctx, release, err := slots.Hold(t.Context())
		if err != nil {
			t.Fatal(err)
		}
		defer release()
		// synctest reports a deadlock if either waits for the slot ctx holds.
		token, err := s.Sign(ctx, key, Message{Payload: []byte(`{}`), CorrelationID: uuid.New()})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := v.Verify(ctx, token, Expect{}); err != nil {
			t.Fatal(err)
		}
	})
}

// The jku certificate is fetched with the verification's slot given back: with a limiter of 1,
// the publisher can take the slot itself while it answers.
func TestJKUFetchGivesTheSlotBack(t *testing.T) {
	for _, pub := range []Publication{PublishJWKSet, PublishPEM} {
		t.Run(string(pub), func(t *testing.T) {
			key := keyFor(t, "RSA")
			p := newPublisher(t, key, "PS512")
			slots := cpu.NewLimiter(1)
			var held atomic.Int64
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
				defer cancel()
				if err := slots.Acquire(ctx); err != nil {
					held.Add(1)
				} else {
					slots.Release()
				}
				p.srv.Config.Handler.ServeHTTP(w, r)
			}))
			t.Cleanup(srv.Close)
			fetcher, err := pki.NewFetcher(pki.FetcherConfig{Clock: fixedClock{epoch}, OwnHost: testHost, OwnBaseURL: srv.URL})
			if err != nil {
				t.Fatal(err)
			}
			v, err := newVerifier(VerifierConfig{
				Truststore: pki.NewStaticTruststore(key.chain...),
				IssuerName: pki.DefaultIssuerName,
				Fetcher:    fetcher,
				CPU:        slots,
			}, fixedClock{epoch}, fixedClock{epoch})
			if err != nil {
				t.Fatal(err)
			}
			s := newSigner(t, Config{Algorithm: "PS512", Publication: pub, Host: testHost, CPU: slots}, fixedClock{epoch})
			token, err := s.Sign(t.Context(), key, Message{Payload: []byte(`{}`), CorrelationID: uuid.New()})
			if err != nil {
				t.Fatal(err)
			}
			if _, err := v.Verify(t.Context(), token, Expect{}); err != nil {
				t.Fatal(err)
			}
			if p.fetched.Load() == 0 {
				t.Fatal("the certificate was not fetched")
			}
			if n := held.Load(); n != 0 {
				t.Fatalf("%d fetches ran while the verification held the CPU slot", n)
			}
			if err := slots.Acquire(t.Context()); err != nil {
				t.Fatal(err)
			}
			slots.Release()
		})
	}
}

// A request that ends while it waits to take its slot again after the jku fetch is UNVERIFIABLE,
// not a refusal of the certificate, and holds no slot.
func TestVerifyGivesUpAfterTheFetch(t *testing.T) {
	key := keyFor(t, "RSA")
	p := newPublisher(t, key, "PS512")
	slots := cpu.NewLimiter(1)
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := slots.Acquire(r.Context()); err != nil { // another request takes the slot
			t.Error(err)
		}
		cancel()
		p.srv.Config.Handler.ServeHTTP(w, r)
	}))
	t.Cleanup(srv.Close)
	fetcher, err := pki.NewFetcher(pki.FetcherConfig{Clock: fixedClock{epoch}, OwnHost: testHost, OwnBaseURL: srv.URL})
	if err != nil {
		t.Fatal(err)
	}
	v, err := newVerifier(VerifierConfig{
		Truststore: pki.NewStaticTruststore(key.chain...),
		IssuerName: pki.DefaultIssuerName,
		Fetcher:    fetcher,
		CPU:        slots,
	}, fixedClock{epoch}, fixedClock{epoch})
	if err != nil {
		t.Fatal(err)
	}
	token, err := newSigner(t, Config{Algorithm: "PS512", Publication: PublishJWKSet, Host: testHost}, fixedClock{epoch}).
		Sign(t.Context(), key, Message{Payload: []byte(`{}`), CorrelationID: uuid.New()})
	if err != nil {
		t.Fatal(err)
	}
	_, err = v.Verify(ctx, token, Expect{})
	if ProblemOf(err) != Unverifiable || !errors.Is(err, cpu.ErrGaveUp) {
		t.Fatalf("Verify = %v (%s), want UNVERIFIABLE for ErrGaveUp", err, ProblemOf(err))
	}
	slots.Release() // the other request's
	if err := slots.Acquire(t.Context()); err != nil {
		t.Fatal(err)
	}
	slots.Release()
}
