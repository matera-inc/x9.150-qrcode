// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package billing

import (
	"math/big"
	"testing"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/clock"
)

// The formula's numbers, as Java's FixedDiscountLateFeeLinearInterestFormulaServiceTest has them.
const (
	face     = 10_000_000
	discount = 1_000_000
	perDay   = 500_000
)

// formulaDue is a due date with a time of day and milliseconds, so that no edge hides behind
// midnight, five years from the business clock's now, as Java's test takes it; in a non-UTC offset,
// so that the arithmetic is shown not to depend on it.
var formulaDue = func() time.Time {
	y, m, d := clock.New().Now().AddDate(5, 0, 0).Date()
	return time.Date(y, m, d, 14, 30, 15, 250_000_000, time.FixedZone("-03:00", -3*60*60))
}()

const ms = time.Millisecond

func formulaBill(discounts ...Discount) Bill {
	return Bill{
		Invoice: &Invoice{Date: formulaDue.Add(-60 * day), DueDate: formulaDue},
		AmountDue: AmountDue{Amount: face, Currency: "USDC", Adjustment: &Adjustment{
			Formula: FormulaFixedDiscountLateFeeLinearInterest, Discounts: discounts,
			LateFees: &LateFees{Fixed: 0, PerDay: perDay, Explanation: "late"},
		}},
	}
}

func adjustAt(t *testing.T, b Bill, at time.Time) *Price {
	t.Helper()
	p, err := Adjust(b, time.Time{}, at)
	if err != nil {
		t.Fatalf("Adjust at %s: %v", at, err)
	}
	return p
}

// priceAt is adjustAt for an instant at which an adjustment must apply.
func priceAt(t *testing.T, b Bill, at time.Time) *Price {
	t.Helper()
	p := adjustAt(t, b, at)
	if p == nil {
		t.Fatalf("no adjustment at %s", at)
	}
	return p
}

func wantPrice(t *testing.T, p *Price, amount, adjustment int64, until time.Time) {
	t.Helper()
	if p == nil {
		t.Fatalf("no adjustment; want %d until %s", amount, until)
	}
	if p.Amount != amount || p.Adjustment != adjustment || !p.ValidUntil.Equal(until) {
		t.Errorf("got %d (%+d) until %s, want %d (%+d) until %s",
			p.Amount, p.Adjustment, p.ValidUntil, amount, adjustment, until)
	}
}

// S9.4-AC3 (I-13): at exactly dueDate the full amount is owed, and 1 ms later the first day's fee.
// Java's X9-AMT-501 to -504.
func TestTheDueDateIsOnTime(t *testing.T) {
	early := Discount{DaysBefore: 10, Discount: discount, Explanation: "early"}
	if p := adjustAt(t, formulaBill(early), formulaDue); p != nil {
		t.Errorf("at dueDate: %+v, want the full amount", p)
	}
	if p := adjustAt(t, formulaBill(), formulaDue); p != nil {
		t.Errorf("at dueDate with no discount: %+v, want the full amount", p)
	}
	if p := adjustAt(t, formulaBill(early), formulaDue.Add(-ms)); p != nil {
		t.Errorf("1 ms before dueDate, no discount covering it: %+v, want the full amount", p)
	}
	wantPrice(t, adjustAt(t, formulaBill(), formulaDue.Add(ms)), face+perDay, perDay, formulaDue.Add(day))
}

// S9.4-AC3 (I-14): a discount applies up to and including E = dueDate − daysBefore, is advertised
// until E itself, and not 1 ms after. Java's X9-AMT-505, -506.
func TestADiscountAppliesUpToAndIncludingItsEdge(t *testing.T) {
	b := formulaBill(Discount{DaysBefore: 10, Discount: discount, Explanation: "early"})
	edge := formulaDue.Add(-10 * day)
	wantPrice(t, adjustAt(t, b, edge.Add(-3*time.Hour)), face-discount, -discount, edge)
	wantPrice(t, adjustAt(t, b, edge), face-discount, -discount, edge)
	if p := adjustAt(t, b, edge.Add(ms)); p != nil {
		t.Errorf("1 ms past E: %+v, want the full amount", p)
	}
	if p := priceAt(t, b, edge); p.Explanation != "early" || p.Currency != "USDC" {
		t.Errorf("explanation %q, currency %q; want the discount's and the bill's", p.Explanation, p.Currency)
	}
}

// S9.4-AC3: of several discounts the largest eligible one wins, until its own edge, even when a
// smaller one would last longer; of equal ones the first. Java's X9-AMT-507, -508.
func TestTheLargestEligibleDiscountWins(t *testing.T) {
	large := Discount{DaysBefore: 30, Discount: 2 * discount, Explanation: "early"}
	small := Discount{DaysBefore: 10, Discount: discount, Explanation: "later"}
	b := formulaBill(small, large)
	largeEnds, smallEnds := formulaDue.Add(-30*day), formulaDue.Add(-10*day)
	wantPrice(t, adjustAt(t, b, largeEnds.Add(-time.Hour)), face-2*discount, -2*discount, largeEnds)
	wantPrice(t, adjustAt(t, b, largeEnds.Add(ms)), face-discount, -discount, smallEnds)

	lateButLarge := Discount{DaysBefore: 10, Discount: 3 * discount, Explanation: "large"}
	earlyButSmall := Discount{DaysBefore: 30, Discount: discount, Explanation: "small"}
	wantPrice(t, adjustAt(t, formulaBill(earlyButSmall, lateButLarge), formulaDue.Add(-40*day)),
		face-3*discount, -3*discount, formulaDue.Add(-10*day))

	first := Discount{DaysBefore: 20, Discount: discount, Explanation: "first"}
	second := Discount{DaysBefore: 10, Discount: discount, Explanation: "second"}
	if p := priceAt(t, formulaBill(first, second), formulaDue.Add(-25*day)); p.Explanation != "first" {
		t.Errorf("of equal discounts %q won, want the first", p.Explanation)
	}
}

// S9.4-AC3 (I-14): k days late the fee is k × perDay + fixed, advertised until dueDate + k × 24 h,
// applying at that boundary and rising 1 ms after. Java's X9-AMT-509, -510.
func TestTheLateFeeRisesAtEach24HourBoundary(t *testing.T) {
	b := formulaBill()
	wantPrice(t, adjustAt(t, b, formulaDue.Add(5*time.Hour)), face+perDay, perDay, formulaDue.Add(day))
	wantPrice(t, adjustAt(t, b, formulaDue.Add(2*day+time.Minute)), face+3*perDay, 3*perDay, formulaDue.Add(3*day))
	boundary := formulaDue.Add(day)
	wantPrice(t, adjustAt(t, b, boundary), face+perDay, perDay, boundary)
	wantPrice(t, adjustAt(t, b, boundary.Add(ms)), face+2*perDay, 2*perDay, formulaDue.Add(2*day))

	// The difference is truncated to the millisecond, as Java's Duration.truncatedTo(MILLIS): half
	// a millisecond past the boundary is still the same day.
	wantPrice(t, adjustAt(t, b, boundary.Add(500*time.Microsecond)), face+perDay, perDay, boundary)

	b.AmountDue.Adjustment.LateFees.Fixed = 700
	wantPrice(t, adjustAt(t, b, formulaDue.Add(ms)), face+700+perDay, 700+perDay, formulaDue.Add(day))
}

// S9.4-AC3: the advertised edge keeps the due date's offset, and is the same instant whatever it is.
func TestTheEdgeKeepsTheDueDatesOffset(t *testing.T) {
	p := priceAt(t, formulaBill(), formulaDue.Add(ms))
	if _, off := p.ValidUntil.Zone(); off != -3*60*60 {
		t.Errorf("offset %d, want the due date's (-03:00)", off)
	}
	utc := formulaBill()
	utc.Invoice.DueDate = formulaDue.UTC()
	if q := priceAt(t, utc, formulaDue.Add(ms)); !q.ValidUntil.Equal(p.ValidUntil) {
		t.Errorf("UTC due date gave %s, want %s", q.ValidUntil, p.ValidUntil)
	}
}

// S9.4-AC3, FR36: the advertised validUntil is capped by whatever ends sooner. Java's X9-AMT-511.
func TestCappedAt(t *testing.T) {
	edge := formulaDue.Add(-10 * day)
	p := priceAt(t, formulaBill(Discount{DaysBefore: 10, Discount: discount, Explanation: "early"}), edge.Add(-5*day))
	methodEnds := edge.Add(-2 * day)
	if got := p.CappedAt(methodEnds); !got.ValidUntil.Equal(methodEnds) || got.Amount != p.Amount {
		t.Errorf("capped at an earlier end: %+v", got)
	}
	if got := p.CappedAt(edge.Add(day)); !got.ValidUntil.Equal(edge) {
		t.Errorf("a later cap moved the edge to %s", got.ValidUntil)
	}
}

// S9.4-AC2, AC3 (ADR-0005): the late fee stops growing at the QR Code's validUntil, and a QR Code
// that expires at its due date never owes one. Java's X9-AMT-118.
func TestTheLateFeeStopsAtValidUntil(t *testing.T) {
	b := formulaBill()
	validUntil := formulaDue.Add(2*day + time.Hour)
	for _, at := range []time.Time{formulaDue.AddDate(40, 0, 0), validUntil} {
		p, err := Adjust(b, validUntil, at)
		if err != nil {
			t.Fatal(err)
		}
		wantPrice(t, p, face+3*perDay, 3*perDay, formulaDue.Add(3*day))
	}
	if p, err := Adjust(b, formulaDue, formulaDue.Add(day)); err != nil || p != nil {
		t.Errorf("a QR Code valid until its due date: %+v, %v; want no fee", p, err)
	}
}

// S9.4-AC2 (ADR-0005): the formula refuses rather than emits an amount past 18 digits, and never
// wraps. Java's X9-AMT-119.
func TestTheFormulaRefusesPast18Digits(t *testing.T) {
	huge := formulaBill()
	huge.AmountDue.Amount = 1
	huge.AmountDue.Adjustment.LateFees = &LateFees{Fixed: MaxAmount, PerDay: MaxAmount, Explanation: "late"}
	_, err := Adjust(huge, time.Time{}, formulaDue.AddDate(30, 0, 0))
	if text, kind := violation(t, err); text != msgTooManyDigits || kind != Invalid {
		t.Errorf("got %q (%s), want %q", text, kind, msgTooManyDigits)
	}

	fits := formulaBill()
	fits.AmountDue.Amount = MaxAmount - 1
	fits.AmountDue.Adjustment.LateFees = &LateFees{Fixed: 0, PerDay: 1, Explanation: "late"}
	wantPrice(t, adjustAt(t, fits, formulaDue.Add(time.Hour)), MaxAmount, 1, formulaDue.Add(day))
	if _, err := Adjust(fits, time.Time{}, formulaDue.Add(day+time.Hour)); err == nil {
		t.Error("one unit past 18 nines was served")
	}
}

// No adjustment, or no invoice to date it, is no price.
func TestNoAdjustment(t *testing.T) {
	b := formulaBill()
	b.AmountDue.Adjustment = nil
	if p := adjustAt(t, b, formulaDue.Add(day)); p != nil {
		t.Errorf("no adjustment priced %+v", p)
	}
	b = formulaBill()
	b.Invoice = nil
	if p := adjustAt(t, b, formulaDue.Add(day)); p != nil {
		t.Errorf("no invoice priced %+v", p)
	}
}

func TestDaysLate(t *testing.T) {
	cases := []struct {
		after time.Duration
		want  int64
	}{
		{0, 0}, {-time.Hour, 0}, {ms, 1}, {500 * time.Microsecond, 0}, {day, 1}, {day + ms, 2},
		{day + 999*time.Microsecond, 1}, {10 * day, 10}, {10*day + ms, 11},
	}
	for _, c := range cases {
		if got := daysLate(formulaDue, formulaDue.Add(c.after)); got.Cmp(big.NewInt(c.want)) != 0 {
			t.Errorf("daysLate(+%s) = %s, want %d", c.after, got, c.want)
		}
	}
	// Exact however far apart: ten thousand years.
	far := time.Date(9999, 12, 31, 23, 59, 59, 999_000_000, time.UTC)
	start := time.Date(1, 1, 1, 0, 0, 0, 0, time.UTC)
	if got := daysLate(start, far); got.Cmp(big.NewInt(3_652_059)) != 0 {
		t.Errorf("daysLate over the whole calendar = %s, want 3652059", got)
	}
}
