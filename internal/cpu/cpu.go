// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package cpu bounds how many goroutines do CPU-heavy JWS work at once.
//
// Why. Nearly all of this service's CPU goes to JWS: a PS512 signature on every message it signs,
// and, on every JWS it receives, the compact form split and decoded, the header read, the x5c
// certificates parsed, the chain built and checked, and the signature verified. Under load,
// hundreds of goroutines do that work at the same time, and the Go scheduler shares the processors
// between all of them. A database write transaction holds a pooled connection across several round
// trips (BEGIN, the statement timeout, two SELECTs, the writes, COMMIT); after each round trip its
// goroutine waits in that long run queue before it can send the next statement, so the connection
// is held far longer than the database needs it, and the small write pool becomes the bottleneck.
// Requests then queue in the pool's Acquire, not on the CPU.
//
// Measured on the full payment cycle at 500 virtual users with the service limited to 2 vCPU, two
// runs each: without a bound, 779 req/s and a median of 91.1/89.8 ms. A bound held only around the
// signature arithmetic (RSA sign and verify, the chain's signatures) did not help: 781/784 req/s,
// 97.2/88.8 ms. A bound held around the whole verification and the whole signature did: 811/810
// req/s, 70.1/61.0 ms. So a slot is taken once, at the outermost point: the top of a verification
// and around a whole signature.
//
// A slot is never held across network or database I/O: a goroutine waiting on a fetch must not
// keep a processor's turn. Hold marks the context it returns as holding the slot, and Outside gives
// the slot back around a fetch and takes it again afterwards. A Hold on a context that already
// holds a slot of the same Limiter takes nothing, so a slot is never nested and two cannot
// deadlock.
package cpu

import (
	"context"
	"errors"
	"fmt"
)

// ErrGaveUp is the error of a request that ended while it waited for a slot. It wraps the
// context's error too.
var ErrGaveUp = errors.New("cpu: the request ended while waiting for a slot")

// Limiter is a counting semaphore: at most its size of holders at once. Main builds one from
// runtime.GOMAXPROCS(0) and gives it to what signs and verifies. A nil *Limiter limits nothing,
// so a signer or verifier built without one (in a test, say) runs unbounded. It is safe for
// concurrent use.
type Limiter struct {
	slots chan struct{}
}

// NewLimiter returns a Limiter of n slots; n below 1 is 1.
func NewLimiter(n int) *Limiter {
	return &Limiter{slots: make(chan struct{}, max(n, 1))}
}

// Size is the number of slots; zero for a nil Limiter.
func (l *Limiter) Size() int {
	if l == nil {
		return 0
	}
	return cap(l.slots)
}

// Acquire takes a slot, waiting for one to be released. It returns ErrGaveUp, holding nothing,
// when ctx ends first or had already ended. Every nil return must be matched by one Release.
func (l *Limiter) Acquire(ctx context.Context) error {
	if l == nil {
		return nil
	}
	if err := ctx.Err(); err != nil {
		return fmt.Errorf("%w: %w", ErrGaveUp, err)
	}
	select {
	case l.slots <- struct{}{}:
		return nil
	case <-ctx.Done():
		return fmt.Errorf("%w: %w", ErrGaveUp, ctx.Err())
	}
}

// Release gives back a slot Acquire took. A Release with no slot held is a programming error and
// panics, as unlocking an unlocked mutex does.
func (l *Limiter) Release() {
	if l == nil {
		return
	}
	select {
	case <-l.slots:
	default:
		panic("cpu: Release without a matching Acquire")
	}
}

// hold is what a context carries while its goroutine holds a slot. It belongs to the one goroutine
// that took it: the context Hold returns is for that goroutine's own calls, not for goroutines it
// starts.
type hold struct {
	limiter *Limiter
	held    bool
}

type holdKey struct{}

func holdOf(ctx context.Context) *hold {
	h, _ := ctx.Value(holdKey{}).(*hold)
	return h
}

// Hold takes a slot for the work that follows and returns a context marked as holding it, with
// the function that gives it back (call it once, when the work ends; it is safe after a failed
// re-acquisition in Outside). When ctx already holds a slot of l, Hold takes nothing. It returns
// ErrGaveUp when ctx ends before a slot is free.
func (l *Limiter) Hold(ctx context.Context) (context.Context, func(), error) {
	if l == nil {
		return ctx, func() {}, nil
	}
	if h := holdOf(ctx); h != nil && h.limiter == l && h.held {
		return ctx, func() {}, nil
	}
	if err := l.Acquire(ctx); err != nil {
		return ctx, func() {}, err
	}
	h := &hold{limiter: l, held: true}
	return context.WithValue(ctx, holdKey{}, h), func() {
		if h.held {
			h.held = false
			l.Release()
		}
	}, nil
}

// Outside runs f, which waits on the network or the database, with the slot ctx holds given back,
// and takes the slot again when f returns, waiting for it under ctx. When ctx holds no slot it
// just runs f. When the slot cannot be taken again it returns ErrGaveUp (joined with f's error, if
// any), and the caller must stop: it no longer holds a slot.
func Outside(ctx context.Context, f func() error) error {
	h := holdOf(ctx)
	if h == nil || !h.held {
		return f()
	}
	h.held = false
	h.limiter.Release()
	err := f()
	if acquireErr := h.limiter.Acquire(ctx); acquireErr != nil {
		return errors.Join(acquireErr, err)
	}
	h.held = true
	return err
}
