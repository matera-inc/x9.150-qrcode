// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package billing

import (
	"fmt"
	"math/big"
	"regexp"
	"slices"
	"strings"
	"time"
	"unicode/utf16"
)

// The interpreted rails, as the refusal names them (ADR-0010, ADR-0012).
const supportedNetworks = "fednow, rtp, ach and solana"

var (
	currencyCode   = regexp.MustCompile(`^[A-Za-z]{1,32}$`)
	routingNumber  = regexp.MustCompile(`^[0-9]{9}$`)
	accountNumber  = regexp.MustCompile(`^[0-9A-Za-z]{4,17}$`)
	solanaAddress  = regexp.MustCompile(`^[1-9A-HJ-NP-Za-km-z]{32,44}$`)
	maxSolanaMemo  = 100
	paymentTimings = map[string]bool{"deferred": true, "immediate": true}
)

// CheckCreate refuses a payment request that Java refuses at creation, with the first violation
// Java would report, judged at now. The order is Java's:
//
//  1. a network this service does not interpret, by name, method by method (the request mapper);
//  2. each value on its own: the bill (CheckBill), then each payment method (CheckPaymentMethod);
//  3. the request as a whole: at least one payment method, the due date not after validUntil, no
//     currency twice, no method valid after the QR Code, a tip offer that agrees with itself;
//  4. nothing already past at now: each method's validUntil, the QR Code's, the due date and every
//     discount's edge;
//  5. no amount past 18 digits with the largest late fee (CheckLateFeeBound);
//  6. every currency settled by this deployment and all of them in one peg group.
//
// A date equal to now is not past, as in Java.
func CheckCreate(req Request, currencies *Currencies, now time.Time) error {
	if err := CheckNetworks(req.PaymentMethods); err != nil {
		return err
	}
	if err := CheckBill(req.Bill); err != nil {
		return err
	}
	for i, m := range req.PaymentMethods {
		if err := CheckPaymentMethod(i, m); err != nil {
			return err
		}
	}
	if len(req.PaymentMethods) == 0 {
		return rule("paymentMethods", "must not be null or empty.")
	}
	if err := CheckInvoiceDueDate(req); err != nil {
		return err
	}
	if err := CheckMethodsTogether(req); err != nil {
		return err
	}
	if err := CheckTip(req.Bill.Tip); err != nil {
		return err
	}
	if err := checkNotPast(req, now); err != nil {
		return err
	}
	if err := CheckLateFeeBound(req); err != nil {
		return err
	}
	return CheckCurrencies(req, currencies)
}

// CheckPatch refuses the merged state req of a PATCH applied to stored, once every change is
// applied, in the order both builds use: no currency twice and no method valid after the QR Code;
// then no date the PATCH changed already past at now (CheckChangedDatesNotPast); then the due date
// not after the QR Code's validUntil, whichever side of each pair the PATCH moved (R-EP-27); then
// the late-fee bound; then the currencies. The values of a patched bill or payment method are
// checked first with CheckBill and CheckPaymentMethod, and a patched bill's due date and tip with
// CheckInvoiceDueDate and CheckTip as soon as it is applied; which currencies a PATCH must name
// (ADR-0018), and whether a changed method is already past, belong to the merge.
func CheckPatch(req, stored Request, currencies *Currencies, now time.Time) error {
	if err := CheckMethodsTogether(req); err != nil {
		return err
	}
	if err := CheckChangedDatesNotPast(req, stored, now); err != nil {
		return err
	}
	if err := CheckInvoiceDueDate(req); err != nil {
		return err
	}
	if err := CheckLateFeeBound(req); err != nil {
		return err
	}
	return CheckCurrencies(req, currencies)
}

// CheckNetworks refuses, by name, every network key of a payment method that is not one of the
// rails this service interprets, the first such method's keys together (ADR-0012, INTERPRETATION
// I-2, I-9). A rail written in another case is one of them (I-1).
func CheckNetworks(methods []PaymentMethod) error {
	for _, m := range methods {
		if len(m.Networks.Other) > 0 {
			return rule("paymentMethods.networks", fmt.Sprintf("Unsupported network(s): %s. This service supports %s.",
				strings.Join(m.Networks.Other, ", "), supportedNetworks))
		}
	}
	return nil
}

// CheckBill refuses a bill whose values Java's value objects refuse, in the order Java builds them:
// the invoice, the tip's range, the amount due and its adjustment, the payment timing, then the
// bill as a whole.
func CheckBill(b Bill) error {
	if inv := b.Invoice; inv != nil && civilBefore(inv.DueDate, inv.Date) {
		return invalid("Invoice dueDate must be after or equal to creation date.")
	}
	if b.Tip != nil && b.Tip.Range != nil && b.Tip.Range.Min > b.Tip.Range.Max {
		return invalid("Tip range min value cannot be greater than tip range max value")
	}
	if err := checkAmountDue(b.AmountDue); err != nil {
		return err
	}
	if b.PaymentTiming != "" && !paymentTimings[b.PaymentTiming] {
		return invalid("Unexpected value '" + b.PaymentTiming + "'")
	}
	if b.Invoice == nil && b.AmountDue.Adjustment != nil {
		return invalid("Bill amount due adjustment can only be informed when invoice is not null.")
	}
	if b.Invoice == nil && b.PaymentTiming == "deferred" {
		return invalid("Bill invoice must be informed when DEFERRED payment timing.")
	}
	if adj := b.AmountDue.Adjustment; adj != nil {
		for i, d := range adj.Discounts {
			if d.Discount >= b.AmountDue.Amount {
				return invalid(fmt.Sprintf("Bill discount at index %d. Must not be greater than or equal to amount due.", i))
			}
		}
	}
	return nil
}

func checkAmountDue(a AmountDue) error {
	if !currencyCode.MatchString(a.Currency) {
		return invalid("Currency must be a 1-32 character alphabetic code.")
	}
	if err := CheckAmount(a.Amount); err != nil {
		return err
	}
	if adj := a.Adjustment; adj != nil {
		if adj.Formula != FormulaFixedDiscountLateFeeLinearInterest {
			return invalid("Unexpected value '" + adj.Formula + "'")
		}
		for _, d := range adj.Discounts {
			if err := checkDiscount(d); err != nil {
				return err
			}
		}
		if adj.LateFees == nil {
			return invalid("Adjustment parameters late fees must not be null")
		}
		if err := checkLateFees(*adj.LateFees); err != nil {
			return err
		}
	}
	if a.Amount <= 0 {
		return invalid("Amount due currency amount value must be greater than zero")
	}
	return nil
}

func checkDiscount(d Discount) error {
	if d.Discount <= 0 {
		return invalid("Discount value must be greater than 0.")
	}
	if err := CheckSignedAmount(d.Discount); err != nil {
		return err
	}
	if d.DaysBefore <= 0 {
		return invalid("Days before discount must be greater than 0.")
	}
	if isBlank(d.Explanation) {
		return invalid("Discount explanation must not be null or blank.")
	}
	return nil
}

// lateFeesField is the late fees' path in the request body.
const lateFeesField = "bill.amountDue.adjustments.parameters.lateFees"

func checkLateFees(f LateFees) error {
	if isBlank(f.Explanation) {
		return invalid("Late fee explanation must not be null or blank.")
	}
	if f.Fixed < 0 {
		return rule(lateFeesField+".fixed", "must be greater than or equal to 0")
	}
	if f.PerDay < 0 {
		return rule(lateFeesField+".perDay", "must be greater than or equal to 0")
	}
	if err := CheckSignedAmount(f.Fixed); err != nil {
		return err
	}
	return CheckSignedAmount(f.PerDay)
}

// CheckPaymentMethod refuses a payment method whose values Java refuses, in the order Java builds
// them: its currency (required: no method inherits the bill's), its amount, its editable range, then
// its networks (fednow, ach, rtp, solana), at least one of which must be given.
func CheckPaymentMethod(index int, m PaymentMethod) error {
	if m.Currency == "" {
		return invalidField(fmt.Sprintf("paymentMethods[%d].currency", index), "must not be null")
	}
	if err := CheckAmount(m.Amount); err != nil {
		return err
	}
	if m.Editable != nil {
		r := m.Editable.Range
		if r == nil {
			return invalid("Editable amount range is required when the amount is editable.")
		}
		if err := CheckAmount(r.Min); err != nil {
			return err
		}
		if err := CheckAmount(r.Max); err != nil {
			return err
		}
		if r.Min > r.Max {
			return invalid("Minimum amount cannot be greater than maximum amount.")
		}
	}
	n := m.Networks
	for _, bank := range []*BankAddress{n.FedNow, n.ACH, n.RTP} {
		if err := checkBankAddress(bank); err != nil {
			return err
		}
	}
	if s := n.Solana; s != nil {
		if !solanaAddress.MatchString(s.Recipient) {
			return invalid("Solana recipient must be a Base58 address of 32 to 44 characters.")
		}
		if s.Memo != nil && len(utf16.Encode([]rune(*s.Memo))) > maxSolanaMemo {
			return invalid(fmt.Sprintf("Solana memo must not exceed %d characters.", maxSolanaMemo))
		}
	}
	if n.FedNow == nil && n.ACH == nil && n.RTP == nil && n.Solana == nil && len(n.Other) == 0 {
		return invalid("At least one network must be provided.")
	}
	return nil
}

// checkBankAddress refuses a routing number that is not 9 digits and an account number that is
// not 4 to 17 letters or digits (INTERPRETATION I-6). Java's value object tests the account pattern
// against the routing number, so the account number is refused there only by the contract.
func checkBankAddress(b *BankAddress) error {
	if b == nil {
		return nil
	}
	if isBlank(b.RoutingNumber) {
		return invalid("RoutingNumber must not be null or blank.")
	}
	if isBlank(b.AccountNumber) {
		return invalid("AccountNumber must not be null or blank.")
	}
	if !routingNumber.MatchString(b.RoutingNumber) {
		return invalid("RoutingNumber is invalid.")
	}
	if !accountNumber.MatchString(b.AccountNumber) {
		return invalid("AccountNumber is invalid.")
	}
	return nil
}

// CheckInvoiceDueDate refuses a due date after the QR Code's validUntil.
func CheckInvoiceDueDate(req Request) error {
	if inv := req.Bill.Invoice; inv != nil && inv.DueDate.After(req.ValidUntil) {
		return rule("bill.invoice.dueDate", "must be before or equal to validUntil date.")
	}
	return nil
}

// CheckMethodsTogether refuses two payment methods in one currency, and a method valid after the
// QR Code. Currencies are compared exactly; of several repeated ones, the first in request order is
// named.
func CheckMethodsTogether(req Request) error {
	if dup := firstDuplicate(req.PaymentMethods); dup != "" {
		return rule("paymentMethods", "must not contain duplicated currency: "+dup)
	}
	for i, m := range req.PaymentMethods {
		if err := checkMethodWithinValidUntil(req, i, m); err != nil {
			return err
		}
	}
	return nil
}

func checkMethodWithinValidUntil(req Request, i int, m PaymentMethod) error {
	if m.ValidUntil.After(req.ValidUntil) {
		return rule(fmt.Sprintf("paymentMethods[%d].validUntil", i), "must be before or equal than payment request validUntil.")
	}
	return nil
}

// CheckTip refuses a tip offer that contradicts itself (INTERPRETATION I-10, ADR-0017): a range or
// presets when tips are refused, or neither when they are allowed. Presets alone are an offer; they
// need not hold any minimum number of entries. No tip block is tips refused. A create checks its
// bill; a PATCH checks the merged bill, with the presets it kept (owner, 2026-11-15).
func CheckTip(t *Tip) error {
	if t == nil {
		return nil
	}
	hasOffer := t.Range != nil || len(t.Presets) > 0
	if !t.Allowed && hasOffer {
		return rule("tip.allow", "cannot provided range or presets when tip is not allowed")
	}
	if t.Allowed && !hasOffer {
		return rule("tip.allow", "should inform range or presets when tip is allowed")
	}
	return nil
}

// checkNotPast refuses, at creation, what is already past at now: a payment method's validUntil,
// the QR Code's, the due date, and every discount's edge. Equal to now is not past.
func checkNotPast(req Request, now time.Time) error {
	for i, m := range req.PaymentMethods {
		if m.ValidUntil.Before(now) {
			return rule(fmt.Sprintf("paymentMethods[%d].validUntil", i), "must be after or equal to actual date.")
		}
	}
	if req.ValidUntil.Before(now) {
		return rule("validUntil", "must not be less than the current date.")
	}
	inv := req.Bill.Invoice
	if inv == nil {
		return nil
	}
	if inv.DueDate.Before(now) {
		return rule("bill.invoice.dueDate", "must be after or equal to actual date.")
	}
	if adj := req.Bill.AmountDue.Adjustment; adj != nil {
		for i, d := range adj.Discounts {
			if discountEdge(inv.DueDate, d.DaysBefore).Before(now) {
				return rule(fmt.Sprintf("bill.amountDue.adjustments.discounts[%d]", i), "target date must be after the current date.")
			}
		}
	}
	return nil
}

// CheckChangedDatesNotPast is checkNotPast for a PATCH (owner, 2026-11-15): of the dates the
// create judges against now, only those whose value the PATCH changed from stored are judged, in
// the create's words and with its inclusive edge: the QR Code's validUntil, the due date, and each
// discount's edge (dueDate − daysBefore) that stored did not already have. A date left as it was,
// even one already past, is not judged again, so a biller can extend a QR Code that has expired. A
// payment method's validUntil is the merge's business: a method the PATCH changes is refused
// whole when its validUntil is past.
func CheckChangedDatesNotPast(req, stored Request, now time.Time) error {
	if !req.ValidUntil.Equal(stored.ValidUntil) && req.ValidUntil.Before(now) {
		return rule("validUntil", "must not be less than the current date.")
	}
	inv := req.Bill.Invoice
	if inv == nil {
		return nil
	}
	var storedDue *time.Time
	if stored.Bill.Invoice != nil {
		storedDue = &stored.Bill.Invoice.DueDate
	}
	if (storedDue == nil || !inv.DueDate.Equal(*storedDue)) && inv.DueDate.Before(now) {
		return rule("bill.invoice.dueDate", "must be after or equal to actual date.")
	}
	adj := req.Bill.AmountDue.Adjustment
	if adj == nil {
		return nil
	}
	var storedEdges []time.Time
	if storedDue != nil && stored.Bill.AmountDue.Adjustment != nil {
		for _, d := range stored.Bill.AmountDue.Adjustment.Discounts {
			storedEdges = append(storedEdges, discountEdge(*storedDue, d.DaysBefore))
		}
	}
	for i, d := range adj.Discounts {
		edge := discountEdge(inv.DueDate, d.DaysBefore)
		kept := slices.ContainsFunc(storedEdges, edge.Equal)
		if !kept && edge.Before(now) {
			return rule(fmt.Sprintf("bill.amountDue.adjustments.discounts[%d]", i), "target date must be after the current date.")
		}
	}
	return nil
}

// CheckLateFeeBound refuses a request whose bill amount, or any payment method's amount as the
// payload would show it, would pass 18 digits with the largest late fee the bill reaches before
// validUntil: fixed + perDay × days late at validUntil (ADR-0005). A method is bounded by the bill's
// ratio, m × (B + fee) / B rounded half up, which is what the payload serves.
func CheckLateFeeBound(req Request) error {
	fee, days := LargestLateFee(req.Bill, req.ValidUntil)
	if fee.Sign() == 0 {
		return nil
	}
	f := req.Bill.AmountDue.Adjustment.LateFees
	feeText := fmt.Sprintf("the largest late fee this bill reaches before validUntil (fixed %d + perDay %d × %s days = %s)",
		f.Fixed, f.PerDay, days, fee)
	bill := req.Bill.AmountDue.Amount
	billWithFee := new(big.Int).Add(big.NewInt(bill), fee)
	if !fits18(billWithFee) {
		return rule("bill.amountDue.amount", exceeds(bill, feeText, billWithFee))
	}
	for i, m := range req.PaymentMethods {
		if withFee := prorated(m.Amount, bill, billWithFee); !fits18(withFee) {
			return rule(fmt.Sprintf("paymentMethods[%d].amount", i), exceeds(m.Amount, feeText, withFee))
		}
	}
	return nil
}

func exceeds(amount int64, feeText string, largest *big.Int) string {
	return fmt.Sprintf("%d with %s can reach %s, more than the 18 digits ANSI X9.150 allows (%d). "+
		"Lower the amount or the late fee, or set an earlier validUntil.", amount, feeText, largest, MaxAmount)
}

// CheckCurrencies refuses the request's currencies (the bill's, then each method's) that this
// deployment does not settle, then a mix of currencies from more than one peg group: the allow list
// first, so that the culprit named is the same as Java's.
func CheckCurrencies(req Request, currencies *Currencies) error {
	all := RequestCurrencies(req)
	if err := currencies.CheckSupported(all); err != nil {
		return err
	}
	return currencies.CheckPegged(all)
}

// RequestCurrencies is every currency the request uses: the bill's, then each method's.
func RequestCurrencies(req Request) []string {
	all := make([]string, 0, 1+len(req.PaymentMethods))
	all = append(all, req.Bill.AmountDue.Currency)
	for _, m := range req.PaymentMethods {
		all = append(all, m.Currency)
	}
	return all
}

// civilBefore reports whether a's calendar day, in its own offset, is before b's.
func civilBefore(a, b time.Time) bool {
	ay, am, ad := a.Date()
	by, bm, bd := b.Date()
	if ay != by {
		return ay < by
	}
	if am != bm {
		return am < bm
	}
	return ad < bd
}

// firstDuplicate is the first currency, in request order, that appears more than once: the first
// by first appearance, so that the refusal names what the biller wrote first.
func firstDuplicate(methods []PaymentMethod) string {
	var order []string
	count := map[string]int{}
	for _, m := range methods {
		if count[m.Currency] == 0 {
			order = append(order, m.Currency)
		}
		count[m.Currency]++
	}
	for _, c := range order {
		if count[c] > 1 {
			return c
		}
	}
	return ""
}
