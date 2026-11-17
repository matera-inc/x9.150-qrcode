// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

import (
	"encoding/json/jsontext"
	"slices"
)

// EnumValues names an enum of openapi.yaml: its values, matched exactly.
type EnumValues interface {
	Values() []string
}

// Enum is a member whose contract is an `enum`. As in the Java build, whose generated enums match
// their wire value exactly, a value outside the list is not a violation but an unreadable body:
// malformed-json. Every enum is matched exactly, in its case: what this service generates is strict
// (INTERPRETATION I-16), so "Immediate" is refused as "FedNow" and "usd" are. The Java build read
// paymentTiming in any case and stored it in lower case; that leniency is not kept.
type Enum[E EnumValues] struct {
	presence
	Value string
}

// EnumOf is an Enum holding v, for building a body in Go.
func EnumOf[E EnumValues](v string) Enum[E] {
	return Enum[E]{Present: true, Value: v}
}

// UnmarshalJSONFrom implements json.UnmarshalerFrom.
func (e *Enum[E]) UnmarshalJSONFrom(dec *jsontext.Decoder) error {
	*e = Enum[E]{Present: true}
	text, null, err := scalarText(dec)
	if err != nil || null {
		e.Null = null
		return err
	}
	var values E
	if !slices.Contains(values.Values(), text) {
		return errMalformed
	}
	e.Value = text
	return nil
}

// MarshalJSONTo implements json.MarshalerTo.
func (e Enum[E]) MarshalJSONTo(enc *jsontext.Encoder) error {
	if e.Null {
		return enc.WriteToken(jsontext.Null)
	}
	return enc.WriteToken(jsontext.String(e.Value))
}

// The enums of the request schemas.
type (
	// StatusValues is StatusUpdate.status.
	StatusValues struct{}
	// KindValues is PaymentNotification.kind.
	KindValues struct{}
	// TimingValues is BillBase.paymentTiming and PatchBillBase.paymentTiming.
	TimingValues struct{}
	// FormulaValues is Adjustment.formula and PatchAdjustment.formula.
	FormulaValues struct{}
	// ProtectionValues is ProtectionTypeEnum.
	ProtectionValues struct{}
	// ActionValues is BlockchainActionEnum.
	ActionValues struct{}
)

// Values implements EnumValues.
func (StatusValues) Values() []string {
	return []string{"PAID", "CANCELLED", "PAYMENT_INITIATED", "ACTIVE"}
}

// Values implements EnumValues.
func (KindValues) Values() []string { return []string{"DEFAULT", "EXTERNAL"} }

// Values implements EnumValues.
func (TimingValues) Values() []string { return []string{"deferred", "immediate"} }

// Values implements EnumValues.
func (FormulaValues) Values() []string { return []string{"FixedDiscountLateFeeLinearInterest"} }

// Values implements EnumValues.
func (ProtectionValues) Values() []string { return []string{"tokenized", "encrypted", "plaintext"} }

// Values implements EnumValues.
func (ActionValues) Values() []string { return []string{"PAYMENT_INITIATED", "SENT", "NOT_SENT"} }
