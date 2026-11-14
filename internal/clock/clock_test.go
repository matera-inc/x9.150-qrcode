// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package clock

import (
	"errors"
	"testing"
	"testing/synctest"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

// S7.4-AC1: the clock is the real time, in UTC, with no offset.
func TestNowIsRealTimeInUTC(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		c := New()
		if got, want := c.Now(), time.Now().UTC(); !got.Equal(want) || got.Location() != time.UTC {
			t.Fatalf("Now = %v, want %v in UTC", got, want)
		}
		if c.Offset() != 0 {
			t.Fatalf("offset = %v", c.Offset())
		}
	})
}

// S7.4-AC1: the offset stays disabled in this build, so a production clock cannot be moved.
func TestOffsetIsDisabledByDefault(t *testing.T) {
	c := New()
	if c.OffsetAllowed() {
		t.Fatal("the offset is allowed by default")
	}
	if err := c.SetOffset(time.Hour); !errors.Is(err, ErrOffsetDisabled) {
		t.Fatalf("SetOffset err = %v, want ErrOffsetDisabled", err)
	}
	if err := c.SetNow(time.Date(2031, 1, 1, 0, 0, 0, 0, time.UTC)); !errors.Is(err, ErrOffsetDisabled) {
		t.Fatalf("SetNow err = %v, want ErrOffsetDisabled", err)
	}
	if c.Offset() != 0 {
		t.Fatalf("a refused SetOffset moved the clock by %v", c.Offset())
	}
}

// S7.4-AC1: it supports an offset, to the millisecond, and keeps ticking (ADR-0033).
func TestOffsetMovesNowAndKeepsTicking(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		c := New(AllowOffset())
		if err := c.SetOffset(36*time.Hour + 1500*time.Microsecond); err != nil {
			t.Fatal(err)
		}
		if c.Offset() != 36*time.Hour+time.Millisecond {
			t.Fatalf("offset = %v, want it truncated to the millisecond", c.Offset())
		}
		before := c.Now()
		if want := time.Now().Add(c.Offset()).UTC(); !before.Equal(want) {
			t.Fatalf("Now = %v, want %v", before, want)
		}
		time.Sleep(10 * time.Second)
		if got := c.Since(before); got != 10*time.Second {
			t.Fatalf("the clock did not keep ticking: Since = %v", got)
		}
		if got := c.Until(before.Add(time.Minute)); got != 50*time.Second {
			t.Fatalf("Until = %v", got)
		}
		if err := c.SetOffset(0); err != nil || c.Offset() != 0 {
			t.Fatalf("reset: err %v, offset %v", err, c.Offset())
		}
	})
}

func TestSetNow(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		c := New(AllowOffset())
		at := time.Date(2031, 12, 31, 23, 59, 59, 999_000_000, time.UTC)
		if err := c.SetNow(at); err != nil {
			t.Fatal(err)
		}
		if got := c.Now(); !got.Equal(at) {
			t.Fatalf("Now = %v, want %v", got, at)
		}
	})
}
