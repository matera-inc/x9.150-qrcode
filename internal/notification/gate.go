// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package notification

import (
	"fmt"
	"math/big"
	"strconv"
	"strings"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/billing"
	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
)

// tipTolerance is how far a tip may miss its computed bound: one minor unit. A percentage of an
// amount rounds, and a payer converting from another currency rounds again; one unit of
// disagreement is arithmetic, not a breach. A unit and not a proportion, so a large bill's range is
// never quietly widened.
const tipTolerance = 1

// Gate is the acceptance gate of a pre-commit, judged at the payee's clock at receipt (INTERPRETATION
// I-13): nil when it would accept, or the refusal. It reads the QR Code as stored, never the payload
// the payer fetched:
//
//  1. the QR Code's validUntil, inclusive;
//  2. the payment method whose currency is the notification's (matched whatever the case), whose
//     network object for the notified rail is published and, on a chain, whose destination address
//     is blockchain.to: one lookup that covers the currency, the rail and the address together,
//     after a rail or a currency that no method offers at all is refused naming it, each rail on
//     its own (ADR-0036, amended 2026-11-15);
//  3. that method's validUntil, inclusive;
//  4. the tip: only on a bill that offers one, inside its range in percent of what is paid to the
//     merchant, give or take one minor unit;
//  5. the amount less the tip: within the editable range, or the amount the payload would quote now
//     (discounted, face or with its late fee), or the face amount when it is not less than that.
//
// The domain consults it only once the status admits the pre-commit, and releases a holder whose
// retry it refuses (ADR-0036). An error is a failure, never an answer: an adjusted amount past 18
// digits, which the create already refuses (ADR-0005).
func Gate(row domain.Row, req billing.Request, data *contract.NotificationData, now time.Time) (*domain.Refusal, error) {
	if row.ValidUntil.Before(now) {
		return refusal("validUntil", fmt.Sprintf("This QR Code expired at %s and can no longer be paid.",
			contract.FormatInstant(row.ValidUntil))), nil
	}
	pay := data.Payment.Value
	notified := domain.Notification{Network: pay.Network.Value, Currency: pay.Currency.Value}
	if missing := notOffered(req.PaymentMethods, notified); len(missing) > 0 {
		return domain.NotOfferedRefusal(notified, missing), nil
	}
	method, r := matchMethod(req.PaymentMethods, data)
	if r != nil {
		return r, nil
	}
	if method.ValidUntil.Before(now) {
		return refusal("paymentMethods.validUntil", fmt.Sprintf("The %s payment method expired at %s.",
			method.Currency, contract.FormatInstant(method.ValidUntil))), nil
	}
	total := pay.Amount.Value
	var tip int64
	if pay.TipAmount.IsSet() {
		tip = pay.TipAmount.Value
	}
	if r := checkTip(req.Bill.Tip, total, tip, method.Currency); r != nil {
		return r, nil
	}
	req.ValidUntil = row.ValidUntil
	return checkAmount(req, method, total, tip, now)
}

// notOffered is domain.NotOffered over the methods the gate reads: NOT_OFFERED_RAIL when no method
// publishes the notified rail, each rail on its own, then NOT_OFFERED_CURRENCY when none is in the
// notified currency, both matched whatever their case.
func notOffered(methods []billing.PaymentMethod, n domain.Notification) []domain.Deviation {
	rail, currency := false, false
	for _, m := range methods {
		rail = rail || publishes(m.Networks, n.Network)
		currency = currency || strings.EqualFold(m.Currency, n.Currency)
	}
	var out []domain.Deviation
	if !rail {
		out = append(out, domain.DeviationNotOfferedRail)
	}
	if !currency {
		out = append(out, domain.DeviationNotOfferedCurrency)
	}
	return out
}

func refusal(field, message string) *domain.Refusal {
	return &domain.Refusal{Kind: domain.KindBusinessRule, Field: field, Message: message}
}

// matchMethod is the single lookup of Gate's step 2.
func matchMethod(methods []billing.PaymentMethod, data *contract.NotificationData) (*billing.PaymentMethod, *domain.Refusal) {
	pay := data.Payment.Value
	currency, network := pay.Currency.Value, pay.Network.Value
	bank := bankRail(network)
	to := ""
	if data.Blockchain.IsSet() && data.Blockchain.Value.To.IsSet() {
		to = data.Blockchain.Value.To.Value
	}
	for i, m := range methods {
		if !strings.EqualFold(m.Currency, currency) || !publishes(m.Networks, network) {
			continue
		}
		if bank || destination(m.Networks, network) == to && to != "" {
			return &methods[i], nil
		}
	}
	if bank {
		return nil, refusal("paymentNotification.data",
			fmt.Sprintf("This QR Code offers no %s payment method on the %s rail.", currency, network))
	}
	address := to
	if address == "" {
		address = "null"
	}
	return nil, refusal("paymentNotification.data",
		fmt.Sprintf("No %s payment method on this QR Code publishes the destination address %s. "+
			"The currency, the rail and the address must belong to the same payment method.", currency, address))
}

// destination is the address a method publishes for a chain: Solana's recipient.
func destination(n billing.Networks, network string) string {
	if strings.EqualFold(network, railSolana) && n.Solana != nil {
		return n.Solana.Recipient
	}
	return ""
}

// checkTip is Gate's step 4. presets do not bind: the range is the rule (A.10), and a bill with
// presets and no range takes any positive tip.
func checkTip(offered *billing.Tip, total, tip int64, currency string) *domain.Refusal {
	if tip == 0 {
		return nil
	}
	if offered == nil || !offered.Allowed {
		return refusal(fieldTipAmount,
			fmt.Sprintf("This bill does not accept tips, but the notification carries a tip of %d.", tip))
	}
	if offered.Range == nil {
		return nil
	}
	// The percentage is of what is paid to the merchant, in the notified currency.
	base := total - tip
	if base <= 0 {
		return nil
	}
	low, high := percentOf(base, offered.Range.Min), percentOf(base, offered.Range.Max)
	if big.NewInt(tip).Cmp(new(big.Int).Sub(low, big.NewInt(tipTolerance))) < 0 ||
		big.NewInt(tip).Cmp(new(big.Int).Add(high, big.NewInt(tipTolerance))) > 0 {
		return refusal(fieldTipAmount, fmt.Sprintf(
			"A tip on this bill must be between %d%% and %d%% of the %d %s being paid (%s..%s) but the notification carries %d.",
			offered.Range.Min, offered.Range.Max, base, currency, low, high, tip))
	}
	return nil
}

// percentOf is base × percent / 100, rounded half up.
func percentOf(base int64, percent int32) *big.Int {
	n := new(big.Int).Mul(big.NewInt(base), big.NewInt(int64(percent)))
	n.Add(n, big.NewInt(50))
	return n.Div(n, big.NewInt(100)) // round half up: base and the range are never negative
}

// checkAmount is Gate's step 5. amount is the total transferred, tip included (§13.6.2), so the
// bill is judged against what is left once the tip is taken out.
func checkAmount(req billing.Request, m *billing.PaymentMethod, total, tip int64, now time.Time) (*domain.Refusal, error) {
	notified := total - tip
	if notified < 0 {
		return refusal(fieldTipAmount, fmt.Sprintf("The tip of %d is larger than the %d %s being paid.",
			tip, total, m.Currency)), nil
	}
	if m.Editable != nil && m.Editable.Range != nil {
		if notified < m.Editable.Range.Min || notified > m.Editable.Range.Max {
			return refusal(fieldAmount, fmt.Sprintf("The %s amount is editable within %d..%d but the notification carries %d.",
				m.Currency, m.Editable.Range.Min, m.Editable.Range.Max, notified)), nil
		}
		return nil, nil
	}
	price, err := billing.Adjust(req.Bill, req.ValidUntil, now)
	if err != nil {
		return nil, err
	}
	face := m.Amount
	adjusted, err := billing.MethodAmount(face, price)
	if err != nil {
		return nil, err
	}
	if notified == adjusted || notified == face && face >= adjusted {
		return nil, nil
	}
	var expected string
	switch {
	case face == adjusted:
		expected = strconv.FormatInt(face, 10)
	case face > adjusted:
		expected = fmt.Sprintf("%d (adjusted) or %d (face)", adjusted, face)
	default:
		expected = fmt.Sprintf("%d (adjusted, including the late fee)", adjusted)
	}
	carried := strconv.FormatInt(notified, 10)
	if tip != 0 {
		carried = fmt.Sprintf("%d (%d total less a %d tip)", notified, total, tip)
	}
	return refusal(fieldAmount, fmt.Sprintf("Expected %s %s but the notification carries %s.", expected, m.Currency, carried)), nil
}
