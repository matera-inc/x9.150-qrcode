// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package jws

import (
	"context"
	"errors"
	"testing"
	"testing/synctest"

	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/cpu"
	"github.com/matera-inc/x9.150-qrcode/internal/pki"
)

// A signature computes holding a CPU slot: with the only slot taken, Sign waits, and finishes once
// it is released.
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

// Verification checks the chain and the signature holding a CPU slot: with the only slot taken,
// Verify waits, and verifies once it is released.
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
