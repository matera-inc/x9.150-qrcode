// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package billing

import (
	"math/big"
	"time"
)

const (
	day         = 24 * time.Hour
	secondsADay = int64(day / time.Second)
)

var (
	nanosAMilli = big.NewInt(int64(time.Millisecond))
	millisADay  = big.NewInt(int64(day / time.Millisecond))
)

// Price is an adjusted bill amount and the instant it stops applying: Java's FormulaResultDTO.
type Price struct {
	// Amount is the bill's amount with the adjustment applied.
	Amount int64
	// Adjustment is the signed change: negative for a discount, the fee for a late fee.
	Adjustment  int64
	Explanation string
	// Currency is the bill's currency.
	Currency string
	// ValidUntil is the exact instant the adjustment's rule changes (a discount's edge, the next
	// late-fee boundary): what the payee signs next to the amount is what it accepts, up to and
	// including that instant and not one millisecond after (INTERPRETATION I-14).
	ValidUntil time.Time
}

// CappedAt is the same price advertised no later than limit: an adjusted amount cannot outlive the
// QR Code or the payment methods it is quoted for.
func (p Price) CappedAt(limit time.Time) Price {
	if !limit.Before(p.ValidUntil) {
		return p
	}
	p.ValidUntil = limit
	return p
}

// Adjust prices bill at exactly the instant at with the FixedDiscountLateFeeLinearInterest
// formula, as Java's FixedDiscountLateFeeLinearInterestFormulaService does. It returns nil when no
// adjustment applies: the bill has none (or no invoice to date it), it is on time and no discount
// is eligible, or the QR Code expires on or before its due date.
//
// feeStopsAt is the QR Code's validUntil: the late fee does not grow past it (ADR-0005). The zero
// time leaves the fee unbounded in time; the result is still refused past 18 digits.
//
//   - After dueDate, k days late with k = ceil((t − dueDate) / 24 h) on the difference truncated
//     to the millisecond, t being at or feeStopsAt, whichever is earlier: the fee is fixed +
//     perDay × k, applying up to and including dueDate + k × 24 h. A refusal, never a wrapped
//     number, when the amount with it passes 18 digits.
//   - At or before dueDate: the largest discount whose edge dueDate − daysBefore × 24 h is not
//     before at (the first of equal ones), applying up to and including that edge. At exactly
//     dueDate none is eligible, since daysBefore is positive, and the full amount is owed (I-13).
func Adjust(bill Bill, feeStopsAt, at time.Time) (*Price, error) {
	adj := bill.AmountDue.Adjustment
	if adj == nil || adj.LateFees == nil || bill.Invoice == nil {
		return nil, nil
	}
	due := bill.Invoice.DueDate
	original := bill.AmountDue.Amount

	if at.After(due) {
		feeAt := at
		if !feeStopsAt.IsZero() && at.After(feeStopsAt) {
			feeAt = feeStopsAt
		}
		if !feeAt.After(due) {
			return nil, nil
		}
		days := daysLate(due, feeAt)
		fee := lateFee(*adj.LateFees, days)
		amount, err := require18(new(big.Int).Add(fee, big.NewInt(original)))
		if err != nil {
			return nil, err
		}
		feeAmount, err := require18(fee)
		if err != nil {
			return nil, err
		}
		return &Price{
			Amount:      amount,
			Adjustment:  feeAmount,
			Explanation: adj.LateFees.Explanation,
			Currency:    bill.AmountDue.Currency,
			ValidUntil:  feeBoundary(due, feeAt, days),
		}, nil
	}

	var best *Discount
	var bestEdge time.Time
	for i := range adj.Discounts {
		d := &adj.Discounts[i]
		edge := discountEdge(due, d.DaysBefore)
		if at.After(edge) {
			continue
		}
		if best == nil || d.Discount > best.Discount {
			best, bestEdge = d, edge
		}
	}
	if best == nil {
		return nil, nil
	}
	return &Price{
		Amount:      original - best.Discount,
		Adjustment:  -best.Discount,
		Explanation: best.Explanation,
		Currency:    bill.AmountDue.Currency,
		ValidUntil:  bestEdge,
	}, nil
}

// discountEdge is E = dueDate − daysBefore × 24 h, in dueDate's offset: the last instant the
// discount applies.
func discountEdge(due time.Time, daysBefore int32) time.Time {
	return time.Unix(due.Unix()-int64(daysBefore)*secondsADay, int64(due.Nanosecond())).In(due.Location())
}

// daysLate is ceil((at − due) / 24 h) on the difference truncated to the millisecond, as Java's
// LateFeeBound.daysLate counts it; zero on or before due. Exact for any two instants.
func daysLate(due, at time.Time) *big.Int {
	if !at.After(due) {
		return new(big.Int)
	}
	ms := new(big.Int).Quo(nanosBetween(due, at), nanosAMilli)
	days, rem := new(big.Int).QuoRem(ms, millisADay, new(big.Int))
	if rem.Sign() != 0 {
		days.Add(days, big.NewInt(1))
	}
	return days
}

// nanosBetween is at − from in nanoseconds, exactly, however far apart they are.
func nanosBetween(from, at time.Time) *big.Int {
	secs := new(big.Int).Sub(big.NewInt(at.Unix()), big.NewInt(from.Unix()))
	secs.Mul(secs, big.NewInt(int64(time.Second)))
	return secs.Add(secs, big.NewInt(int64(at.Nanosecond()-from.Nanosecond())))
}

// lateFee is fixed + perDay × days, exactly.
func lateFee(fees LateFees, days *big.Int) *big.Int {
	fee := new(big.Int).Mul(big.NewInt(fees.PerDay), days)
	return fee.Add(fee, big.NewInt(fees.Fixed))
}

// feeBoundary is dueDate + days × 24 h, in dueDate's offset: the last instant the fee for that many
// days applies. days is daysLate(due, at), so the boundary lies within a day of at, and it is
// reached from at without any arithmetic that could overflow.
func feeBoundary(due, at time.Time, days *big.Int) time.Time {
	gap := new(big.Int).Mul(days, big.NewInt(int64(day)))
	gap.Sub(gap, nanosBetween(due, at))
	return at.Add(time.Duration(gap.Int64())).In(due.Location())
}

// LargestLateFee is the largest late fee the bill can show before validUntil: the fee owed at
// validUntil itself, with the number of days it counts. Zero when the bill has no late fee or no
// invoice, or when the QR Code expires on or before its due date, since nothing is charged, not
// even the fixed part, until strictly after it.
func LargestLateFee(bill Bill, validUntil time.Time) (fee, days *big.Int) {
	adj := bill.AmountDue.Adjustment
	if adj == nil || adj.LateFees == nil || bill.Invoice == nil || !validUntil.After(bill.Invoice.DueDate) {
		return new(big.Int), new(big.Int)
	}
	days = daysLate(bill.Invoice.DueDate, validUntil)
	return lateFee(*adj.LateFees, days), days
}
