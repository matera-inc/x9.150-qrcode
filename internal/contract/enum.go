// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package contract

import (
	"encoding/json/jsontext"
	"slices"
	"strings"
)

// EnumValues names an enum of openapi.yaml: its values, and whether input is read in any case.
type EnumValues interface {
	Values() []string
	AnyCase() bool
}

// Enum is a member whose contract is an `enum`. As in the Java build, whose generated enums match
// their wire value exactly, a value outside the list is not a violation but an unreadable body:
// malformed-json. Only paymentTiming is read in any case and normalised to lower case
// (ObjectMapperFactory's caseInsensitivePaymentTimingModule).
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
	if values.AnyCase() {
		text = strings.ToLower(text)
	}
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

// AnyCase implements EnumValues.
func (StatusValues) AnyCase() bool { return false }

// Values implements EnumValues.
func (KindValues) Values() []string { return []string{"DEFAULT", "EXTERNAL"} }

// AnyCase implements EnumValues.
func (KindValues) AnyCase() bool { return false }

// Values implements EnumValues.
func (TimingValues) Values() []string { return []string{"deferred", "immediate"} }

// AnyCase implements EnumValues.
func (TimingValues) AnyCase() bool { return true }

// Values implements EnumValues.
func (FormulaValues) Values() []string { return []string{"FixedDiscountLateFeeLinearInterest"} }

// AnyCase implements EnumValues.
func (FormulaValues) AnyCase() bool { return false }

// Values implements EnumValues.
func (ProtectionValues) Values() []string { return []string{"tokenized", "encrypted", "plaintext"} }

// AnyCase implements EnumValues.
func (ProtectionValues) AnyCase() bool { return false }

// Values implements EnumValues.
func (ActionValues) Values() []string { return []string{"PAYMENT_INITIATED", "SENT", "NOT_SENT"} }

// AnyCase implements EnumValues.
func (ActionValues) AnyCase() bool { return false }
