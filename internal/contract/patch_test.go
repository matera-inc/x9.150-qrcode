// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

import (
	"encoding/json/v2"
	"strings"
	"testing"

	"github.com/google/go-cmp/cmp"

	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
)

// Story 9.7: a key a PATCH does not declare, at its top level or in a payment method, is kept
// aside and never written back. The PATCH check refuses it by name (INTERPRETATION I-16), unless it
// is one a GET answer carries (creditor, status, …): that one is left to the handler, which
// compares it with the stored value (option B, owner 2026-11-15).
func TestAPatchKeepsUndeclaredMembersAside(t *testing.T) {
	body := `{"creditor": {"name": "x"}, "status": "PAID", "paymentMethods": [{"currency": "USDC",
		"validUntil": "2030-12-31T23:59:59Z", "amount": 1, "colour": "red",
		"networks": {"solana": {"recipient": "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"}}}]}`
	var p PatchRequest
	if err := httpapi.Unmarshal([]byte(body), &p); err != nil {
		t.Fatal(err)
	}
	if diff := cmp.Diff([]string{"creditor", "status"}, p.Unknown.Names()); diff != "" {
		t.Errorf("top level (-want +got):\n%s", diff)
	}
	if diff := cmp.Diff([]string{"colour"}, p.PaymentMethods.Items[0].Unknown.Names()); diff != "" {
		t.Errorf("payment method (-want +got):\n%s", diff)
	}
	out, err := json.Marshal(p)
	if err != nil {
		t.Fatal(err)
	}
	want := `{"paymentMethods":[{"currency":"USDC","validUntil":"2030-12-31T23:59:59Z","amount":1,` +
		`"networks":{"solana":{"recipient":"9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"}}}]}`
	if string(out) != want {
		t.Errorf("written back:\n got %s\nwant %s", out, want)
	}

	wantClosed := []string{
		"paymentMethods[0].colour: is not a field of a payment method, whose fields are currency, validUntil, amount, editable and networks",
	}
	if diff := cmp.Diff(wantClosed, p.Validate()); diff != "" {
		t.Errorf("closed (-want +got):\n%s", diff)
	}
	readOnly := p.ReadOnly()
	paths := make([]string, 0, len(readOnly))
	for _, m := range readOnly {
		paths = append(paths, m.Path)
	}
	if diff := cmp.Diff([]string{"creditor", "status"}, paths); diff != "" {
		t.Errorf("read-only (-want +got):\n%s", diff)
	}

	// A null method still reads as an empty one, as before Unknown was kept.
	var q PatchRequest
	if err := httpapi.Unmarshal([]byte(`{"paymentMethods": [null]}`), &q); err != nil || len(q.PaymentMethods.Items) != 1 {
		t.Errorf("a null method: %v %v", err, q.PaymentMethods.Items)
	}
}

// notAField is the violations that refuse an unknown member.
func notAField(violations []string) []string {
	var out []string
	for _, v := range violations {
		if strings.Contains(v, ": is not a field of ") {
			out = append(out, v)
		}
	}
	return out
}

// Option B (owner, 2026-11-15; X9-PATCH-510, -511): a read-only member equal to what GET renders is
// accepted, whatever its JSON spelling; a different one is refused by name, in the Java build's
// words; sentAt, the instant of the GET answer itself, is never compared; an unknown member is
// still refused.
func TestReadOnlyMembersAreComparedWithTheGetAnswer(t *testing.T) {
	answer := []byte(`{"id": "01A1E04EECD77A579A5F1521C38923CC", "revision": 2, "status": "ACTIVE",
		"createdAt": "2030-01-10T12:00:00Z", "creditor": {"name": "Blackbox Co", "MCC": "4900"},
		"paymentNotification": {"kind": "DEFAULT"}, "bill": {"amountDue": {"amount": 1000, "currency": "USD"}}}`)
	read := func(t *testing.T, body string) *PatchRequest {
		t.Helper()
		var p PatchRequest
		if err := httpapi.Unmarshal([]byte(body), &p); err != nil {
			t.Fatal(err)
		}
		return &p
	}
	for _, tc := range []struct {
		name, body string
		want       []string
	}{
		{"copied from GET", `{"id": "01A1E04EECD77A579A5F1521C38923CC", "revision": 2.0, "status": "ACTIVE",
			"sentAt": "1999-01-01T00:00:00Z", "creditor": {"MCC": "4900", "name": "Blackbox Co", "phone": null},
			"paymentNotification": {"kind": "DEFAULT"}, "bill": {"paymentTiming": "immediate", "description": "d",
			"amountDue": {"amount": 900, "currency": "USD"}}}`, nil},
		{"changed", `{"status": "PAID", "revision": 7, "creditor": {"name": "Other"}, "revisedAt": "2030-01-10T12:00:00Z",
			"bill": {"paymentTiming": "immediate", "description": "d", "amountDue": {"amount": 900, "currency": "EUR"}}}`,
			[]string{
				"status: cannot be changed by a PATCH (stored ACTIVE)",
				"revision: cannot be changed by a PATCH (stored 2)",
				"creditor: cannot be changed by a PATCH",
				"revisedAt: cannot be changed by a PATCH (none stored)",
				"bill.amountDue.currency: cannot be changed by a PATCH (stored USD)",
			}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			p := read(t, tc.body)
			if v := notAField(p.Validate()); v != nil {
				t.Fatalf("a read-only member was refused as unknown: %q", v)
			}
			got, err := ChangedReadOnly(answer, p.ReadOnly())
			if err != nil {
				t.Fatal(err)
			}
			if diff := cmp.Diff(tc.want, got); diff != "" {
				t.Errorf("violations (-want +got):\n%s", diff)
			}
		})
	}
	unknown := read(t, `{"colour": "red", "bill": {"paymentTiming": "immediate", "description": "d",
		"amountDue": {"amount": 1, "tax": 2}}}`)
	want := []string{
		"bill.amountDue.tax: is not a field of amountDue, whose fields are amount and adjustments",
		"colour: is not a field of the payment request, whose fields are locationId, validUntil, bill, unstructured, additionalInformation and paymentMethods",
	}
	if diff := cmp.Diff(want, notAField(unknown.Validate())); diff != "" {
		t.Errorf("unknown members are still refused (-want +got):\n%s", diff)
	}
}
