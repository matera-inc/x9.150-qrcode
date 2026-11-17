// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package billing

import "time"

// Request is the part of a payment request these rules read: its validity, its bill and its
// payment methods. The HTTP layer decodes the body, applies the contract checks of openapi.yaml,
// and fills this from what it decoded; a PATCH fills it from the merged result.
//
// Instants keep the offset they were written with: an invoice's date is compared with its due
// date's calendar day in that offset, as Java does.
type Request struct {
	ValidUntil     time.Time
	Bill           Bill
	PaymentMethods []PaymentMethod
}

// Bill is $.bill.
type Bill struct {
	Invoice *Invoice
	// Tip is nil when the body has no tip block, which Java stores as tips refused.
	Tip       *Tip
	AmountDue AmountDue
	// PaymentTiming is "deferred" or "immediate", exactly (the contract refuses another case, I-16).
	PaymentTiming string
}

// Invoice is $.bill.invoice.
type Invoice struct {
	// Date is the day the bill was issued; only its year, month and day are read.
	Date    time.Time
	DueDate time.Time
}

// AmountDue is $.bill.amountDue.
type AmountDue struct {
	Amount     int64
	Currency   string
	Adjustment *Adjustment
}

// FormulaFixedDiscountLateFeeLinearInterest is the one adjustment formula there is.
const FormulaFixedDiscountLateFeeLinearInterest = "FixedDiscountLateFeeLinearInterest"

// Adjustment is $.bill.amountDue.adjustments: the formula and its parameters.
type Adjustment struct {
	Formula string
	// Discounts is nil when none is offered; an empty list is read as none, as Java's mapper does.
	Discounts []Discount
	// LateFees is required by the contract.
	LateFees *LateFees
}

// Discount is one fixed discount, offered up to and including dueDate − DaysBefore × 24 h.
type Discount struct {
	DaysBefore  int32
	Discount    int64
	Explanation string
}

// LateFees is the late fee: Fixed + PerDay × days late.
type LateFees struct {
	Fixed       int64
	PerDay      int64
	Explanation string
}

// Tip is $.bill.tip.
type Tip struct {
	Allowed bool
	Range   *TipRange
	// Presets is nil when none is sent; an empty list is read as none, as Java's mapper does.
	Presets []int32
}

// TipRange is the tip's range, in percent.
type TipRange struct {
	Min int32
	Max int32
}

// PaymentMethod is one entry of $.paymentMethods.
type PaymentMethod struct {
	// Currency is required: no method inherits the bill's (INTERPRETATION I-3).
	Currency   string
	ValidUntil time.Time
	Amount     int64
	Editable   *Editable
	Networks   Networks
}

// Editable is a method's editable amount; its range is required when it is present.
type Editable struct {
	Range *AmountRange
}

// AmountRange is the range a payer may choose an amount in.
type AmountRange struct {
	Min int64
	Max int64
}

// Networks is a method's networks object: the rails this service interprets, and the names of
// every other key, in the order they were written, so that each is refused by name.
type Networks struct {
	FedNow *BankAddress
	RTP    *BankAddress
	ACH    *BankAddress
	Solana *SolanaAddress
	// Other holds the keys that are none of fednow, rtp, ach and solana, exactly as written:
	// FedNow is one of them, since the create API takes the standard's spelling only (I-1).
	Other []string
}

// BankAddress is a FedNow, RTP or ACH destination.
type BankAddress struct {
	RoutingNumber string
	AccountNumber string
}

// SolanaAddress is a Solana destination, as the Solana Foundation published it.
type SolanaAddress struct {
	Recipient string
	Memo      *string
}
