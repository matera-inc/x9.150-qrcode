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

// Many goroutines, few slots, each one also nesting a Hold and stepping Outside: never more
// holders than slots, and every slot back at the end.
func TestNeverMoreHoldersThanSlots(t *testing.T) {
	const slots, workers, rounds = 3, 64, 50
	l := NewLimiter(slots)
	var holders, peak atomic.Int64
	work := func() {
		n := holders.Add(1)
		for {
			p := peak.Load()
			if n <= p || peak.CompareAndSwap(p, n) {
				break
			}
		}
		runtime.Gosched()
		holders.Add(-1)
	}
	var wg sync.WaitGroup
	for range workers {
		wg.Go(func() {
			for range rounds {
				ctx, release, err := l.Hold(t.Context())
				if err != nil {
					t.Errorf("Hold: %v", err)
					return
				}
				work()
				if err := Outside(ctx, func() error { runtime.Gosched(); return nil }); err != nil {
					t.Errorf("Outside: %v", err)
				}
				_, innerRelease, err := l.Hold(ctx) // nested: takes nothing
				if err != nil {
					t.Errorf("nested Hold: %v", err)
				}
				work()
				innerRelease()
				work() // still held: the nested release gave nothing back
				release()
			}
		})
	}
	wg.Wait()
	if p := peak.Load(); p > slots {
		t.Fatalf("peak holders = %d, want at most %d", p, slots)
	}
	if n := len(l.slots); n != 0 {
		t.Fatalf("%d slots still held after every release", n)
	}
}

// A nested Hold on a limiter of 1 does not wait for the slot its own context holds.
func TestNestedHoldDoesNotDeadlock(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		l := NewLimiter(1)
		ctx, release, err := l.Hold(t.Context())
		if err != nil {
			t.Fatal(err)
		}
		done := make(chan error, 1)
		go func() {
			_, innerRelease, err := l.Hold(ctx)
			innerRelease()
			done <- err
		}()
		synctest.Wait()
		select {
		case err := <-done:
			if err != nil {
				t.Fatal(err)
			}
		default:
			t.Fatal("a nested Hold waited for the slot its own context holds")
		}
		if n := len(l.slots); n != 1 {
			t.Fatalf("%d slots held, want 1", n)
		}
		release()
		release() // a second call gives back nothing
		if n := len(l.slots); n != 0 {
			t.Fatalf("%d slots held after release, want 0", n)
		}
	})
}

// Outside gives the slot back while f runs, so another goroutine can take it, and takes it again.
func TestOutsideReleasesWhileFRuns(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		l := NewLimiter(1)
		ctx, release, err := l.Hold(t.Context())
		if err != nil {
			t.Fatal(err)
		}
		defer release()
		want := errors.New("the fetch failed")
		err = Outside(ctx, func() error {
			if len(l.slots) != 0 {
				t.Error("the slot is held while f runs")
			}
			if err := l.Acquire(t.Context()); err != nil { // another request takes it meanwhile
				t.Error(err)
				return nil
			}
			l.Release()
			return want
		})
		if !errors.Is(err, want) {
			t.Fatalf("Outside = %v, want f's error", err)
		}
		if n := len(l.slots); n != 1 {
			t.Fatalf("%d slots held after Outside, want 1 (taken again)", n)
		}
	})
}

// Outside on a context that holds no slot just runs f.
func TestOutsideWithoutAHold(t *testing.T) {
	l := NewLimiter(1)
	ran := false
	if err := Outside(t.Context(), func() error { ran = true; return nil }); err != nil || !ran {
		t.Fatalf("Outside = %v (ran %v)", err, ran)
	}
	if n := len(l.slots); n != 0 {
		t.Fatalf("%d slots held", n)
	}
}

// A request that ends while Outside waits to take its slot again gets ErrGaveUp and holds nothing;
// its release then gives back nothing.
func TestOutsideGivesUpWhenItsContextEnds(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		l := NewLimiter(1)
		ctx, cancel := context.WithCancel(t.Context())
		held, release, err := l.Hold(ctx)
		if err != nil {
			t.Fatal(err)
		}
		other := make(chan struct{})
		done := make(chan error, 1)
		go func() {
			done <- Outside(held, func() error {
				// Another request, which does not end, takes the slot.
				if err := l.Acquire(context.WithoutCancel(held)); err != nil {
					t.Error(err)
				}
				close(other)
				return nil
			})
		}()
		<-other
		synctest.Wait() // Outside waits for the slot the other request holds
		cancel()
		if err := <-done; !errors.Is(err, ErrGaveUp) || !errors.Is(err, context.Canceled) {
			t.Fatalf("Outside = %v, want ErrGaveUp for context.Canceled", err)
		}
		release() // nothing to give back
		if n := len(l.slots); n != 1 {
			t.Fatalf("%d slots held, want 1 (the other request's)", n)
		}
		l.Release()
	})
}

// A waiter whose context ends returns ErrGaveUp, holding nothing, and the holder's slot is intact.
func TestCancelledWaiterReturns(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		l := NewLimiter(1)
		if err := l.Acquire(t.Context()); err != nil {
			t.Fatalf("first Acquire: %v", err)
		}
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan error, 1)
		go func() {
			_, release, err := l.Hold(ctx)
			release()
			done <- err
		}()
		synctest.Wait()
		select {
		case err := <-done:
			t.Fatalf("Hold returned %v while the only slot was held", err)
		default:
		}
		cancel()
		if err := <-done; !errors.Is(err, ErrGaveUp) || !errors.Is(err, context.Canceled) {
			t.Fatalf("cancelled Hold = %v, want ErrGaveUp for context.Canceled", err)
		}
		if n := len(l.slots); n != 1 {
			t.Fatalf("%d slots held, want 1 (the first holder's only)", n)
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
	if _, _, err := l.Hold(ctx); !errors.Is(err, ErrGaveUp) {
		t.Fatalf("Hold = %v, want ErrGaveUp", err)
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

func TestNilLimiterLimitsNothing(t *testing.T) {
	var l *Limiter
	for range 3 {
		if err := l.Acquire(t.Context()); err != nil {
			t.Fatalf("nil Acquire: %v", err)
		}
	}
	l.Release()
	ctx, release, err := l.Hold(t.Context())
	if err != nil {
		t.Fatalf("nil Hold: %v", err)
	}
	ran := false
	if err := Outside(ctx, func() error { ran = true; return nil }); err != nil || !ran {
		t.Fatalf("Outside under a nil Limiter = %v (ran %v)", err, ran)
	}
	release()
}
