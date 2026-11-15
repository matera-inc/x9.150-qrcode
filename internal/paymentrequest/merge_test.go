// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package paymentrequest

import (
	"encoding/json"
	"testing"

	"github.com/google/go-cmp/cmp"

	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
)

func decode[T any](t *testing.T, raw string) T {
	t.Helper()
	var v T
	if err := httpapi.Unmarshal([]byte(raw), &v); err != nil {
		t.Fatalf("%s: %v", raw, err)
	}
	return v
}

// asJSON is v as the GET writes it, decoded for comparison.
func asJSON(t *testing.T, v any) any {
	t.Helper()
	raw, err := withoutNulls(v)
	if err != nil {
		t.Fatal(err)
	}
	var out any
	if err := json.Unmarshal(raw, &out); err != nil {
		t.Fatal(err)
	}
	return out
}

const storedBill = `{"description": "old", "paymentTiming": "deferred",
	"order": {"number": "O-1", "date": "2030-01-02"},
	"invoice": {"number": "I-1", "date": "2030-01-01", "dueDate": "2030-06-30T00:00:00Z",
		"invoicee": {"name": "Ann", "phone": "+15550100", "email": "ann@example.com",
			"address": {"line1": "1 A St", "line2": "Apt 2", "city": "Austin", "state": "TX", "postalCode": "78701", "country": "US"}}},
	"tip": {"allowed": true, "presets": [10, 15]},
	"amountDue": {"amount": 1000, "currency": "USD", "adjustments": {"formula": "FixedDiscountLateFeeLinearInterest",
		"parameters": {"discounts": [{"daysBefore": 5, "discount": 10, "explanation": "early"}],
			"lateFees": {"fixed": 1, "perDay": 2, "explanation": "late"}}}}}`

// S9.7-AC1: a PATCH restates the bill. What it may leave out inside the order, the invoice, the
// tip and the adjustments keeps its stored value; what it leaves out of the bill itself is gone;
// the currency is always the stored one.
func TestMergeBill(t *testing.T) {
	for _, tc := range []struct {
		name, stored, patch, want string
	}{
		{"what is left out inside keeps its stored value", storedBill,
			`{"description": "new", "paymentTiming": "deferred", "order": {"number": "O-2"},
			  "invoice": {"number": "I-2", "date": "2030-01-03", "dueDate": "2030-07-01T00:00:00Z",
				"invoicee": {"name": "Bob", "address": {"line1": "9 Z St", "city": "Dallas", "country": "US"}}},
			  "tip": {"range": {"min": 1, "max": 20}},
			  "amountDue": {"amount": 900, "adjustments": {"formula": "FixedDiscountLateFeeLinearInterest",
				"parameters": {"lateFees": {"fixed": 3, "perDay": 4, "explanation": "later"}}}}}`,
			`{"description": "new", "paymentTiming": "deferred", "order": {"number": "O-2", "date": "2030-01-02"},
			  "invoice": {"number": "I-2", "date": "2030-01-03", "dueDate": "2030-07-01T00:00:00Z",
				"invoicee": {"name": "Bob", "phone": "+15550100", "email": "ann@example.com",
				  "address": {"line1": "9 Z St", "line2": "Apt 2", "city": "Dallas", "state": "TX", "postalCode": "78701", "country": "US"}}},
			  "tip": {"allowed": true, "range": {"min": 1, "max": 20}, "presets": [10, 15]},
			  "amountDue": {"amount": 900, "currency": "USD", "adjustments": {"formula": "FixedDiscountLateFeeLinearInterest",
				"parameters": {"discounts": [{"daysBefore": 5, "discount": 10, "explanation": "early"}],
				  "lateFees": {"fixed": 3, "perDay": 4, "explanation": "later"}}}}}`},
		{"null clears, and what the bill leaves out is gone", storedBill,
			`{"description": "new", "paymentTiming": "immediate",
			  "invoice": {"number": "I-1", "date": "2030-01-01", "dueDate": "2030-06-30T00:00:00Z", "invoicee": null},
			  "tip": {"presets": null, "range": {"min": 1, "max": 2}},
			  "amountDue": {"amount": 900, "adjustments": {"formula": "FixedDiscountLateFeeLinearInterest",
				"parameters": {"discounts": [], "lateFees": {"fixed": 1, "perDay": 2, "explanation": "late"}}}}}`,
			`{"description": "new", "paymentTiming": "immediate",
			  "invoice": {"number": "I-1", "date": "2030-01-01", "dueDate": "2030-06-30T00:00:00Z"},
			  "tip": {"allowed": true, "range": {"min": 1, "max": 2}},
			  "amountDue": {"amount": 900, "currency": "USD", "adjustments": {"formula": "FixedDiscountLateFeeLinearInterest",
				"parameters": {"lateFees": {"fixed": 1, "perDay": 2, "explanation": "late"}}}}}`},
		{"a tip not allowed, or left out, is no tip; adjustments null are none", storedBill,
			`{"description": "d", "paymentTiming": "immediate", "tip": {"allowed": false}, "amountDue": {"amount": 5, "adjustments": null}}`,
			`{"description": "d", "paymentTiming": "immediate", "amountDue": {"amount": 5, "currency": "USD"}}`},
		{"what the stored bill lacks is taken as sent (Java drops it)",
			`{"description": "old", "amountDue": {"amount": 1000, "currency": "USDC"}}`,
			`{"description": "d", "paymentTiming": "deferred", "order": {"number": "O-1"},
			  "invoice": {"number": "I-1", "date": "2030-01-01", "dueDate": "2030-06-30T00:00:00Z", "invoicee": {"name": "Ann"}},
			  "tip": {"presets": [5]},
			  "amountDue": {"amount": 5, "adjustments": {"formula": "FixedDiscountLateFeeLinearInterest",
				"parameters": {"lateFees": {"fixed": 1, "perDay": 2, "explanation": "late"}}}}}`,
			`{"description": "d", "paymentTiming": "deferred", "order": {"number": "O-1"},
			  "invoice": {"number": "I-1", "date": "2030-01-01", "dueDate": "2030-06-30T00:00:00Z", "invoicee": {"name": "Ann"}},
			  "tip": {"allowed": true, "presets": [5]},
			  "amountDue": {"amount": 5, "currency": "USDC", "adjustments": {"formula": "FixedDiscountLateFeeLinearInterest",
				"parameters": {"lateFees": {"fixed": 1, "perDay": 2, "explanation": "late"}}}}}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got := mergeBill(decode[contract.Bill](t, tc.stored), decode[contract.PatchBill](t, tc.patch))
			if diff := cmp.Diff(asJSON(t, decode[contract.Bill](t, tc.want)), asJSON(t, got)); diff != "" {
				t.Errorf("(-want +got):\n%s", diff)
			}
		})
	}
}

// S9.7-AC2: methods merge by currency: validUntil and amount as sent; editable when sent, its range
// kept when left out; each rail sent replaces the stored one, null removes it, the others stay.
func TestMergeMethod(t *testing.T) {
	const stored = `{"currency": "USD", "validUntil": "2030-12-31T00:00:00Z", "amount": 100,
		"editable": {"range": {"min": 1, "max": 200}},
		"networks": {"fednow": {"routingNumber": "021000021", "accountNumber": "1111", "protectionType": "plaintext"},
			"rtp": {"routingNumber": "021000021", "accountNumber": "2222", "protectionType": "plaintext"}}}`
	for _, tc := range []struct {
		name, patch, want string
	}{
		{"editable and rails left out are kept",
			`{"currency": "USD", "validUntil": "2030-11-30T00:00:00Z", "amount": 50, "networks": {}}`,
			`{"currency": "USD", "validUntil": "2030-11-30T00:00:00Z", "amount": 50,
			  "editable": {"range": {"min": 1, "max": 200}},
			  "networks": {"fednow": {"routingNumber": "021000021", "accountNumber": "1111", "protectionType": "plaintext"},
				"rtp": {"routingNumber": "021000021", "accountNumber": "2222", "protectionType": "plaintext"}}}`},
		{"a rail sent replaces, null removes; editable without range keeps it",
			`{"currency": "USD", "validUntil": "2030-11-30T00:00:00Z", "amount": 50, "editable": {},
			  "networks": {"rtp": null, "ach": {"routingNumber": "021000021", "accountNumber": "3333", "protectionType": "plaintext"}}}`,
			`{"currency": "USD", "validUntil": "2030-11-30T00:00:00Z", "amount": 50,
			  "editable": {"range": {"min": 1, "max": 200}},
			  "networks": {"fednow": {"routingNumber": "021000021", "accountNumber": "1111", "protectionType": "plaintext"},
				"ach": {"routingNumber": "021000021", "accountNumber": "3333", "protectionType": "plaintext"}}}`},
		{"editable null is not editable",
			`{"currency": "USD", "validUntil": "2030-11-30T00:00:00Z", "amount": 50, "editable": null, "networks": {}}`,
			`{"currency": "USD", "validUntil": "2030-11-30T00:00:00Z", "amount": 50,
			  "networks": {"fednow": {"routingNumber": "021000021", "accountNumber": "1111", "protectionType": "plaintext"},
				"rtp": {"routingNumber": "021000021", "accountNumber": "2222", "protectionType": "plaintext"}}}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got := mergeMethod(decode[contract.PaymentMethod](t, stored), decode[contract.PaymentMethod](t, tc.patch))
			if diff := cmp.Diff(asJSON(t, decode[contract.PaymentMethod](t, tc.want)), asJSON(t, got)); diff != "" {
				t.Errorf("(-want +got):\n%s", diff)
			}
		})
	}
}
