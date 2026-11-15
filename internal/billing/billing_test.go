// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package billing

import (
	"errors"
	"testing"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

// now is the instant the rule tests judge at; nothing reads the wall clock.
var now = time.Date(2030, 1, 15, 12, 0, 0, 0, time.UTC)

const eighteenNines = MaxAmount

func fednow() *BankAddress {
	return &BankAddress{RoutingNumber: "021000021", AccountNumber: "1234567890"}
}

// valid is a request CheckCreate accepts at now: 10000 USD, one FedNow method, valid for 30 days.
func valid() Request {
	until := now.Add(30 * day)
	return Request{
		ValidUntil: until,
		Bill: Bill{
			AmountDue:     AmountDue{Amount: 10_000, Currency: "USD"},
			PaymentTiming: "immediate",
		},
		PaymentMethods: []PaymentMethod{{
			Currency: "USD", ValidUntil: until, Amount: 10_000,
			Networks: Networks{FedNow: fednow()},
		}},
	}
}

// withInvoice adds an invoice due at due, with a late fee of fixed + perDay per day.
func withInvoice(r Request, due time.Time, fixed, perDay int64, discounts ...Discount) Request {
	r.Bill.Invoice = &Invoice{Date: due.Add(-10 * day), DueDate: due}
	r.Bill.AmountDue.Adjustment = &Adjustment{
		Formula:   FormulaFixedDiscountLateFeeLinearInterest,
		Discounts: discounts,
		LateFees:  &LateFees{Fixed: fixed, PerDay: perDay, Explanation: "Late fee"},
	}
	return r
}

// violation returns err's text and kind, failing the test when err is not a *Violation.
func violation(t *testing.T, err error) (string, Kind) {
	t.Helper()
	var v *Violation
	if !errors.As(err, &v) {
		t.Fatalf("got %v (%T), want a *Violation", err, err)
	}
	return v.Error(), v.Kind
}

func TestViolationText(t *testing.T) {
	cases := []struct {
		v    Violation
		want string
	}{
		{Violation{Kind: BusinessRule, Field: "validUntil", Message: "must not be less than the current date."},
			"validUntil: must not be less than the current date."},
		{Violation{Kind: Invalid, Message: "Discount value must be greater than 0."}, "Discount value must be greater than 0."},
	}
	for _, c := range cases {
		if got := c.v.Error(); got != c.want {
			t.Errorf("Error() = %q, want %q", got, c.want)
		}
	}
	if Invalid.String() != "validation-error" || BusinessRule.String() != "business-rule-violation" {
		t.Errorf("kinds name the wrong problem types: %s, %s", Invalid, BusinessRule)
	}
}
