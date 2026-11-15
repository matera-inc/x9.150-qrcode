// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package clock

import (
	"context"
	"errors"
	"testing"
	"testing/synctest"
	"time"
)

func TestSleepWaitsTheRealDuration(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		start := time.Now()
		if err := Sleep(t.Context(), 30*time.Second); err != nil {
			t.Fatal(err)
		}
		if got := time.Since(start); got != 30*time.Second {
			t.Fatalf("slept %v, want 30s", got)
		}
	})
}

func TestSleepEndsWithItsContext(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan error, 1)
		go func() { done <- Sleep(ctx, time.Hour) }()
		synctest.Wait()
		cancel()
		if err := <-done; !errors.Is(err, context.Canceled) {
			t.Fatalf("err = %v, want context.Canceled", err)
		}
	})
}

// Moving the business clock does not move a real-duration wait.
func TestSleepIgnoresTheOffset(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		c := New(AllowOffset())
		if err := c.SetOffset(time.Hour); err != nil {
			t.Fatal(err)
		}
		start := time.Now()
		if err := Sleep(t.Context(), time.Second); err != nil {
			t.Fatal(err)
		}
		if got := time.Since(start); got != time.Second {
			t.Fatalf("slept %v, want 1s", got)
		}
	})
}
