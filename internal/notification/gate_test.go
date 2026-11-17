// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package notification

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/paymentrequest"
)

// The acceptance gate of a pre-commit, at the payee's clock (INTERPRETATION I-13): the edges are
// inclusive, the tip is judged in the notified currency, and the amount is the total less the tip.
func TestGate(t *testing.T) {
	due := `,"invoice":{"date":"2029-01-01","dueDate":"2029-06-01T00:00:00Z"}`
	adjusted := due + `,"amountDue":{"amount":1000,"currency":"USDC","adjustments":{"formula":"FixedDiscountLateFeeLinearInterest",` +
		`"parameters":{"discounts":[{"daysBefore":5,"discount":100,"explanation":"early"}],"lateFees":{"fixed":50,"perDay":10,"explanation":"late"}}}}`
	tip := `,"tip":{"allowed":true,"range":{"min":10,"max":20}}`
	usdBill := `,"amountDue":{"amount":10,"currency":"USD"}`
	dueAt := time.Date(2029, 6, 1, 0, 0, 0, 0, time.UTC)

	cases := []struct {
		name          string
		bill, method  string
		at            time.Time
		amount, tip   int64
		field, saying string // "" accepts
	}{
		{name: "the face amount", amount: 1000},
		{name: "one unit off", amount: 999, field: fieldAmount, saying: "Expected 1000 USDC but the notification carries 999."},
		{name: "at validUntil exactly", at: validUntil, amount: 1000},
		{name: "a millisecond past validUntil", at: validUntil.Add(time.Millisecond), amount: 1000, field: "validUntil"},
		{name: "a method expired", method: `,"validUntil":"2029-01-01T00:00:00Z"`, at: time.Date(2029, 1, 1, 0, 0, 0, 1e6, time.UTC),
			amount: 1000, field: "paymentMethods.validUntil"},
		{name: "a tip on a bill that offers none", amount: 1100, tip: 100, field: fieldTipAmount, saying: "does not accept tips"},
		{name: "a tip in range", bill: tip, amount: 1150, tip: 150},
		{name: "a tip one unit past the range", bill: tip, amount: 1201, tip: 201},
		{name: "a tip two units past the range", bill: tip, amount: 1202, tip: 202, field: fieldTipAmount},
		{name: "a tip larger than the total", bill: tip, amount: 100, tip: 200, field: fieldTipAmount, saying: "larger than"},
		// R-I10-08: the percentages are taken in the notified currency (the method's 1000 USDC
		// units), not the bill's (10 USD cents): 15% of the method is accepted, 20% of the bill is
		// not.
		{name: "a tip in range of the notified currency, not the bill's", bill: usdBill + tip, amount: 1150, tip: 150},
		{name: "a tip in range of the bill's currency only", bill: usdBill + tip, amount: 1002, tip: 2, field: fieldTipAmount},
		{name: "editable within range", method: `,"editable":{"range":{"min":500,"max":2000}}`, amount: 700},
		{name: "editable out of range", method: `,"editable":{"range":{"min":500,"max":2000}}`, amount: 2001, field: fieldAmount},
		{name: "discounted at its edge", bill: adjusted, at: dueAt.Add(-5 * 24 * time.Hour), amount: 900},
		{name: "face while discounted", bill: adjusted, at: dueAt.Add(-5 * 24 * time.Hour), amount: 1000},
		{name: "discount one ms late", bill: adjusted, at: dueAt.Add(-5*24*time.Hour + time.Millisecond), amount: 900, field: fieldAmount},
		{name: "face when a late fee is owed", bill: adjusted, at: dueAt.Add(time.Millisecond), amount: 1000, field: fieldAmount,
			saying: "including the late fee"},
		{name: "with the late fee", bill: adjusted, at: dueAt.Add(time.Millisecond), amount: 1060},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			billJSON := c.bill
			if !strings.Contains(billJSON, "amountDue") {
				billJSON = `,"amountDue":{"amount":1000,"currency":"USDC"}` + billJSON
			}
			method := c.method
			if !strings.Contains(method, "validUntil") {
				method += `,"validUntil":"2030-01-01T00:00:00Z"`
			}
			raw := `{"validUntil":"2030-01-01T00:00:00Z","bill":{` + billJSON[1:] + `},"paymentMethods":[{"currency":"USDC",` +
				`"amount":1000` + method + `,"networks":{"solana":{"recipient":"` + recipient + `"}}}]}`
			req, err := paymentrequest.StoredRequest([]byte(raw))
			if err != nil {
				t.Fatal(err)
			}
			n := note([16]byte{1}, payment("amount", c.amount))
			if c.tip != 0 {
				n = note([16]byte{1}, payment("amount", c.amount), payment("tipAmount", c.tip))
			}
			b, _ := json.Marshal(n)
			var data contract.NotificationData
			if err := httpapi.Unmarshal(b, &data); err != nil {
				t.Fatal(err)
			}
			at := c.at
			if at.IsZero() {
				at = t0
			}
			r, err := Gate(domain.Row{ValidUntil: validUntil}, paymentrequest.BillingRequest(&req), &data, at)
			if err != nil {
				t.Fatal(err)
			}
			switch {
			case c.field == "" && r != nil:
				t.Fatalf("refused: %v", r)
			case c.field != "" && r == nil:
				t.Fatalf("accepted, want a refusal naming %s", c.field)
			case r != nil && (r.Field != c.field || !strings.Contains(r.Message, c.saying)):
				t.Errorf("refusal %v, want %s saying %q", r, c.field, c.saying)
			}
		})
	}
}
