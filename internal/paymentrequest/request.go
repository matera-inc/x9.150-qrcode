// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package paymentrequest

import (
	"bytes"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"fmt"

	"github.com/matera-inc/x9.150-qrcode/internal/billing"
	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
)

// billingRequest is what the business rules read of a create: the body as decoded, the contract's
// checks already passed. A tip block without allowed reads as allowed, the contract's default.
func billingRequest(req *contract.CreateRequest) billing.Request {
	b := req.Bill.Value
	out := billing.Request{
		ValidUntil: req.ValidUntil.Value,
		Bill: billing.Bill{
			PaymentTiming: b.PaymentTiming.Value,
			AmountDue: billing.AmountDue{
				Amount:     b.AmountDue.Value.Amount.Value,
				Currency:   b.AmountDue.Value.Currency.Value,
				Adjustment: billingAdjustment(b.AmountDue.Value.Adjustments),
			},
		},
	}
	if inv := b.Invoice; inv.IsSet() {
		out.Bill.Invoice = &billing.Invoice{Date: inv.Value.Date.Value, DueDate: inv.Value.DueDate.Value}
	}
	if t := b.Tip; t.IsSet() {
		tip := &billing.Tip{Allowed: !t.Value.Allowed.IsSet() || t.Value.Allowed.Value}
		if r := t.Value.Range; r.IsSet() {
			tip.Range = &billing.TipRange{Min: r.Value.Min.Value, Max: r.Value.Max.Value}
		}
		for _, p := range t.Value.Presets.Items {
			tip.Presets = append(tip.Presets, p.Value)
		}
		out.Bill.Tip = tip
	}
	for _, m := range req.PaymentMethods.Items {
		out.PaymentMethods = append(out.PaymentMethods, billingMethod(m))
	}
	return out
}

func billingAdjustment(a contract.Object[contract.Adjustment]) *billing.Adjustment {
	if !a.IsSet() {
		return nil
	}
	out := &billing.Adjustment{Formula: a.Value.Formula.Value}
	p := a.Value.Parameters.Value
	for _, d := range p.Discounts.Items {
		out.Discounts = append(out.Discounts, billing.Discount{
			DaysBefore: d.DaysBefore.Value, Discount: d.Discount.Value, Explanation: d.Explanation.Value,
		})
	}
	if f := p.LateFees; f.IsSet() {
		out.LateFees = &billing.LateFees{Fixed: f.Value.Fixed.Value, PerDay: f.Value.PerDay.Value, Explanation: f.Value.Explanation.Value}
	}
	return out
}

func billingMethod(m contract.PaymentMethod) billing.PaymentMethod {
	out := billing.PaymentMethod{Currency: m.Currency.Value, ValidUntil: m.ValidUntil.Value, Amount: m.Amount.Value}
	if e := m.Editable; e.IsSet() {
		out.Editable = &billing.Editable{}
		if r := e.Value.Range; r.IsSet() {
			out.Editable.Range = &billing.AmountRange{Min: r.Value.Min.Value, Max: r.Value.Max.Value}
		}
	}
	n := m.Networks.Value
	bank := func(o contract.Object[contract.BankAddress]) *billing.BankAddress {
		if !o.IsSet() {
			return nil
		}
		return &billing.BankAddress{RoutingNumber: o.Value.RoutingNumber.Value, AccountNumber: o.Value.AccountNumber.Value}
	}
	out.Networks = billing.Networks{FedNow: bank(n.FedNow), RTP: bank(n.RTP), ACH: bank(n.ACH), Other: n.OtherNames()}
	if s := n.Solana; s.IsSet() {
		out.Networks.Solana = &billing.SolanaAddress{Recipient: s.Value.Recipient.Value}
		if s.Value.Memo.IsSet() {
			out.Networks.Solana.Memo = &s.Value.Memo.Value
		}
	}
	return out
}

// storedRequest is request_json: the create body as decoded, without its locationId, which is a
// column of its own (ADR-0024).
func storedRequest(req contract.CreateRequest) ([]byte, error) {
	req.LocationID = contract.String{}
	b, err := httpapi.Marshal(req)
	if err != nil {
		return nil, fmt.Errorf("writing request_json: %w", err)
	}
	return b, nil
}

// readRequest reads request_json back into the type it was written from.
func readRequest(raw []byte) (contract.CreateRequest, error) {
	var req contract.CreateRequest
	if err := json.Unmarshal(raw, &req); err != nil {
		return contract.CreateRequest{}, fmt.Errorf("reading request_json: %w", err)
	}
	return req, nil
}

// sameCreditor is Java's CreditorVO equality: every member equal, an absent one and a null one
// alike.
func sameCreditor(a, b contract.Object[contract.Creditor]) (bool, error) {
	x, err := withoutNulls(a)
	if err != nil {
		return false, err
	}
	y, err := withoutNulls(b)
	if err != nil {
		return false, err
	}
	return bytes.Equal(x, y), nil
}

// withoutNulls is v as JSON with every null member left out, as Jackson's NON_NULL writes it.
func withoutNulls(v any) (jsontext.Value, error) {
	raw, err := httpapi.Marshal(v)
	if err != nil {
		return nil, err
	}
	var out bytes.Buffer
	enc := jsontext.NewEncoder(&out)
	if err := copyWithoutNulls(enc, jsontext.NewDecoder(bytes.NewReader(raw))); err != nil {
		return nil, err
	}
	return jsontext.Value(bytes.TrimRight(out.Bytes(), "\n")), nil
}

// copyWithoutNulls copies the next value of dec to enc, leaving out every object member whose
// value is null. An array keeps its items as they are.
func copyWithoutNulls(enc *jsontext.Encoder, dec *jsontext.Decoder) error {
	switch dec.PeekKind() { //nolint:exhaustive // scalars, null included, are copied as they are
	case '{':
		if err := copyToken(enc, dec); err != nil {
			return err
		}
		for dec.PeekKind() != '}' {
			tok, err := dec.ReadToken()
			if err != nil {
				return err
			}
			name := tok.Clone() // the next read voids tok
			if dec.PeekKind() == 'n' {
				if err := dec.SkipValue(); err != nil {
					return err
				}
				continue
			}
			if err := enc.WriteToken(name); err != nil {
				return err
			}
			if err := copyWithoutNulls(enc, dec); err != nil {
				return err
			}
		}
		return copyToken(enc, dec)
	case '[':
		if err := copyToken(enc, dec); err != nil {
			return err
		}
		for dec.PeekKind() != ']' {
			if err := copyWithoutNulls(enc, dec); err != nil {
				return err
			}
		}
		return copyToken(enc, dec)
	default:
		v, err := dec.ReadValue()
		if err != nil {
			return err
		}
		return enc.WriteValue(v)
	}
}

func copyToken(enc *jsontext.Encoder, dec *jsontext.Decoder) error {
	tok, err := dec.ReadToken()
	if err != nil {
		return err
	}
	return enc.WriteToken(tok)
}
