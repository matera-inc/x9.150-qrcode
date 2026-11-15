// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

import (
	jsonv1 "encoding/json"
	"encoding/json/jsontext"
	"fmt"
	"math/big"
	"slices"
	"strings"
)

// A PATCH may carry what a GET answer carries but a PATCH cannot change (owner, 2026-11-15, option
// B; INTERPRETATION I-16): each is accepted and ignored when it equals the stored value, so a body
// copied from GET keeps working, and refused by name when it differs. They are the members of
// PaymentRequestInformation that PatchPaymentRequestReplacement does not declare, at the levels a
// PATCH accepts: the top level, and bill.amountDue's currency. The Java build's list is
// ClosedRequestFields.READ_ONLY.
var (
	patchReadOnlyTop = []string{"id", "qrCode", "qrCodeB64", "location", "creditor", "paymentNotification",
		"revision", "createdAt", "revisedAt", "sentAt", "status", "paymentDetails", "emv"}
	patchReadOnlyAmountDue = []string{"currency"}
)

// alwaysIgnored is never compared: GET writes the instant of its own answer in sentAt, so no copy
// of it can ever match.
const alwaysIgnored = "sentAt"

// ReadOnlyMember is a read-only member a PATCH carried: its path, and the value sent.
type ReadOnlyMember struct {
	Path  string
	Value jsontext.Value
}

// ReadOnly is the read-only members r carried, the top level first, each in the order sent;
// sentAt is left out.
func (r *PatchRequest) ReadOnly() []ReadOnlyMember {
	var out []ReadOnlyMember
	for _, m := range r.Unknown {
		if m.Name != alwaysIgnored && slices.Contains(patchReadOnlyTop, m.Name) {
			out = append(out, ReadOnlyMember{Path: m.Name, Value: m.Value})
		}
	}
	if r.Bill.IsSet() && r.Bill.Value.AmountDue.IsSet() {
		for _, m := range r.Bill.Value.AmountDue.Value.Unknown {
			if slices.Contains(patchReadOnlyAmountDue, m.Name) {
				out = append(out, ReadOnlyMember{Path: "bill.amountDue." + m.Name, Value: m.Value})
			}
		}
	}
	return out
}

// ChangedReadOnly is the violation of each member of sent that differs from what answer, the GET
// answer rendered at the same instant, holds at its path, in the Java build's words:
//
//	bill.amountDue.currency: cannot be changed by a PATCH (stored USD)
//	revisedAt: cannot be changed by a PATCH (none stored)
//	creditor: cannot be changed by a PATCH
//
// Values are compared as JSON: a number by its value, a string exactly as GET writes it, an
// absent member and a null alike, an object member by member.
func ChangedReadOnly(answer jsontext.Value, sent []ReadOnlyMember) ([]string, error) {
	if len(sent) == 0 {
		return nil, nil
	}
	stored, err := decodeAny(answer)
	if err != nil {
		return nil, err
	}
	var violations []string
	for _, m := range sent {
		value, err := decodeAny(m.Value)
		if err != nil {
			return nil, err
		}
		at := stored
		for step := range strings.SplitSeq(m.Path, ".") {
			obj, _ := at.(map[string]any)
			at = obj[step]
		}
		if !sameJSON(at, value) {
			violations = append(violations, m.Path+": "+readOnlyMessage(at))
		}
	}
	return violations, nil
}

func readOnlyMessage(stored any) string {
	switch v := stored.(type) {
	case nil:
		return "cannot be changed by a PATCH (none stored)"
	case map[string]any, []any:
		return "cannot be changed by a PATCH"
	default:
		return fmt.Sprintf("cannot be changed by a PATCH (stored %v)", v)
	}
}

func decodeAny(raw []byte) (any, error) {
	dec := jsonv1.NewDecoder(strings.NewReader(string(raw)))
	dec.UseNumber()
	var v any
	if err := dec.Decode(&v); err != nil {
		return nil, fmt.Errorf("reading a read-only member: %w", err)
	}
	return v, nil
}

func sameJSON(a, b any) bool {
	switch x := a.(type) {
	case nil:
		return b == nil
	case jsonv1.Number:
		y, ok := b.(jsonv1.Number)
		if !ok {
			return false
		}
		p, okP := new(big.Rat).SetString(x.String())
		q, okQ := new(big.Rat).SetString(y.String())
		return okP && okQ && p.Cmp(q) == 0
	case map[string]any:
		y, ok := b.(map[string]any)
		if !ok {
			return false
		}
		for k, v := range x {
			if !sameJSON(v, y[k]) {
				return false
			}
		}
		for k, v := range y {
			if _, seen := x[k]; !seen && v != nil {
				return false
			}
		}
		return true
	case []any:
		y, ok := b.([]any)
		if !ok || len(x) != len(y) {
			return false
		}
		for i := range x {
			if !sameJSON(x[i], y[i]) {
				return false
			}
		}
		return true
	default:
		return a == b
	}
}
