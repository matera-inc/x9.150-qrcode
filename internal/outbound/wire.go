// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package outbound

import (
	"strings"

	"github.com/matera-inc/x9.150-qrcode/internal/contract"
)

// The notification as it goes on the wire (ANSI X9.150-2026 §2, PaymentNotificationData): the QR
// Code id inside payment, and the members in the contract's order, as Java's generated DTO writes
// them. Only the members the contract declares are written, and none that is null (Jackson's
// NON_NULL), so what is signed is exactly what a conformant payee reads.
type wireNotification struct {
	Payment      wirePayment     `json:"payment"`
	Payer        *wirePayer      `json:"payer,omitzero"`
	ExpectedDate *string         `json:"expectedDate,omitzero"`
	Blockchain   *wireBlockchain `json:"blockchain,omitzero"`
}

type wirePayment struct {
	QRCodeID  string `json:"qrcodeId"`
	Amount    int64  `json:"amount"`
	TipAmount *int64 `json:"tipAmount,omitzero"`
	Currency  string `json:"currency"`
	Network   string `json:"network"`
	// TransactionID is written only when it is not blank: the payee infers the phase from its
	// absence (ADR-0004), and a blank one is no reference.
	TransactionID *string `json:"transactionId,omitzero"`
}

type wirePayer struct {
	Info *string `json:"info,omitzero"`
}

type wireBlockchain struct {
	Action string `json:"action"`
	From   string `json:"from"`
	To     string `json:"to"`
}

// wireOf is the wire notification of a request the contract has accepted: payment, its required
// members, and blockchain's are present.
func wireOf(n contract.NotificationData) wireNotification {
	p := n.Payment.Value
	w := wireNotification{Payment: wirePayment{
		QRCodeID: p.QRCodeID.Value,
		Amount:   p.Amount.Value,
		Currency: p.Currency.Value,
		Network:  p.Network.Value,
	}}
	if p.TipAmount.IsSet() {
		tip := p.TipAmount.Value
		w.Payment.TipAmount = &tip
	}
	if strings.TrimSpace(p.TransactionID.Value) != "" {
		id := p.TransactionID.Value
		w.Payment.TransactionID = &id
	}
	if n.Payer.IsSet() {
		w.Payer = &wirePayer{}
		if n.Payer.Value.Info.IsSet() {
			info := n.Payer.Value.Info.Value
			w.Payer.Info = &info
		}
	}
	if n.ExpectedDate.IsSet() {
		at := contract.FormatInstant(n.ExpectedDate.Value)
		w.ExpectedDate = &at
	}
	if n.Blockchain.IsSet() {
		b := n.Blockchain.Value
		w.Blockchain = &wireBlockchain{Action: b.Action.Value, From: b.From.Value, To: b.To.Value}
	}
	return w
}
