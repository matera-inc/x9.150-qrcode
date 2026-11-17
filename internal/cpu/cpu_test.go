// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package cpu

import (
	"context"
	"errors"
	"runtime"
	"sync"
	"sync/atomic"
	"testing"
	"testing/synctest"

	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

func TestNewLimiterSize(t *testing.T) {
	for _, tc := range []struct{ n, want int }{{-3, 1}, {0, 1}, {1, 1}, {4, 4}} {
		if got := NewLimiter(tc.n).Size(); got != tc.want {
			t.Errorf("NewLimiter(%d).Size() = %d, want %d", tc.n, got, tc.want)
		}
	}
	var nilLimiter *Limiter
	if got := nilLimiter.Size(); got != 0 {
		t.Errorf("nil Limiter Size() = %d, want 0", got)
	}
}

// Many goroutines, few slots: never more holders than slots, and every slot back at the end.
func TestNeverMoreHoldersThanSlots(t *testing.T) {
	const slots, workers, rounds = 3, 64, 50
	l := NewLimiter(slots)
	var holders, peak atomic.Int64
	var wg sync.WaitGroup
	for range workers {
		wg.Go(func() {
			for range rounds {
				err := l.Do(t.Context(), func() {
					n := holders.Add(1)
					for {
						p := peak.Load()
						if n <= p || peak.CompareAndSwap(p, n) {
							break
						}
					}
					runtime.Gosched()
					holders.Add(-1)
				})
				if err != nil {
					t.Errorf("Do: %v", err)
					return
				}
			}
		})
	}
	wg.Wait()
	if p := peak.Load(); p > slots {
		t.Fatalf("peak holders = %d, want at most %d", p, slots)
	}
	if n := len(l.slots); n != 0 {
		t.Fatalf("%d slots still held after every Do returned", n)
	}
}

// A waiter whose context ends returns its error, holding nothing, and the holder's slot is intact.
func TestCancelledWaiterReturns(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		l := NewLimiter(1)
		if err := l.Acquire(t.Context()); err != nil {
			t.Fatalf("first Acquire: %v", err)
		}
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan error, 1)
		go func() { done <- l.Acquire(ctx) }()
		synctest.Wait() // the waiter is blocked on the full limiter
		select {
		case err := <-done:
			t.Fatalf("Acquire returned %v while the only slot was held", err)
		default:
		}
		cancel()
		if err := <-done; !errors.Is(err, context.Canceled) {
			t.Fatalf("cancelled Acquire = %v, want context.Canceled", err)
		}
		if n := len(l.slots); n != 1 {
			t.Fatalf("%d slots held, want 1 (the first holder's only)", n)
		}
		l.Release()
		if err := l.Acquire(t.Context()); err != nil {
			t.Fatalf("Acquire after Release: %v", err)
		}
		l.Release()
	})
}

func TestAcquireWithEndedContextTakesNothing(t *testing.T) {
	l := NewLimiter(2)
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if err := l.Acquire(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("Acquire = %v, want context.Canceled", err)
	}
	ran := false
	if err := l.Do(ctx, func() { ran = true }); !errors.Is(err, context.Canceled) || ran {
		t.Fatalf("Do = %v (ran %v), want context.Canceled without running", err, ran)
	}
	if n := len(l.slots); n != 0 {
		t.Fatalf("%d slots held, want 0", n)
	}
}

func TestReleaseWithoutAcquirePanics(t *testing.T) {
	l := NewLimiter(1)
	defer func() {
		if recover() == nil {
			t.Fatal("Release without Acquire did not panic")
		}
	}()
	l.Release()
}

func TestDoReleasesWhenFPanics(t *testing.T) {
	l := NewLimiter(1)
	func() {
		defer func() { _ = recover() }()
		_ = l.Do(t.Context(), func() { panic("boom") })
	}()
	if n := len(l.slots); n != 0 {
		t.Fatalf("%d slots held after a panicking f, want 0", n)
	}
}

func TestNilLimiterLimitsNothing(t *testing.T) {
	var l *Limiter
	for range 3 {
		if err := l.Acquire(t.Context()); err != nil {
			t.Fatalf("nil Acquire: %v", err)
		}
	}
	l.Release()
	ran := false
	if err := l.Do(t.Context(), func() { ran = true }); err != nil || !ran {
		t.Fatalf("nil Do = %v (ran %v)", err, ran)
	}
}
