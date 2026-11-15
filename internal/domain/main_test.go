// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package domain

import (
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

// The one "now" of every decision in these tests. Business time is never the wall clock here.
var t0 = time.Date(2030, time.January, 1, 12, 0, 0, 0, time.UTC)

const ttl = 90 * time.Second

var settings = Settings{ReservationTTL: ttl}

var (
	qrID       = uuid.MustParse("01938e7a-0000-7000-8000-000000000001")
	locationID = uuid.MustParse("6f1c2d3e-4b5a-4c6d-8e7f-000000000002")
	freshLoc   = uuid.MustParse("6f1c2d3e-4b5a-4c6d-8e7f-000000000003")
)

// The first payment method is USDC on Solana, the second USD on FedNow, RTP and ACH.
const requestJSON = `{"bill":{"invoice":{"number":"INV-1"},"order":{"number":"ORD-1"}},` +
	`"paymentMethods":[` +
	`{"currency":"USDC","amount":1000,"networks":{"solana":{"recipient":"R"}}},` +
	`{"currency":"USD","amount":1000,"networks":{"fednow":{"accountNumber":"1"},"rtp":{"accountNumber":"1"},"ach":{"accountNumber":"1"}}}]}`

var (
	holderA      = Holder{PayerInfo: "holder@example.com", SignerSubject: "CN=payer-a"}
	otherInfo    = Holder{PayerInfo: "other@example.com", SignerSubject: "CN=payer-a"}
	otherCert    = Holder{PayerInfo: "holder@example.com", SignerSubject: "CN=payer-b"}
	noPayerInfoA = Holder{SignerSubject: "CN=payer-a"}
)

// ------------------------------------------------------------------ rows in a given status

func active() Row {
	return Row{
		ID: qrID, LocationID: locationID, ValidUntil: t0.Add(24 * time.Hour),
		Status: StatusActive, Revision: 3, LockVersion: 7, ReservedUntil: NoLapse,
		RequestJSON: []byte(requestJSON),
		CreatedAt:   t0.Add(-time.Hour), RevisedAt: t0.Add(-time.Hour),
	}
}

// held is PAYMENT_INITIATED, held by h, inside its window.
func held(h Holder) Row {
	r := active()
	r.Status = StatusPaymentInitiated
	r.ReservedBy = h
	r.ReservedUntil = t0.Add(30 * time.Second)
	return r
}

// lapsed is held by h, past its window: it reads ACTIVE.
func lapsed(h Holder) Row {
	r := held(h)
	r.ReservedUntil = t0.Add(-time.Second)
	return r
}

// withHash is held by h once a hash is registered: the window has stopped.
func withHash(h Holder) Row {
	r := held(h)
	r.ReservedUntil = NoLapse
	r.HashRegistered = true
	return r
}

// unattributed is reserved through the status-update API: held by nobody identifiable.
func unattributed() Row { return held(Holder{}) }

func closed(s Status) Row {
	r := active()
	r.Status = s
	if s == StatusPaid {
		r.EndToEndID, r.PaymentNetwork = "E2E-PAID", "solana"
	}
	return r
}

// ------------------------------------------------------------------ a payer's notifications

func payer(h Holder) *string {
	if h.PayerInfo == "" {
		return nil
	}
	return new(h.PayerInfo)
}

// chain is a Solana notification from h with the given action, no transactionId.
func chain(h Holder, action Action) Notification {
	return Notification{
		Network: "solana", Currency: "USDC", Amount: 1100, TipAmount: new(int64(100)),
		PayerInfo: payer(h), Action: action, SignerSubject: h.SignerSubject,
	}
}

func bank(h Holder, network string) Notification {
	return Notification{
		Network: network, Currency: "USD", Amount: 1000,
		PayerInfo: payer(h), SignerSubject: h.SignerSubject,
	}
}

func withTx(n Notification, tx string) Notification {
	n.TransactionID = tx
	return n
}

// last is a stored pre-commit from the holder, the "last stored notification" of a held row.
func lastFrom(h Holder) *NotificationRecord {
	return &NotificationRecord{
		Notification: chain(h, ActionPaymentInitiated), Phase: PhasePreCommit,
		ReceivedAt: t0.Add(-time.Minute), Receipt: Receipt{ReceivedIn: StatusActive},
	}
}

func gateNo() *Refusal {
	return businessRule("paymentNotification.data.payment.amount", "must be 1000 at this instant.")
}
