// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package domain

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

// Action is blockchain.action as the payer declared it; "" when the notification has no blockchain
// object (a bank rail).
type Action string

// The blockchain actions (BlockchainActionEnum in openapi.yaml).
const (
	ActionNone             Action = ""
	ActionPaymentInitiated Action = "PAYMENT_INITIATED"
	ActionSent             Action = "SENT"
	ActionNotSent          Action = "NOT_SENT"
)

// Phase is what a notification is taken as, decided from the evidence (ADR-0004, ADR-0036). The
// values are those of qr_code_notification.phase.
type Phase string

// The three phases.
const (
	// PhasePreCommit has no transactionId: "may I pay this?".
	PhasePreCommit Phase = "PRE_COMMIT"
	// PhasePostCommit carries a transactionId: the money has moved, whatever the action says.
	PhasePostCommit Phase = "POST_COMMIT"
	// PhaseRelease is NOT_SENT without a transactionId: the payer gives the QR Code back.
	PhaseRelease Phase = "RELEASE"
)

// Notification is a payer's notification as the transitions read it. The endpoint (Story 10.4)
// has already verified it and refused what is malformed for this QR Code.
type Notification struct {
	// Network and Currency are $.payment.network and $.payment.currency as received.
	Network  string
	Currency string
	// Amount is $.payment.amount in minor units, tip included.
	Amount int64
	// TipAmount is $.payment.tipAmount, nil when absent.
	TipAmount *int64
	// TransactionID is $.payment.transactionId; blank means absent.
	TransactionID string
	// PayerInfo is $.payer.info, nil when absent.
	PayerInfo *string
	Action    Action
	// SignerSubject is the subject of the certificate that signed the notification.
	SignerSubject string
}

// HasTransactionID reports whether the payer reported a transaction: an End-to-End ID, a Trace
// Number or a transaction hash.
func (n Notification) HasTransactionID() bool {
	return strings.TrimSpace(n.TransactionID) != ""
}

// Phase is decided from the evidence, on every rail (ADR-0004, ADR-0036): a transactionId makes a
// post-commit, NOT_SENT included; NOT_SENT without one is a release; anything else is a
// pre-commit.
func (n Notification) Phase() Phase {
	if n.HasTransactionID() {
		return PhasePostCommit
	}
	if n.Action == ActionNotSent {
		return PhaseRelease
	}
	return PhasePreCommit
}

// ActionContradictsHash reports PAYMENT_INITIATED ("may I pay?") or NOT_SENT ("I did not pay")
// carrying a hash that shows the money has moved: bad payer behaviour (ADR-0036).
func (n Notification) ActionContradictsHash() bool {
	return (n.Action == ActionPaymentInitiated || n.Action == ActionNotSent) && n.HasTransactionID()
}

// OnChain reports whether the notification's rail settles on a public ledger, the only kind that
// emits payment.sent and payment.failed. Every rail but FedNow, RTP and ACH is a chain; the
// endpoint has already refused the networks this deployment does not interpret.
func (n Notification) OnChain() bool {
	switch strings.ToLower(n.Network) {
	case "fednow", "rtp", "ach":
		return false
	default:
		return true
	}
}

// Sender is who sent the notification, as a Holder.
func (n Notification) Sender() Holder {
	h := Holder{SignerSubject: n.SignerSubject}
	if n.PayerInfo != nil {
		h.PayerInfo = *n.PayerInfo
	}
	return h
}

// Deviation is how a payer broke the protocol (ADR-0036). The list is closed: a new code is added
// to the ADR first, never invented at a call site.
type Deviation string

// The two deviations.
const (
	// DeviationReceivedInWrongStatus: a post-commit reached a QR Code that was not
	// PAYMENT_INITIATED held by this payer.
	DeviationReceivedInWrongStatus Deviation = "RECEIVED_IN_WRONG_STATUS"
	// DeviationActionContradictsHash: blockchain.action is PAYMENT_INITIATED or NOT_SENT, yet the
	// money has moved.
	DeviationActionContradictsHash Deviation = "ACTION_CONTRADICTS_HASH"
)

// ErrUnknownDeviation is returned for a code outside ADR-0036's closed list.
var ErrUnknownDeviation = errors.New("unknown deviation")

// FormatDeviations writes deviations as the deviations columns store them: comma-separated, ""
// for none.
func FormatDeviations(deviations []Deviation) string {
	parts := make([]string, len(deviations))
	for i, d := range deviations {
		parts[i] = string(d)
	}
	return strings.Join(parts, ",")
}

// ParseDeviations reads a deviations column. "" is none, returned as nil: deviations are absent
// rather than empty.
func ParseDeviations(column string) ([]Deviation, error) {
	if column == "" {
		return nil, nil
	}
	parts := strings.Split(column, ",")
	out := make([]Deviation, 0, len(parts))
	for _, p := range parts {
		switch d := Deviation(p); d {
		case DeviationReceivedInWrongStatus, DeviationActionContradictsHash:
			out = append(out, d)
		default:
			return nil, fmt.Errorf("%w: %q", ErrUnknownDeviation, p)
		}
	}
	return out, nil
}

// Receipt is how a payer's notification arrived: the QR Code's effective status at receipt and
// the deviations, nil when the payer followed the rules (ADR-0036).
type Receipt struct {
	ReceivedIn Status
	Deviations []Deviation
}

// NotificationRecord is an accepted notification as qr_code_notification stores it. Which stored
// one an event body is built from is EventBodySource's choice (ADR-0035).
type NotificationRecord struct {
	Notification Notification
	Phase        Phase
	ReceivedAt   time.Time
	Receipt      Receipt
}

// EventBodySource is the stored notification an event body is built from (ADR-0035), given every
// notification stored for the QR Code in arrival order:
//
//  1. the latest one that carries a transactionId, so that the hash a payer reported stays in the
//     body even after a later notification without one was stored (a NOT_SENT once a hash is
//     registered is stored, flagged ACTION_CONTRADICTS_HASH; it must not erase the hash);
//  2. else the last one stored;
//  3. else nil, and BuildEvent uses the first payment method.
//
// The store reads them all and leaves the choice here, so that a change to the rule changes this
// function and nothing in SQL. The result is a copy.
func EventBodySource(stored []NotificationRecord) *NotificationRecord {
	if len(stored) == 0 {
		return nil
	}
	for i := len(stored) - 1; i >= 0; i-- {
		if stored[i].Notification.HasTransactionID() {
			found := stored[i]
			return &found
		}
	}
	last := stored[len(stored)-1]
	return &last
}
