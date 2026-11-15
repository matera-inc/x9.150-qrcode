// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package billing

import (
	"testing"
	"time"
)

// span is the instants a JSON date-time can carry, years 0000 to 9999, in milliseconds.
const (
	minMillis = -62_167_219_200_000
	maxMillis = 253_402_300_799_999
)

func instant(ms int64) time.Time {
	ms = minMillis + mod(ms, maxMillis-minMillis+1)
	return time.UnixMilli(ms).UTC()
}

func mod(a, m int64) int64 {
	r := a % m
	if r < 0 {
		r += m
	}
	return r
}

// amountIn maps any int64 onto [lo, MaxAmount].
func amountIn(v, lo int64) int64 { return lo + mod(v, MaxAmount-lo+1) }

// S9.4-AC5: whatever the amounts and instants, the formula never overflows or panics. Its answer
// is either a refusal or an amount within 18 digits whose adjustment is exactly the difference, a
// discount until an edge not before the instant, or a fee until a boundary less than a day after
// it. And once a bill has passed the late-fee bound at creation (ADR-0005), no instant makes the
// payload refuse it.
func FuzzAdjust(f *testing.F) {
	f.Add(int64(10_000), int64(0), int64(100), int64(1_000), int32(10), int64(1_900_000_000_000), int64(1_900_000_000_001), int64(1_900_500_000_000), int64(10_000))
	f.Add(MaxAmount, MaxAmount, MaxAmount, MaxAmount-1, int32(1), int64(0), int64(maxMillis), int64(-1), MaxAmount)
	f.Add(int64(1), int64(0), int64(1), int64(0), int32(2_147_483_647), int64(1), int64(0), int64(1), int64(0))
	f.Fuzz(func(t *testing.T, amount, fixed, daily, off int64, daysBefore int32, dueMs, atMs, untilMs, methodAmount int64) {
		amount = amountIn(amount, 1)
		due, at, until := instant(dueMs), instant(atMs), instant(untilMs)
		discounts := []Discount{}
		if off = amountIn(off, 0); off > 0 && off < amount && daysBefore > 0 {
			discounts = append(discounts, Discount{DaysBefore: daysBefore, Discount: off, Explanation: "early"})
		}
		bill := Bill{
			Invoice: &Invoice{Date: due, DueDate: due},
			AmountDue: AmountDue{Amount: amount, Currency: "USD", Adjustment: &Adjustment{
				Formula: FormulaFixedDiscountLateFeeLinearInterest, Discounts: discounts,
				LateFees: &LateFees{Fixed: amountIn(fixed, 0), PerDay: amountIn(daily, 0), Explanation: "late"},
			}},
		}
		for _, stop := range []time.Time{{}, until} {
			p, err := Adjust(bill, stop, at)
			if err != nil || p == nil {
				continue
			}
			if p.Amount < 0 || p.Amount > MaxAmount || p.Adjustment < -MaxAmount || p.Adjustment > MaxAmount {
				t.Fatalf("out of 18 digits: %+v", p)
			}
			if p.Amount-p.Adjustment != amount {
				t.Fatalf("%d − %d is not the bill's %d", p.Amount, p.Adjustment, amount)
			}
			switch {
			case p.Adjustment < 0 && p.ValidUntil.Before(at):
				t.Fatalf("a discount at %s advertised until %s", at, p.ValidUntil)
			case p.Adjustment >= 0 && (p.ValidUntil.Before(due) || p.ValidUntil.After(at.Add(day))):
				t.Fatalf("a fee at %s advertised until %s (due %s)", at, p.ValidUntil, due)
			}
			// A refusal is an answer; only a panic is not.
			_, _ = MethodAmount(amountIn(methodAmount, 0), p)
		}

		// ADR-0005: a bill accepted by the bound is served at every instant.
		req := Request{ValidUntil: until, Bill: bill, PaymentMethods: []PaymentMethod{{
			Currency: "USD", ValidUntil: until, Amount: amountIn(methodAmount, 0), Networks: Networks{FedNow: fednow()},
		}}}
		if !until.Before(due) && CheckLateFeeBound(req) == nil {
			if _, err := QuoteAt(req, at); err != nil {
				t.Fatalf("accepted at creation, refused at %s: %v", at, err)
			}
		}
	})
}
