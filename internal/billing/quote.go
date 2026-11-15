// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package billing

import (
	"math/big"
	"time"
)

// Quote is what a payload shows at one instant: the adjustment, if any, and the payment methods
// still valid then, each with its amount.
type Quote struct {
	// Adjustment is nil when no adjustment applies. Its ValidUntil is capped by the QR Code's and
	// by the last of Methods (FR36).
	Adjustment *Price
	// Methods are the payment methods valid at the instant, in the request's order; empty when
	// none is.
	Methods []QuotedMethod
}

// QuotedMethod is a payment method as the payload shows it.
type QuotedMethod struct {
	// Index is the method's position in the request's paymentMethods.
	Index int
	// Amount is the method's amount with the bill's adjustment carried to it by ratio.
	Amount int64
}

// PricingInstant is the instant a payload is priced at: the later of the payer's dateForPayment
// and now, so a payload is never priced in the past (INTERPRETATION I-14).
func PricingInstant(dateForPayment, now time.Time) time.Time {
	if dateForPayment.After(now) {
		return dateForPayment
	}
	return now
}

// QuoteAt prices req at exactly the instant at, as Java's payload does: the adjustment at that
// instant, the payment methods still valid at that same instant (a method's validUntil is
// inclusive), each method's amount carried from the bill by ratio, and the adjustment's
// validUntil no later than the QR Code's nor than the last method shown. An amount past 18 digits
// is refused rather than shown (ADR-0005).
func QuoteAt(req Request, at time.Time) (Quote, error) {
	price, err := Adjust(req.Bill, req.ValidUntil, at)
	if err != nil {
		return Quote{}, err
	}
	var q Quote
	limit := req.ValidUntil
	var lastMethodEnds time.Time
	for i, m := range req.PaymentMethods {
		if at.After(m.ValidUntil) {
			continue
		}
		amount, err := MethodAmount(m.Amount, price)
		if err != nil {
			return Quote{}, err
		}
		q.Methods = append(q.Methods, QuotedMethod{Index: i, Amount: amount})
		if lastMethodEnds.IsZero() || m.ValidUntil.After(lastMethodEnds) {
			lastMethodEnds = m.ValidUntil
		}
	}
	if !lastMethodEnds.IsZero() && lastMethodEnds.Before(limit) {
		limit = lastMethodEnds
	}
	if price != nil {
		capped := price.CappedAt(limit)
		q.Adjustment = &capped
	}
	return q, nil
}

// MethodAmount is a payment method's amount with the bill's adjustment carried to it by ratio,
// rounded half up: methodAmount × price.Amount / bill amount. The method's own amount when no
// adjustment applies. Refused past 18 digits.
func MethodAmount(methodAmount int64, price *Price) (int64, error) {
	if price == nil {
		return methodAmount, nil
	}
	bill := price.Amount - price.Adjustment
	if bill <= 0 {
		return methodAmount, nil
	}
	return require18(prorated(methodAmount, bill, big.NewInt(price.Amount)))
}
