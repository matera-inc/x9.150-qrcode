// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

import (
	"testing"

	"github.com/google/go-cmp/cmp"

	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
)

const msgIntegerNotInteger = "must be a whole number, written without a fraction or an exponent, but 6.5 was given"

// ADR-0005: every amount is an integer in minor units. Each integer member of a create, a PATCH and
// a notification (outbound and inbound), sent as 6.5, is refused naming that member: none is
// truncated to 6 (Jackson's ACCEPT_FLOAT_AS_INT, which the Java build applied).
func TestEveryIntegerMemberRefusesAFraction(t *testing.T) {
	type body struct {
		name, sample, old, new, want string
		read                         func() validator
	}
	create := func() validator { return &CreateRequest{} }
	patch := func() validator { return &PatchRequest{} }
	outbound := func() validator { return &OutboundNotification{} }
	amount := msgAmountNotInteger("6.5")
	cases := make([]body, 0, 20)
	for _, s := range []struct {
		sample string
		read   func() validator
		kind   string
	}{{sampleCreate, create, "create"}, {samplePatch, patch, "patch"}} {
		cases = append(cases,
			body{s.kind + " bill amount", s.sample, `"amountDue": {"amount": 1000`, `"amountDue": {"amount": 6.5`,
				"bill.amountDue.amount: " + amount, s.read},
			body{s.kind + " discount", s.sample, `"discount": 50`, `"discount": 6.5`,
				"bill.amountDue.adjustments.parameters.discounts[0].discount: " + amount, s.read},
			body{s.kind + " daysBefore", s.sample, `"daysBefore": 5`, `"daysBefore": 6.5`,
				"bill.amountDue.adjustments.parameters.discounts[0].daysBefore: " + msgIntegerNotInteger, s.read},
			body{s.kind + " late fee fixed", s.sample, `"fixed": 100`, `"fixed": 6.5`,
				"bill.amountDue.adjustments.parameters.lateFees.fixed: " + amount, s.read},
			body{s.kind + " late fee perDay", s.sample, `"perDay": 10`, `"perDay": 6.5`,
				"bill.amountDue.adjustments.parameters.lateFees.perDay: " + amount, s.read},
			body{s.kind + " tip range max", s.sample, `"max": 25`, `"max": 6.5`,
				"bill.tip.range.max: " + msgIntegerNotInteger, s.read},
			body{s.kind + " tip preset", s.sample, `"presets": [10, 15]`, `"presets": [10, 6.5]`,
				"bill.tip.presets[1]: " + msgIntegerNotInteger, s.read},
			body{s.kind + " method amount", s.sample, pmAmount, `"amount": 6.5, "editable"`,
				"paymentMethods[0].amount: " + amount, s.read},
			body{s.kind + " editable range min", s.sample, `"min": 500`, `"min": 6.5`,
				"paymentMethods[0].editable.range.min: " + amount, s.read},
		)
	}
	cases = append(cases,
		body{"outbound amount", sampleOutbound, `"amount": 1000`, `"amount": 6.5`,
			"notification.payment.amount: " + amount, outbound},
		body{"outbound tipAmount", sampleOutbound, `"tipAmount": 0`, `"tipAmount": 6.5`,
			"notification.payment.tipAmount: " + amount, outbound},
	)
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			v := tc.read()
			if err := httpapi.Unmarshal([]byte(replace(t, tc.sample, tc.old, tc.new)), v); err != nil {
				t.Fatal(err)
			}
			if diff := cmp.Diff([]string{tc.want}, v.Validate()); diff != "" {
				t.Errorf("violations (-want +got):\n%s", diff)
			}
		})
	}
}

// The inbound notification (a payer's, read tolerantly) refuses a fractional amount naming it, as
// the outbound one does: an amount is never read as another.
func TestAnInboundNotificationRefusesAFractionalAmount(t *testing.T) {
	for _, tc := range []struct{ old, new, want string }{
		{`"amount": 1000`, `"amount": 6.5`, "payment.amount: " + msgAmountNotInteger("6.5")},
		{`"tipAmount": 0`, `"tipAmount": 1e2`, "payment.tipAmount: " + msgAmountNotInteger("1e2")},
	} {
		var r OutboundNotification
		if err := httpapi.Unmarshal([]byte(replace(t, sampleOutbound, tc.old, tc.new)), &r); err != nil {
			t.Fatal(err)
		}
		n := r.Notification.Value
		if diff := cmp.Diff([]string{tc.want}, n.Validate()); diff != "" {
			t.Errorf("%s (-want +got):\n%s", tc.new, diff)
		}
	}
}
