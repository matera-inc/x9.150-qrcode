// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package cpu bounds how many goroutines run CPU-heavy cryptography at once.
//
// Why. Nearly all of this service's CPU goes to RSA: a PS512 signature on every answer it signs,
// and a signature check (plus the certificate chain's) on every JWS it receives. Under load,
// hundreds of goroutines do that arithmetic at the same time, and the Go scheduler shares the
// processors between all of them. A database write transaction holds a pooled connection across
// several round trips (BEGIN, the statement timeout, two SELECTs, the writes, COMMIT); after each
// round trip its goroutine waits in that long run queue behind RSA work before it can send the next
// statement, so the connection is held far longer than the database needs it, and the small write
// pool becomes the bottleneck. Requests then queue in the pool's Acquire, not on the CPU.
//
// Measured on the full payment cycle at 500 virtual users with the service limited to 2 vCPU: the
// write median moved by ~28 ms on a change as small as one pass-through HTTP middleware, while CPU
// profiles of the fast and the slow runs were the same (75% RSA, under 1% apart); block profiles
// put the difference in the write pool's Acquire. With at most GOMAXPROCS goroutines in RSA at
// once, and everything else free to run between them, the same build went from 779 to 806 req/s,
// its median from 93.3 to 67.6 ms and its p99 from 211 to 182 ms.
//
// A Limiter is held only around the arithmetic itself, never across network or database I/O: a
// goroutine waiting on a fetch must not keep a processor's slot.
package cpu

import (
	"context"
	"fmt"
)

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

// Acquire takes a slot, waiting for one to be released. It returns ctx's error, holding nothing,
// when ctx ends first or had already ended. Every nil return must be matched by one Release.
func (l *Limiter) Acquire(ctx context.Context) error {
	if l == nil {
		return nil
	}
	if err := ctx.Err(); err != nil {
		return fmt.Errorf("cpu: waiting for a slot: %w", err)
	}
	select {
	case l.slots <- struct{}{}:
		return nil
	case <-ctx.Done():
		return fmt.Errorf("cpu: waiting for a slot: %w", ctx.Err())
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

// Do runs f holding a slot. It returns Acquire's error without running f when ctx ends first.
func (l *Limiter) Do(ctx context.Context, f func()) error {
	if err := l.Acquire(ctx); err != nil {
		return err
	}
	defer l.Release()
	f()
	return nil
}
