// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package billing

import (
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
)

// quoteRequest is a bill of 10000 USD due at formulaDue with a 100-a-day late fee, a USD method
// valid a day after the due date and a USDC method (micro-units: 100 × the cents) valid 3 days
// after it, on a QR Code valid 5 days after it.
func quoteRequest() Request {
	r := Request{
		ValidUntil: formulaDue.Add(5 * day),
		Bill: Bill{
			Invoice: &Invoice{Date: formulaDue.Add(-30 * day), DueDate: formulaDue},
			AmountDue: AmountDue{Amount: 10_000, Currency: "USD", Adjustment: &Adjustment{
				Formula:   FormulaFixedDiscountLateFeeLinearInterest,
				Discounts: []Discount{{DaysBefore: 10, Discount: 1_000, Explanation: "early"}},
				LateFees:  &LateFees{Fixed: 0, PerDay: 100, Explanation: "late"},
			}},
		},
		PaymentMethods: []PaymentMethod{
			{Currency: "USD", ValidUntil: formulaDue.Add(day), Amount: 10_000, Networks: Networks{FedNow: fednow()}},
			{Currency: "USDC", ValidUntil: formulaDue.Add(3 * day), Amount: 1_000_000,
				Networks: Networks{Solana: &SolanaAddress{Recipient: "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"}}},
		},
	}
	return r
}

func quote(t *testing.T, r Request, at time.Time) Quote {
	t.Helper()
	q, err := QuoteAt(r, at)
	if err != nil {
		t.Fatalf("QuoteAt %s: %v", at, err)
	}
	return q
}

// S9.4-AC3: each method in a pegged currency is adjusted by the bill's ratio, as Java adjusts it
// since BUG-001's fix: a 10% discount is 10% of the USDC micro-units, not 1000 of them.
func TestQuoteCarriesTheAdjustmentByRatio(t *testing.T) {
	edge := formulaDue.Add(-10 * day)
	q := quote(t, quoteRequest(), edge.Add(-time.Hour))
	want := []QuotedMethod{{Index: 0, Amount: 9_000}, {Index: 1, Amount: 900_000}}
	if diff := cmp.Diff(want, q.Methods); diff != "" {
		t.Errorf("methods (-want +got):\n%s", diff)
	}
	wantPrice(t, q.Adjustment, 9_000, -1_000, edge)
}

// S9.4-AC3 (I-14, FR36): the methods shown are those valid at the pricing instant (a method's
// validUntil is inclusive), and the adjustment's validUntil is capped by the last of them and by the
// QR Code. Java's X9-TIME-007, -017.
func TestQuoteChoosesMethodsAtTheInstantItPrices(t *testing.T) {
	r := quoteRequest()
	usdEnds := r.PaymentMethods[0].ValidUntil
	usdcEnds := r.PaymentMethods[1].ValidUntil

	// At the USD method's last instant both are shown, one day late; the fee's boundary is that
	// same instant.
	q := quote(t, r, usdEnds)
	if diff := cmp.Diff([]QuotedMethod{{0, 10_100}, {1, 1_010_000}}, q.Methods); diff != "" {
		t.Errorf("at the USD method's validUntil (-want +got):\n%s", diff)
	}
	wantPrice(t, q.Adjustment, 10_100, 100, usdEnds)

	// 1 ms later the USD method is gone and the price is two days late.
	q = quote(t, r, usdEnds.Add(ms))
	if diff := cmp.Diff([]QuotedMethod{{1, 1_020_000}}, q.Methods); diff != "" {
		t.Errorf("1 ms past the USD method (-want +got):\n%s", diff)
	}
	wantPrice(t, q.Adjustment, 10_200, 200, formulaDue.Add(2*day))

	// Three days late, the fee's boundary is the USDC method's end: it caps nothing. Past it, no
	// method is shown and the QR Code's validUntil caps the price.
	q = quote(t, r, usdcEnds.Add(-time.Hour))
	wantPrice(t, q.Adjustment, 10_300, 300, usdcEnds)
	q = quote(t, r, usdcEnds.Add(time.Hour))
	if len(q.Methods) != 0 {
		t.Errorf("past every method: %+v", q.Methods)
	}
	wantPrice(t, q.Adjustment, 10_400, 400, formulaDue.Add(4*day))
	q = quote(t, r, r.ValidUntil.Add(-time.Hour))
	wantPrice(t, q.Adjustment, 10_500, 500, r.ValidUntil)
}

// FR36: a discount's edge later than the last method shown is capped by that method.
func TestQuoteCapsTheDiscountByTheMethods(t *testing.T) {
	r := quoteRequest()
	r.PaymentMethods = r.PaymentMethods[:1]
	r.PaymentMethods[0].ValidUntil = formulaDue.Add(-20 * day)
	q := quote(t, r, formulaDue.Add(-25*day))
	wantPrice(t, q.Adjustment, 9_000, -1_000, formulaDue.Add(-20*day))
}

// With no adjustment, every method keeps its own amount.
func TestQuoteWithoutAdjustment(t *testing.T) {
	r := quoteRequest()
	r.Bill.AmountDue.Adjustment = nil
	q := quote(t, r, formulaDue)
	if q.Adjustment != nil {
		t.Errorf("adjustment %+v", q.Adjustment)
	}
	if diff := cmp.Diff([]QuotedMethod{{0, 10_000}, {1, 1_000_000}}, q.Methods); diff != "" {
		t.Errorf("(-want +got):\n%s", diff)
	}
}

// ADR-0005: a method that the adjustment would carry past 18 digits is refused, not shown.
func TestQuoteRefusesAMethodPast18Digits(t *testing.T) {
	r := quoteRequest()
	r.PaymentMethods[1].Amount = MaxAmount
	_, err := QuoteAt(r, formulaDue.Add(ms))
	if text, _ := violation(t, err); text != msgTooManyDigits {
		t.Errorf("got %q", text)
	}
}

func TestMethodAmountRoundsHalfUp(t *testing.T) {
	cases := []struct {
		method int64
		price  *Price
		want   int64
	}{
		{7, nil, 7},
		{3, &Price{Amount: 9, Adjustment: -1}, 3},  // 2.7
		{5, &Price{Amount: 9, Adjustment: -1}, 5},  // 4.5, half up
		{1, &Price{Amount: 15, Adjustment: 5}, 2},  // 1.5, half up
		{1, &Price{Amount: 13, Adjustment: 3}, 1},  // 1.3
		{0, &Price{Amount: 13, Adjustment: 3}, 0},  // nothing to scale
		{42, &Price{Amount: 0, Adjustment: 0}, 42}, // no bill to scale from
		{MaxAmount, &Price{Amount: 9, Adjustment: -1}, 899_999_999_999_999_999},
		{1_000_000, &Price{Amount: 10_100, Adjustment: 100}, 1_010_000},
	}
	for _, c := range cases {
		got, err := MethodAmount(c.method, c.price)
		if err != nil || got != c.want {
			t.Errorf("MethodAmount(%d, %+v) = %d, %v; want %d", c.method, c.price, got, err, c.want)
		}
	}
	if got, err := MethodAmount(MaxAmount, &Price{Amount: 2, Adjustment: 1}); err == nil {
		t.Errorf("doubling 18 nines gave %d, want a refusal", got)
	}
}

// I-14: a payload is priced at the later of the payer's date and now.
func TestPricingInstant(t *testing.T) {
	later := now.Add(day)
	if got := PricingInstant(later, now); !got.Equal(later) {
		t.Errorf("a future date: %s", got)
	}
	if got := PricingInstant(now.Add(-day), now); !got.Equal(now) {
		t.Errorf("a past date: %s", got)
	}
	if got := PricingInstant(now, now); !got.Equal(now) {
		t.Errorf("now: %s", got)
	}
}
