// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

import (
	"encoding/json/v2"
	"testing"

	"github.com/google/go-cmp/cmp"

	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
)

// Story 9.7: a key a PATCH does not declare, at its top level or in a payment method, is kept
// aside and never written back. Today it is dropped, as Java drops it (X9-PATCH-420); closing it
// is ClosedPatchMembers, and what the closed check names is pinned here.
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
	if !ClosedPatchMembers {
		if v := p.Validate(); v != nil {
			t.Errorf("dropped today, yet refused: %v", v)
		}
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

	c := &checker{}
	checkUnknownPatchMembers(c, &p)
	wantClosed := []string{
		"creditor: is not a field of a PATCH, whose fields are locationId, validUntil, bill, unstructured, additionalInformation and paymentMethods",
		"status: is not a field of a PATCH, whose fields are locationId, validUntil, bill, unstructured, additionalInformation and paymentMethods",
		"paymentMethods[0].colour: is not a field of a payment method, whose fields are currency, validUntil, amount, editable and networks",
	}
	if diff := cmp.Diff(wantClosed, c.result()); diff != "" {
		t.Errorf("closed (-want +got):\n%s", diff)
	}

	// A null method still reads as an empty one, as before Unknown was kept.
	var q PatchRequest
	if err := httpapi.Unmarshal([]byte(`{"paymentMethods": [null]}`), &q); err != nil || len(q.PaymentMethods.Items) != 1 {
		t.Errorf("a null method: %v %v", err, q.PaymentMethods.Items)
	}
}
