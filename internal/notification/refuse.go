// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package notification

import (
	"fmt"
	"strings"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/billing"
	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
)

// The rails this service interprets (ADR-0010, ADR-0012), as the create API spells them (I-1). A
// notification's network is matched whatever its case (I-1) and echoed as sent.
const (
	railFedNow = "fednow"
	railRTP    = "rtp"
	railACH    = "ach"
	railSolana = "solana"
)

// The fields the refusals name, as the Java build names them.
const (
	fieldAmount    = "paymentNotification.data.payment.amount"
	fieldTipAmount = "paymentNotification.data.payment.tipAmount"
	fieldNetwork   = "paymentNotification.data.payment.network"
)

// bankRail reports whether network settles through a bank (FedNow, RTP, ACH): it names no
// destination address and carries no blockchain object.
func bankRail(network string) bool {
	switch strings.ToLower(network) {
	case railFedNow, railRTP, railACH:
		return true
	default:
		return false
	}
}

// publishes reports whether a method's networks object carries network, matched whatever its case.
func publishes(n billing.Networks, network string) bool {
	switch strings.ToLower(network) {
	case railFedNow:
		return n.FedNow != nil
	case railRTP:
		return n.RTP != nil
	case railACH:
		return n.ACH != nil
	case railSolana:
		return n.Solana != nil
	default:
		// The create refuses every other network by name (ADR-0012), so no QR Code offers one.
		return false
	}
}

func offers(methods []billing.PaymentMethod, network string) bool {
	for _, m := range methods {
		if publishes(m.Networks, network) {
			return true
		}
	}
	return false
}

// RefuseByName is what a notification is refused for, whatever the QR Code's status and before any
// transition (S10.4-AC3), in the Java build's order and with its field: nil when none applies. It
// reads the QR Code as stored (req is its request_json) at the payee's clock.
//
//   - an expectedDate already past;
//   - a chain without its blockchain object;
//   - a QR Code created without paymentNotification, or whose notifications go to another service
//     (kind EXTERNAL, ADR-0006);
//   - an amount or a tip that is not positive;
//   - a network the QR Code does not offer, matched whatever its case and echoed as sent (I-1, I-4);
//   - blockchain data on a bank rail;
//   - ACH without payer.info or expectedDate (an ACH transfer settles later; without payer.info the
//     holder is nobody, ADR-0036).
//
// The currency, the destination address, the amount and the validity are the acceptance gate's
// (Gate): a pre-commit's only, because a post-commit reports money that has already moved and is
// never judged on amount or time (ADR-0036).
func RefuseByName(req contract.CreateRequest, methods []billing.PaymentMethod, data *contract.NotificationData, now time.Time) *domain.Refusal {
	pay := data.Payment.Value
	network := pay.Network.Value
	if data.ExpectedDate.IsSet() && data.ExpectedDate.Value.Before(now) {
		return refusal("paymentNotification.data.expectedDate", "ExpectedDate must not be less than the current date.")
	}
	bank := bankRail(network)
	if !bank && !data.Blockchain.IsSet() {
		return refusal("", fmt.Sprintf("Blockchain data is required for a notification on the %s network.", network))
	}
	if !req.PaymentNotification.IsSet() {
		return refusal("paymentNotification", "is not configured for this QR Code.")
	}
	if strings.EqualFold(req.PaymentNotification.Value.Kind.Value, "EXTERNAL") {
		return refusal("paymentNotification.kind", "must not be EXTERNAL.")
	}
	if pay.Amount.Value <= 0 {
		return refusal(fieldAmount, "must be greater than zero.")
	}
	if pay.TipAmount.IsSet() && pay.TipAmount.Value <= 0 {
		return refusal(fieldTipAmount, "must be greater than zero.")
	}
	return refuseNetwork(methods, data)
}

// refuseNetwork checks the notified rail against the QR Code's methods, and what each rail must
// and must not carry.
func refuseNetwork(methods []billing.PaymentMethod, data *contract.NotificationData) *domain.Refusal {
	network := data.Payment.Value.Network.Value
	switch rail := strings.ToLower(network); rail {
	case railFedNow, railRTP, railACH:
		kind := "Instant Payments"
		if rail == railACH {
			kind = "ACH payments"
		}
		if data.Blockchain.IsSet() {
			return refusal("paymentNotification.data.blockchain", "Blockchain data must not be informed for "+kind+".")
		}
		if !offers(methods, network) {
			return refusal(fieldNetwork, notOffered(methods, rail, network))
		}
		if rail != railACH {
			return nil
		}
		if !data.Payer.IsSet() || strings.TrimSpace(data.Payer.Value.Info.Value) == "" {
			return refusal("paymentNotification.data.payer.info", "Payer info is required for ACH payments.")
		}
		if !data.ExpectedDate.IsSet() {
			return refusal("paymentNotification.data.expectedDate", "Expected date is required for ACH payments.")
		}
		return nil
	default:
		if !offers(methods, network) {
			return refusal(fieldNetwork, fmt.Sprintf("This QRCode does not support the %s network.", network))
		}
		return nil
	}
}

// notOffered is the Java build's words for a bank rail the QR Code does not offer. Java takes
// FedNow and RTP together, so a QR Code offering only one of them accepted a notification on the
// other; here each rail is its own, and the other is refused naming it.
func notOffered(methods []billing.PaymentMethod, rail, network string) string {
	switch {
	case rail == railACH:
		return "This QRCode does not support ACH network."
	case offers(methods, railFedNow) || offers(methods, railRTP):
		return fmt.Sprintf("This QRCode does not support the %s network.", network)
	default:
		return "This QRCode does not support Instant Payment networks."
	}
}
