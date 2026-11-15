// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package domain

import (
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
)

// Decision is what a trigger decided, for the store to apply in one transaction (ADR-0025), in
// this order: the row, then the notification, then the events.
type Decision struct {
	// Row is the row's new values; the row as read when nothing is written.
	Row Row
	// Write is true when Row must be written. Every accepted transition sets revised_at.
	Write bool
	// Notification is the accepted notification to append to qr_code_notification before the
	// events, or nil. Its receipt's deviations are what the caller logs as a warning.
	Notification *NotificationRecord
	// Events are the events to insert, in order, each built by BuildEvent.
	Events []Event
	// Status is the effective status the answer carries.
	Status Status
	// Refusal, when set, is the answer. Write may still be true: a holder's retry refused by the
	// acceptance gate releases the reservation in the same transaction (ADR-0036).
	Refusal *Refusal
}

// Err returns the refusal as an error, or nil when the trigger was accepted.
func (d Decision) Err() error {
	if d.Refusal == nil {
		return nil
	}
	return d.Refusal
}

func unchanged(row Row, now time.Time) Decision {
	return Decision{Row: row, Status: EffectiveStatus(row, now)}
}

func refused(row Row, now time.Time, r *Refusal) Decision {
	d := unchanged(row, now)
	d.Refusal = r
	return d
}

func written(row Row, now time.Time) Decision {
	row.RevisedAt = now
	return Decision{Row: row, Write: true, Status: EffectiveStatus(row, now)}
}

// emitted returns d with one more event, built from d's row.
func emitted(d Decision, eventType EventType, last *NotificationRecord, now time.Time, cause Cause) (Decision, error) {
	e, err := BuildEvent(eventType, d.Row, last, now, cause)
	if err != nil {
		return Decision{}, err
	}
	d.Events = append(d.Events, e)
	return d, nil
}

// ------------------------------------------------------------------ create, PATCH, location

// NewQRCode is what a create writes besides its lifecycle columns.
type NewQRCode struct {
	ID          uuid.UUID
	LocationID  uuid.UUID
	ValidUntil  time.Time
	RequestJSON json.RawMessage
}

// Create decides a create (X9-MAP-001): ACTIVE, revision 0, no reservation, no event.
func Create(q NewQRCode, now time.Time) Decision {
	return Decision{
		Row: Row{
			ID:            q.ID,
			LocationID:    q.LocationID,
			ValidUntil:    q.ValidUntil,
			Status:        StatusActive,
			ReservedUntil: NoLapse,
			RequestJSON:   q.RequestJSON,
			CreatedAt:     now,
			RevisedAt:     now,
		},
		Write:  true,
		Status: StatusActive,
	}
}

// Patch decides whether a PATCH may apply (X9-MAP-002): If-Match first, then the stored status
// must be ACTIVE or PAYMENT_INITIATED. An accepted PATCH moves the revision, the only transition
// that does (ADR-0016), keeps the status and emits nothing. The caller applies the content to
// Row (request_json, valid_until, location) and writes the audit row (ADR-0024).
func Patch(row Row, ifMatch *string, now time.Time) Decision {
	if r := requireETag(row, now, ifMatch); r != nil {
		return refused(row, now, r)
	}
	if row.Status.Closed() {
		return refused(row, now, businessRule("",
			"QR code with id "+formatID(row.ID)+" must be active to be updated."))
	}
	row.Revision++
	return written(row, now)
}

// ReleaseLocation decides whether the QR Code holding a location gives it up to a create or a
// PATCH (X9-MAP-050): only when it is not live (HolderIsLive, ADR-0024). It then takes the fresh
// location the caller minted; its status and revision do not move and nothing is emitted. The
// creditor check is the caller's, before this.
func ReleaseLocation(holder Row, fresh uuid.UUID, now time.Time) Decision {
	if HolderIsLive(holder, now) {
		return refused(holder, now, businessRule("",
			"The QRCode needs to be inactive to reuse location. Current status: "+string(holder.Status)))
	}
	holder.LocationID = fresh
	return written(holder, now)
}

// ------------------------------------------------------------------ the status-update API

// StatusUpdate is a PUT /api/v1/payment-request/{id}/status-update.
type StatusUpdate struct {
	Status Status
	// EndToEndID and PaymentNetwork are paymentDetails; nil when absent.
	EndToEndID     *string
	PaymentNetwork *string
	// IfMatch is the parsed If-Match tag, nil for an unconditional request.
	IfMatch *string
}

// UpdateStatus decides a status-update (X9-MAP-010 to -013), the payment system's control over a
// QR Code. In Java's order: If-Match; a payment network the QR Code does not offer; the same
// status, stored and effective, as a retry (200, nothing written, no event); then the transition.
// last is the last stored notification, the body of the event when there is one.
func UpdateStatus(row Row, req StatusUpdate, last *NotificationRecord, now time.Time, s Settings) (Decision, error) {
	if r := requireETag(row, now, req.IfMatch); r != nil {
		return refused(row, now, r), nil
	}
	if req.PaymentNetwork != nil {
		view, err := readRequest(row.RequestJSON)
		if err != nil {
			return Decision{}, err
		}
		if !view.offers(*req.PaymentNetwork) {
			return refused(row, now, businessRule("",
				"Network "+*req.PaymentNetwork+" is not a valid payment method.")), nil
		}
	}

	effective := EffectiveStatus(row, now)
	if row.Status == req.Status && effective == req.Status {
		return unchanged(row, now), nil
	}
	withDetails := req.EndToEndID != nil || req.PaymentNetwork != nil

	switch req.Status {
	case StatusPaid:
		return pay(row, req, last, now)
	case StatusPaymentInitiated:
		if effective != StatusActive {
			return refused(row, now, conflict(effective,
				"The QRCode needs to be active to have a payment initiated. Current status: "+string(effective))), nil
		}
		if withDetails {
			return refused(row, now, businessRule("", "paymentDetails must be not informed when initiating a payment.")), nil
		}
		// Unattributed: the payee records a reservation it made where this service cannot see, so
		// no payer may adopt it (ADR-0021).
		reserve(&row, Holder{}, now, s)
		d := written(row, now)
		return emitted(d, EventInitiated, last, now, Cause{})
	case StatusCancelled:
		if row.Status.Closed() {
			return refused(row, now, businessRule("",
				"The QRCode needs to be active to be cancelled. Current status: "+string(row.Status))), nil
		}
		if withDetails {
			return refused(row, now, businessRule("", "paymentDetails must be not informed when cancelling a QR Code.")), nil
		}
		row.Status = StatusCancelled
		clearReservation(&row)
		row.EndToEndID, row.PaymentNetwork = "", ""
		d := written(row, now)
		return emitted(d, EventCancelled, last, now, Cause{})
	case StatusActive:
		// A lapsed reservation already reads ACTIVE: there is nothing to reactivate (ADR-0019).
		if effective != StatusPaymentInitiated {
			return refused(row, now, businessRule("", "Only QR Codes with PAYMENT_INITIATED status can be reactivated.")), nil
		}
		if withDetails {
			return refused(row, now, businessRule("", "paymentDetails must be not informed when reactivating a QR Code.")), nil
		}
		row.Status = StatusActive
		clearReservation(&row)
		row.EndToEndID, row.PaymentNetwork = "", ""
		return written(row, now), nil
	default:
		return refused(row, now, businessRule("", "Status "+string(req.Status)+" is not allowed.")), nil
	}
}

// pay is status-update PAID: from ACTIVE or PAYMENT_INITIATED, the stored status (ADR-0020). The
// only way a QR Code becomes PAID (ADR-0036).
func pay(row Row, req StatusUpdate, last *NotificationRecord, now time.Time) (Decision, error) {
	if row.Status.Closed() {
		return refused(row, now, businessRule("",
			"The QRCode needs to be active to be paid. Current status: "+string(row.Status))), nil
	}
	if req.EndToEndID == nil || req.PaymentNetwork == nil {
		return refused(row, now, businessRule("",
			"Both endToEndId and paymentNetwork fields must be provided when marking as paid.")), nil
	}
	row.Status = StatusPaid
	clearReservation(&row)
	row.EndToEndID, row.PaymentNetwork = *req.EndToEndID, *req.PaymentNetwork
	d := written(row, now)
	return emitted(d, EventCleared, last, now, Cause{})
}

// ------------------------------------------------------------------ a payer's notifications

// Notify decides a payer's notification by its phase (ADR-0004, ADR-0036): PreCommit, PostCommit
// or Release. gate is the acceptance gate's refusal of a pre-commit at the payee's clock, nil when
// it would accept; it is consulted only once the status admits the pre-commit. last is the last
// stored notification before this one.
func Notify(row Row, n Notification, gate *Refusal, last *NotificationRecord, now time.Time, s Settings) (Decision, error) {
	switch n.Phase() {
	case PhasePostCommit:
		return PostCommit(row, n, now)
	case PhaseRelease:
		return Release(row, n, now)
	case PhasePreCommit:
		return PreCommit(row, n, gate, last, now, s)
	default:
		return PreCommit(row, n, gate, last, now, s)
	}
}

// PreCommit decides a notification without a transactionId (X9-MAP-020 to -025, -041), on the
// effective status:
//
//   - SENT without a hash: 400 in every status, nothing to record;
//   - ACTIVE (a lapsed reservation included): judged by the gate; yes takes the reservation for
//     the sender with payment.initiated, no is a 400 that changes nothing;
//   - PAYMENT_INITIATED once a hash is registered: 409, the holder included (§);
//   - PAYMENT_INITIATED held by the sender: a retry, judged again; yes refreshes the window and
//     emits nothing; no releases the QR Code to ACTIVE in the same transaction (payment.failed on
//     a chain, built from the last stored notification: the refused retry is not stored);
//   - PAYMENT_INITIATED held by anyone else, or by nobody identifiable: 409;
//   - PAID or CANCELLED: 400.
func PreCommit(row Row, n Notification, gate *Refusal, last *NotificationRecord, now time.Time, s Settings) (Decision, error) {
	if n.OnChain() && n.Action == ActionSent && !n.HasTransactionID() {
		return refused(row, now, businessRule("paymentNotification.data.payment.transactionId",
			"Transaction ID is required when blockchain notification action is 'sent'.")), nil
	}

	effective := EffectiveStatus(row, now)
	sender := n.Sender()
	retry := false

	switch effective {
	case StatusActive:
	case StatusPaymentInitiated:
		if row.HashRegistered {
			return refused(row, now, conflict(effective,
				"A transaction has already been reported for this QR Code, so it can no longer be "+
					"pre-committed. Only the payment system ends this reservation.")), nil
		}
		if !row.ReservedBy.SamePayer(sender) {
			return refused(row, now, conflict(effective,
				"Another payer holds this QR Code. Wait for its reservation to end and try again. "+
					"A payer that sends no payer.info cannot be recognised as the holder.")), nil
		}
		retry = true
	case StatusPaid, StatusCancelled:
		return refused(row, now, businessRule("paymentNotification.data.payment",
			fmt.Sprintf("This QR Code is %s, so it can no longer be paid.", effective))), nil
	}

	if gate != nil {
		if !retry {
			return refused(row, now, gate), nil
		}
		row.Status = StatusActive
		clearReservation(&row)
		d := written(row, now)
		d.Refusal = gate
		if n.OnChain() {
			return emitted(d, EventFailed, last, now, Cause{
				Reason:  "the holder's retried pre-commit was refused: " + gate.Message,
				Receipt: &Receipt{ReceivedIn: effective},
			})
		}
		return d, nil
	}

	record := &NotificationRecord{
		Notification: n, Phase: PhasePreCommit, ReceivedAt: now,
		Receipt: Receipt{ReceivedIn: effective},
	}
	if retry {
		// The holder asked again: the window moves. Never restarted once a hash is registered,
		// which a retry cannot reach (409 above).
		row.ReservedUntil = reservationEnd(now, row.ValidUntil, s.ReservationTTL)
		d := written(row, now)
		d.Notification = record
		return d, nil
	}

	reserve(&row, sender, now, s)
	d := written(row, now)
	d.Notification = record
	return emitted(d, EventInitiated, record, now, Cause{Receipt: &record.Receipt})
}

// PostCommit decides a notification carrying a transactionId (X9-MAP-030 to -034, -040, -044): the
// money has moved, so it is accepted in every status and never judged on amount or time.
//
//   - ACTIVE (a lapsed reservation included): the sender takes the reservation, payment.initiated;
//   - PAYMENT_INITIATED, whoever holds it: the holder keeps it;
//   - PAID or CANCELLED: nothing changes, a closed QR Code never reopens.
//
// In every PAYMENT_INITIATED outcome the hash is registered and the window stops (ADR-0036). On a
// chain every post-commit then publishes its own payment.sent; a bank rail never does. Each event
// is flagged RECEIVED_IN_WRONG_STATUS unless the QR Code was held by the sender, and
// ACTION_CONTRADICTS_HASH when the action said otherwise.
func PostCommit(row Row, n Notification, now time.Time) (Decision, error) {
	effective := EffectiveStatus(row, now)
	sender := n.Sender()

	var deviations []Deviation
	if effective != StatusPaymentInitiated || !row.ReservedBy.SamePayer(sender) {
		deviations = append(deviations, DeviationReceivedInWrongStatus)
	}
	if n.ActionContradictsHash() {
		deviations = append(deviations, DeviationActionContradictsHash)
	}
	record := &NotificationRecord{
		Notification: n, Phase: PhasePostCommit, ReceivedAt: now,
		Receipt: Receipt{ReceivedIn: effective, Deviations: deviations},
	}
	cause := Cause{Receipt: &record.Receipt}

	reserved := false
	switch effective {
	case StatusActive:
		row.Status = StatusPaymentInitiated
		row.ReservedBy = sender
		row.ReservedUntil = NoLapse
		row.HashRegistered = true
		reserved = true
	case StatusPaymentInitiated:
		row.ReservedUntil = NoLapse
		row.HashRegistered = true
	case StatusPaid, StatusCancelled:
	}

	d := written(row, now)
	d.Notification = record
	if reserved {
		var err error
		if d, err = emitted(d, EventInitiated, record, now, cause); err != nil {
			return Decision{}, err
		}
	}
	if n.OnChain() {
		return emitted(d, EventSent, record, now, cause)
	}
	return d, nil
}

// Release decides NOT_SENT without a transactionId (X9-MAP-042, -043, -045): the payer gives the
// QR Code back.
//
//   - PAYMENT_INITIATED held by anyone but the sender: 409; a sender without payer.info is never
//     the holder;
//   - held by the sender once a hash is registered: accepted and stored, flagged
//     ACTION_CONTRADICTS_HASH for the warning; the status does not change and nothing is emitted
//     (§: only the payment system ends the reservation now);
//   - held by the sender, or a lapsed reservation of the sender's that nobody has taken since:
//     ACTIVE, reservation cleared, payment.failed;
//   - anything else (ACTIVE with no reservation of the sender's, PAID, CANCELLED): 400.
func Release(row Row, n Notification, now time.Time) (Decision, error) {
	effective := EffectiveStatus(row, now)
	sender := n.Sender()
	holder := row.ReservedBy.SamePayer(sender)

	if effective == StatusPaymentInitiated && !holder {
		return refused(row, now, conflict(effective,
			"This QR Code is held by another payer, so it is not yours to release. "+
				"A payer that sends no payer.info cannot be recognised as the holder.")), nil
	}

	if effective == StatusPaymentInitiated && row.HashRegistered {
		d := written(row, now)
		d.Notification = &NotificationRecord{
			Notification: n, Phase: PhaseRelease, ReceivedAt: now,
			Receipt: Receipt{ReceivedIn: effective, Deviations: []Deviation{DeviationActionContradictsHash}},
		}
		return d, nil
	}

	lapsedButStillTheirs := effective == StatusActive && row.Status == StatusPaymentInitiated && holder
	if effective != StatusPaymentInitiated && !lapsedButStillTheirs {
		return refused(row, now, businessRule("paymentNotification.data.blockchain.action",
			fmt.Sprintf("NOT_SENT releases a reservation, and this QR Code is %s with no reservation of yours.", effective))), nil
	}

	record := &NotificationRecord{
		Notification: n, Phase: PhaseRelease, ReceivedAt: now,
		Receipt: Receipt{ReceivedIn: effective},
	}
	row.Status = StatusActive
	clearReservation(&row)
	d := written(row, now)
	d.Notification = record
	return emitted(d, EventFailed, record, now, Cause{
		Reason:  "payer reported the payment did not proceed",
		Receipt: &record.Receipt,
	})
}
