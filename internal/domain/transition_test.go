// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package domain

import (
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
)

// S9.3-AC4: one table, a case for every key of ADR-0035's map (X9-MAP-001 to -051) and every cell
// of ADR-0036's matrix, the § and ¶ amendments included. TestEveryMapKeyAndMatrixCellHasACase
// proves the coverage against the ADR's text.

// source is where an event's body must come from (ADR-0035).
type source int

const (
	fromSent   source = iota + 1 // the notification this trigger carries, stored before the event
	fromLast                     // the last notification stored before this trigger
	fromMethod                   // the first payment method and the payment details
)

type wantEvent struct {
	typ     EventType
	from    source
	receipt *Receipt // nil: no receivedInStatus, no deviations (a status-update event)
	reason  string   // a substring of the reason; "" means no reason
}

type want struct {
	status   Status // the answer's (effective) status
	stored   Status // the stored status after
	write    bool
	revision int // the revision's move: 1 for PATCH, 0 for everything else

	refusal Kind
	field   string
	text    string // a substring of the refusal's message

	events []wantEvent
	record *Receipt // the stored notification's receipt; nil: nothing stored

	holder *Holder
	until  *time.Time
	hash   *bool
}

type mapCase struct {
	key   string   // X9-MAP-NNN
	cells []string // ADR-0036 matrix cells this case is
	name  string
	row   Row
	sent  *Notification
	last  *NotificationRecord
	run   func(Row) (Decision, error)
	want  want
}

func pre(n Notification, gate *Refusal, last *NotificationRecord, s Settings) func(Row) (Decision, error) {
	return func(r Row) (Decision, error) { return Notify(r, n, gate, last, t0, s) }
}

func notify(n Notification) func(Row) (Decision, error) {
	return func(r Row) (Decision, error) { return Notify(r, n, nil, nil, t0, settings) }
}

func update(req StatusUpdate, last *NotificationRecord, s Settings) func(Row) (Decision, error) {
	return func(r Row) (Decision, error) { return UpdateStatus(r, req, last, t0, s) }
}

func read(r Row) (Decision, error) { return unchanged(r, t0), nil }

func paid(network string) StatusUpdate {
	return StatusUpdate{Status: StatusPaid, EndToEndID: new("E2E-MAP-PAID"), PaymentNetwork: new(network)}
}

var (
	riws    = DeviationReceivedInWrongStatus
	ach     = DeviationActionContradictsHash
	window  = t0.Add(ttl)
	noLapse = NoLapse
	yes     = true
	no      = false
)

func rcpt(s Status, d ...Deviation) *Receipt { return &Receipt{ReceivedIn: s, Deviations: d} }

func mapCases() []mapCase {
	nA := chain(holderA, ActionPaymentInitiated)
	postA := withTx(chain(holderA, ActionSent), "TX-1")
	postOther := withTx(chain(otherInfo, ActionSent), "TX-2")
	postNoInfo := withTx(chain(noPayerInfoA, ActionSent), "TX-3")
	piHashA := withTx(chain(holderA, ActionPaymentInitiated), "TX-4")
	piHashOther := withTx(chain(otherInfo, ActionPaymentInitiated), "TX-5")
	sentNoHashA := chain(holderA, ActionSent)
	sentNoHashOther := chain(otherInfo, ActionSent)
	notSentA := chain(holderA, ActionNotSent)
	notSentOther := chain(otherInfo, ActionNotSent)
	notSentNoInfo := chain(noPayerInfoA, ActionNotSent)
	notSentTxA := withTx(chain(holderA, ActionNotSent), "TX-6")
	notSentTxOther := withTx(chain(otherInfo, ActionNotSent), "TX-7")
	lastA := lastFrom(holderA)
	ttl0 := Settings{ReservationTTL: 0}

	cases := []mapCase{
		// ============================================================ create, PATCH
		{key: "X9-MAP-001", name: "create is ACTIVE, revision 0, no event",
			run: func(Row) (Decision, error) {
				return Create(NewQRCode{ID: qrID, LocationID: locationID, ValidUntil: t0.Add(time.Hour), RequestJSON: []byte(requestJSON)}, t0), nil
			},
			want: want{status: StatusActive, stored: StatusActive, write: true, until: &noLapse, holder: &Holder{}, hash: &no}},
		{key: "X9-MAP-002", name: "PATCH from ACTIVE moves the revision", row: active(),
			run:  func(r Row) (Decision, error) { return Patch(r, nil, t0), nil },
			want: want{status: StatusActive, stored: StatusActive, write: true, revision: 1}},
		{key: "X9-MAP-002", name: "PATCH from PAYMENT_INITIATED keeps the reservation", row: held(holderA),
			run:  func(r Row) (Decision, error) { return Patch(r, nil, t0), nil },
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, revision: 1, holder: &holderA}},
		{key: "X9-MAP-002", name: "PATCH with the current If-Match", row: held(holderA),
			run:  func(r Row) (Decision, error) { return Patch(r, new("3-PAYMENT_INITIATED"), t0), nil },
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, revision: 1}},
		{key: "X9-MAP-002", name: "PATCH on a lapsed reservation compares the effective ETag", row: lapsed(holderA),
			run:  func(r Row) (Decision, error) { return Patch(r, new("3-ACTIVE"), t0), nil },
			want: want{status: StatusActive, stored: StatusPaymentInitiated, write: true, revision: 1}},
		{key: "X9-MAP-002", name: "PATCH with a stale If-Match is 412", row: held(holderA),
			run:  func(r Row) (Decision, error) { return Patch(r, new("3-ACTIVE"), t0), nil },
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, refusal: KindPreconditionFailed, text: "expected 3-ACTIVE, found 3-PAYMENT_INITIATED"}},
		{key: "X9-MAP-002", name: "PATCH on PAID is refused", row: closed(StatusPaid),
			run:  func(r Row) (Decision, error) { return Patch(r, nil, t0), nil },
			want: want{status: StatusPaid, stored: StatusPaid, refusal: KindBusinessRule, text: "QR code with id 01938E7A000070008000000000000001 must be active to be updated."}},
		{key: "X9-MAP-002", name: "PATCH on CANCELLED is refused", row: closed(StatusCancelled),
			run:  func(r Row) (Decision, error) { return Patch(r, nil, t0), nil },
			want: want{status: StatusCancelled, stored: StatusCancelled, refusal: KindBusinessRule, text: "must be active to be updated"}},

		// ============================================================ status-update PAYMENT_INITIATED
		{key: "X9-MAP-010", cells: []string{"su-init/ACTIVE"}, name: "reserves, unattributed, body from the first method", row: active(),
			run: update(StatusUpdate{Status: StatusPaymentInitiated}, nil, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true,
				events: []wantEvent{{typ: EventInitiated, from: fromMethod}}, holder: &Holder{}, until: &window, hash: &no}},
		{key: "X9-MAP-010", cells: []string{"su-init/ACTIVE lapsed"}, name: "a lapsed reservation reads ACTIVE: reserved again (BUG-002)", row: lapsed(holderA), last: lastA,
			run: update(StatusUpdate{Status: StatusPaymentInitiated}, lastA, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true,
				events: []wantEvent{{typ: EventInitiated, from: fromLast}}, holder: &Holder{}, until: &window}},
		{key: "X9-MAP-010", name: "the window is capped by validUntil", row: func() Row { r := active(); r.ValidUntil = t0.Add(10 * time.Second); return r }(),
			run: update(StatusUpdate{Status: StatusPaymentInitiated}, nil, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true,
				events: []wantEvent{{typ: EventInitiated, from: fromMethod}}, until: new(t0.Add(10 * time.Second))}},
		{key: "X9-MAP-010", name: "ttl-seconds 0: no window", row: active(),
			run: update(StatusUpdate{Status: StatusPaymentInitiated}, nil, ttl0),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true,
				events: []wantEvent{{typ: EventInitiated, from: fromMethod}}, until: &noLapse, hash: &no}},
		{key: "X9-MAP-010", cells: []string{"su-init/held ¶"}, name: "held by a payer: a retry, nothing written", row: held(holderA), last: lastA,
			run:  update(StatusUpdate{Status: StatusPaymentInitiated}, lastA, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated}},
		{key: "X9-MAP-010", cells: []string{"su-init/unattributed ¶"}, name: "unattributed: a retry, nothing written", row: unattributed(),
			run:  update(StatusUpdate{Status: StatusPaymentInitiated}, nil, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated}},
		{key: "X9-MAP-010", cells: []string{"su-init/closed"}, name: "on PAID: 409", row: closed(StatusPaid),
			run:  update(StatusUpdate{Status: StatusPaymentInitiated}, nil, settings),
			want: want{status: StatusPaid, stored: StatusPaid, refusal: KindConflict, text: "needs to be active to have a payment initiated. Current status: PAID"}},
		{key: "X9-MAP-010", cells: []string{"su-init/closed"}, name: "on CANCELLED: 409", row: closed(StatusCancelled),
			run:  update(StatusUpdate{Status: StatusPaymentInitiated}, nil, settings),
			want: want{status: StatusCancelled, stored: StatusCancelled, refusal: KindConflict, text: "Current status: CANCELLED"}},
		{key: "X9-MAP-010", name: "with payment details: 400", row: active(),
			run:  update(StatusUpdate{Status: StatusPaymentInitiated, EndToEndID: new("E2E")}, nil, settings),
			want: want{status: StatusActive, stored: StatusActive, refusal: KindBusinessRule, text: "paymentDetails must be not informed when initiating a payment."}},

		// ============================================================ status-update PAID
		{key: "X9-MAP-011", cells: []string{"su-paid/ACTIVE"}, name: "cleared, body from the first method and the details", row: active(),
			run:  update(paid("solana"), nil, settings),
			want: want{status: StatusPaid, stored: StatusPaid, write: true, events: []wantEvent{{typ: EventCleared, from: fromMethod}}, until: &noLapse, holder: &Holder{}}},
		{key: "X9-MAP-011", cells: []string{"su-paid/held"}, name: "held: cleared, body from the last notification", row: held(holderA), last: lastA,
			run:  update(paid("solana"), lastA, settings),
			want: want{status: StatusPaid, stored: StatusPaid, write: true, events: []wantEvent{{typ: EventCleared, from: fromLast}}, until: &noLapse, holder: &Holder{}, hash: &no}},
		{key: "X9-MAP-011", cells: []string{"su-paid/held"}, name: "held with a hash: cleared, the reservation ends", row: withHash(holderA), last: lastA,
			run:  update(paid("solana"), lastA, settings),
			want: want{status: StatusPaid, stored: StatusPaid, write: true, events: []wantEvent{{typ: EventCleared, from: fromLast}}, hash: &no, holder: &Holder{}}},
		{key: "X9-MAP-011", name: "a lapsed reservation may still be paid (ADR-0020)", row: lapsed(holderA), last: lastA,
			run:  update(paid("solana"), lastA, settings),
			want: want{status: StatusPaid, stored: StatusPaid, write: true, events: []wantEvent{{typ: EventCleared, from: fromLast}}}},
		{key: "X9-MAP-011", name: "past validUntil: still paid (ADR-0020)", row: func() Row { r := active(); r.ValidUntil = t0.Add(-time.Hour); return r }(),
			run:  update(paid("FedNow"), nil, settings),
			want: want{status: StatusPaid, stored: StatusPaid, write: true, events: []wantEvent{{typ: EventCleared, from: fromMethod}}}},
		{key: "X9-MAP-011", cells: []string{"su-paid/unattributed"}, name: "unattributed: cleared", row: unattributed(),
			run:  update(paid("solana"), nil, settings),
			want: want{status: StatusPaid, stored: StatusPaid, write: true, events: []wantEvent{{typ: EventCleared, from: fromMethod}}}},
		{key: "X9-MAP-011", cells: []string{"su-paid/PAID ¶"}, name: "on PAID: a retry, the first reference kept", row: closed(StatusPaid),
			run:  update(paid("solana"), nil, settings),
			want: want{status: StatusPaid, stored: StatusPaid}},
		{key: "X9-MAP-011", cells: []string{"su-paid/CANCELLED"}, name: "on CANCELLED: 400", row: closed(StatusCancelled),
			run:  update(paid("solana"), nil, settings),
			want: want{status: StatusCancelled, stored: StatusCancelled, refusal: KindBusinessRule, text: "The QRCode needs to be active to be paid. Current status: CANCELLED"}},
		{key: "X9-MAP-011", name: "without both details: 400", row: active(),
			run:  update(StatusUpdate{Status: StatusPaid, PaymentNetwork: new("solana")}, nil, settings),
			want: want{status: StatusActive, stored: StatusActive, refusal: KindBusinessRule, text: "Both endToEndId and paymentNetwork fields must be provided when marking as paid."}},
		{key: "X9-MAP-011", name: "a network the QR Code does not offer: 400", row: active(),
			run:  update(paid("zelle"), nil, settings),
			want: want{status: StatusActive, stored: StatusActive, refusal: KindBusinessRule, text: "Network zelle is not a valid payment method."}},
		{key: "X9-MAP-011", name: "a stale If-Match: 412, even for a retry", row: closed(StatusPaid),
			run:  update(StatusUpdate{Status: StatusPaid, EndToEndID: new("E2E"), PaymentNetwork: new("solana"), IfMatch: new("3-ACTIVE")}, nil, settings),
			want: want{status: StatusPaid, stored: StatusPaid, refusal: KindPreconditionFailed}},

		// ============================================================ status-update CANCELLED
		{key: "X9-MAP-012", cells: []string{"su-cancel/ACTIVE"}, name: "cancelled, body from the first method", row: active(),
			run:  update(StatusUpdate{Status: StatusCancelled}, nil, settings),
			want: want{status: StatusCancelled, stored: StatusCancelled, write: true, events: []wantEvent{{typ: EventCancelled, from: fromMethod}}}},
		{key: "X9-MAP-012", cells: []string{"su-cancel/held"}, name: "held: cancelled, body from the last notification", row: held(holderA), last: lastA,
			run:  update(StatusUpdate{Status: StatusCancelled}, lastA, settings),
			want: want{status: StatusCancelled, stored: StatusCancelled, write: true, events: []wantEvent{{typ: EventCancelled, from: fromLast}}, holder: &Holder{}, until: &noLapse}},
		{key: "X9-MAP-012", cells: []string{"su-cancel/unattributed"}, name: "unattributed: cancelled", row: unattributed(),
			run:  update(StatusUpdate{Status: StatusCancelled}, nil, settings),
			want: want{status: StatusCancelled, stored: StatusCancelled, write: true, events: []wantEvent{{typ: EventCancelled, from: fromMethod}}}},
		{key: "X9-MAP-012", cells: []string{"su-cancel/CANCELLED ¶"}, name: "on CANCELLED: a retry", row: closed(StatusCancelled),
			run:  update(StatusUpdate{Status: StatusCancelled}, nil, settings),
			want: want{status: StatusCancelled, stored: StatusCancelled}},
		{key: "X9-MAP-012", cells: []string{"su-cancel/PAID"}, name: "on PAID: 400", row: closed(StatusPaid),
			run:  update(StatusUpdate{Status: StatusCancelled}, nil, settings),
			want: want{status: StatusPaid, stored: StatusPaid, refusal: KindBusinessRule, text: "The QRCode needs to be active to be cancelled. Current status: PAID"}},
		{key: "X9-MAP-012", name: "with payment details: 400", row: active(),
			run:  update(StatusUpdate{Status: StatusCancelled, PaymentNetwork: new("solana")}, nil, settings),
			want: want{status: StatusActive, stored: StatusActive, refusal: KindBusinessRule, text: "paymentDetails must be not informed when cancelling a QR Code."}},

		// ============================================================ status-update ACTIVE
		{key: "X9-MAP-013", cells: []string{"su-active/held"}, name: "reactivates, no event", row: held(holderA), last: lastA,
			run:  update(StatusUpdate{Status: StatusActive}, lastA, settings),
			want: want{status: StatusActive, stored: StatusActive, write: true, holder: &Holder{}, until: &noLapse, hash: &no}},
		{key: "X9-MAP-013", cells: []string{"su-active/held"}, name: "with a hash: the payment system's only way back", row: withHash(holderA), last: lastA,
			run:  update(StatusUpdate{Status: StatusActive}, lastA, settings),
			want: want{status: StatusActive, stored: StatusActive, write: true, holder: &Holder{}, until: &noLapse, hash: &no}},
		{key: "X9-MAP-013", cells: []string{"su-active/unattributed"}, name: "unattributed: reactivates", row: unattributed(),
			run:  update(StatusUpdate{Status: StatusActive}, nil, settings),
			want: want{status: StatusActive, stored: StatusActive, write: true}},
		{key: "X9-MAP-013", cells: []string{"su-active/ACTIVE ¶"}, name: "on ACTIVE: a retry", row: active(),
			run:  update(StatusUpdate{Status: StatusActive}, nil, settings),
			want: want{status: StatusActive, stored: StatusActive}},
		{key: "X9-MAP-013", cells: []string{"su-active/ACTIVE lapsed"}, name: "a lapsed reservation: 400", row: lapsed(holderA),
			run:  update(StatusUpdate{Status: StatusActive}, nil, settings),
			want: want{status: StatusActive, stored: StatusPaymentInitiated, refusal: KindBusinessRule, text: "Only QR Codes with PAYMENT_INITIATED status can be reactivated."}},
		{key: "X9-MAP-013", cells: []string{"su-active/closed"}, name: "on PAID: 400", row: closed(StatusPaid),
			run:  update(StatusUpdate{Status: StatusActive}, nil, settings),
			want: want{status: StatusPaid, stored: StatusPaid, refusal: KindBusinessRule, text: "can be reactivated"}},
		{key: "X9-MAP-013", cells: []string{"su-active/closed"}, name: "on CANCELLED: 400", row: closed(StatusCancelled),
			run:  update(StatusUpdate{Status: StatusActive}, nil, settings),
			want: want{status: StatusCancelled, stored: StatusCancelled, refusal: KindBusinessRule, text: "can be reactivated"}},

		// ============================================================ pre-commit
		{key: "X9-MAP-020", cells: []string{"pre/ACTIVE yes"}, name: "Solana: reserved for the sender", row: active(), sent: &nA,
			run: pre(nA, nil, nil, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusActive),
				events: []wantEvent{{typ: EventInitiated, from: fromSent, receipt: rcpt(StatusActive)}}, holder: &holderA, until: &window, hash: &no}},
		{key: "X9-MAP-020", cells: []string{"pre/ACTIVE yes"}, name: "FedNow: reserved for the sender", row: active(), sent: new(bank(holderA, "FedNow")),
			run: pre(bank(holderA, "FedNow"), nil, nil, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusActive),
				events: []wantEvent{{typ: EventInitiated, from: fromSent, receipt: rcpt(StatusActive)}}, holder: &holderA, until: &window}},
		{key: "X9-MAP-020", name: "RTP: reserved for the sender", row: active(), sent: new(bank(holderA, "RTP")),
			run: pre(bank(holderA, "RTP"), nil, nil, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusActive),
				events: []wantEvent{{typ: EventInitiated, from: fromSent, receipt: rcpt(StatusActive)}}, holder: &holderA}},
		{key: "X9-MAP-020", name: "ACH: reserved for the sender", row: active(), sent: new(bank(holderA, "ACH")),
			run: pre(bank(holderA, "ACH"), nil, nil, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusActive),
				events: []wantEvent{{typ: EventInitiated, from: fromSent, receipt: rcpt(StatusActive)}}, holder: &holderA}},
		{key: "X9-MAP-020", cells: []string{"pre/ACTIVE yes"}, name: "takes over a lapsed reservation, with payment.initiated", row: lapsed(otherInfo), sent: &nA,
			run: pre(nA, nil, nil, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusActive),
				events: []wantEvent{{typ: EventInitiated, from: fromSent, receipt: rcpt(StatusActive)}}, holder: &holderA, until: &window}},
		{key: "X9-MAP-020", name: "ttl-seconds 0: a reservation with no window", row: active(), sent: &nA,
			run: pre(nA, nil, nil, ttl0),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusActive),
				events: []wantEvent{{typ: EventInitiated, from: fromSent, receipt: rcpt(StatusActive)}}, until: &noLapse, hash: &no}},
		{key: "X9-MAP-020", cells: []string{"pre/ACTIVE no"}, name: "refused by the gate: 400, nothing changes", row: active(),
			run:  pre(nA, gateNo(), nil, settings),
			want: want{status: StatusActive, stored: StatusActive, refusal: KindBusinessRule, field: "paymentNotification.data.payment.amount"}},
		{key: "X9-MAP-021", cells: []string{"pre/sender retry yes"}, name: "the holder's retry refreshes the window, no event", row: held(holderA), sent: &nA,
			run:  pre(nA, nil, lastA, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusPaymentInitiated), holder: &holderA, until: &window}},
		{key: "X9-MAP-021", name: "FedNow: the holder's retry", row: held(holderA), sent: new(bank(holderA, "FedNow")),
			run:  pre(bank(holderA, "FedNow"), nil, lastA, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusPaymentInitiated), until: &window}},
		{key: "X9-MAP-021", name: "ttl-seconds 0: a retry keeps no window", row: func() Row { r := held(holderA); r.ReservedUntil = NoLapse; return r }(), sent: &nA,
			run:  pre(nA, nil, lastA, ttl0),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusPaymentInitiated), until: &noLapse}},
		{key: "X9-MAP-022", cells: []string{"pre/sender § hash"}, name: "once a hash is registered: 409, the holder's too", row: withHash(holderA),
			run:  pre(nA, nil, lastA, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, refusal: KindConflict, text: "already been reported"}},
		{key: "X9-MAP-022", name: "once a hash is registered: 409 before the gate", row: withHash(holderA),
			run:  pre(bank(holderA, "FedNow"), gateNo(), lastA, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, refusal: KindConflict}},
		{key: "X9-MAP-023", cells: []string{"pre/sender retry no (chain)"}, name: "Solana: the refused retry releases, payment.failed from the last stored", row: held(holderA), last: lastA,
			run: pre(nA, gateNo(), lastA, settings),
			want: want{status: StatusActive, stored: StatusActive, write: true, refusal: KindBusinessRule, field: "paymentNotification.data.payment.amount",
				events: []wantEvent{{typ: EventFailed, from: fromLast, receipt: rcpt(StatusPaymentInitiated), reason: "the holder's retried pre-commit was refused: must be 1000"}},
				holder: &Holder{}, until: &noLapse}},
		{key: "X9-MAP-023", cells: []string{"pre/sender retry no (bank)"}, name: "FedNow: the refused retry releases, no event", row: held(holderA), last: lastA,
			run:  pre(bank(holderA, "FedNow"), gateNo(), lastA, settings),
			want: want{status: StatusActive, stored: StatusActive, write: true, refusal: KindBusinessRule, holder: &Holder{}, until: &noLapse}},
		{key: "X9-MAP-023", name: "ttl-seconds 0: a refused retry still releases", row: func() Row { r := held(holderA); r.ReservedUntil = NoLapse; return r }(), last: lastA,
			run: pre(nA, gateNo(), lastA, ttl0),
			want: want{status: StatusActive, stored: StatusActive, write: true, refusal: KindBusinessRule,
				events: []wantEvent{{typ: EventFailed, from: fromLast, receipt: rcpt(StatusPaymentInitiated), reason: "refused"}}}},
		{key: "X9-MAP-024", cells: []string{"pre/else"}, name: "another payer.info: 409", row: held(holderA),
			run:  pre(chain(otherInfo, ActionPaymentInitiated), nil, lastA, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, refusal: KindConflict, text: "Another payer holds this QR Code"}},
		{key: "X9-MAP-024", cells: []string{"pre/else"}, name: "another certificate: 409", row: held(holderA),
			run:  pre(chain(otherCert, ActionPaymentInitiated), nil, lastA, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, refusal: KindConflict}},
		{key: "X9-MAP-024", cells: []string{"pre/else"}, name: "a holder without payer.info cannot retry: 409", row: held(noPayerInfoA),
			run:  pre(chain(noPayerInfoA, ActionPaymentInitiated), nil, nil, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, refusal: KindConflict}},
		{key: "X9-MAP-024", cells: []string{"pre/else"}, name: "unattributed matches nobody: 409", row: unattributed(),
			run:  pre(nA, nil, nil, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, refusal: KindConflict}},
		{key: "X9-MAP-024", name: "another payer: 409 before the gate", row: held(holderA),
			run:  pre(chain(otherInfo, ActionPaymentInitiated), gateNo(), lastA, settings),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, refusal: KindConflict}},
		{key: "X9-MAP-025", cells: []string{"pre/closed"}, name: "on PAID: 400", row: closed(StatusPaid),
			run:  pre(nA, nil, nil, settings),
			want: want{status: StatusPaid, stored: StatusPaid, refusal: KindBusinessRule, field: "paymentNotification.data.payment", text: "This QR Code is PAID, so it can no longer be paid."}},
		{key: "X9-MAP-025", cells: []string{"pre/closed"}, name: "on CANCELLED: 400", row: closed(StatusCancelled),
			run:  pre(bank(holderA, "RTP"), nil, nil, settings),
			want: want{status: StatusCancelled, stored: StatusCancelled, refusal: KindBusinessRule, text: "CANCELLED"}},

		// ============================================================ post-commit
		{key: "X9-MAP-030", cells: []string{"post/ACTIVE"}, name: "FedNow on ACTIVE: reserved, window stopped, initiated flagged", row: active(), sent: new(withTx(bank(holderA, "FedNow"), "E2E-1")),
			run: notify(withTx(bank(holderA, "FedNow"), "E2E-1")),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusActive, riws),
				events: []wantEvent{{typ: EventInitiated, from: fromSent, receipt: rcpt(StatusActive, riws)}}, holder: &holderA, until: &noLapse, hash: &yes}},
		{key: "X9-MAP-030", name: "RTP on ACTIVE", row: active(), sent: new(withTx(bank(holderA, "RTP"), "E2E-1")),
			run: notify(withTx(bank(holderA, "RTP"), "E2E-1")),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusActive, riws),
				events: []wantEvent{{typ: EventInitiated, from: fromSent, receipt: rcpt(StatusActive, riws)}}, hash: &yes}},
		{key: "X9-MAP-030", name: "ACH on ACTIVE", row: active(), sent: new(withTx(bank(holderA, "ACH"), "TRACE-1")),
			run: notify(withTx(bank(holderA, "ACH"), "TRACE-1")),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusActive, riws),
				events: []wantEvent{{typ: EventInitiated, from: fromSent, receipt: rcpt(StatusActive, riws)}}, hash: &yes}},
		{key: "X9-MAP-031", cells: []string{"post/ACTIVE"}, name: "Solana on ACTIVE: initiated then sent, both flagged", row: active(), sent: &postA,
			run: notify(postA),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusActive, riws),
				events: []wantEvent{
					{typ: EventInitiated, from: fromSent, receipt: rcpt(StatusActive, riws)},
					{typ: EventSent, from: fromSent, receipt: rcpt(StatusActive, riws)},
				}, holder: &holderA, until: &noLapse, hash: &yes}},
		{key: "X9-MAP-031", name: "on a lapsed reservation: received in ACTIVE", row: lapsed(holderA), sent: &postA,
			run: notify(postA),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusActive, riws),
				events: []wantEvent{
					{typ: EventInitiated, from: fromSent, receipt: rcpt(StatusActive, riws)},
					{typ: EventSent, from: fromSent, receipt: rcpt(StatusActive, riws)},
				}, holder: &holderA, until: &noLapse, hash: &yes}},
		{key: "X9-MAP-031", name: "a second hash from the holder: its own payment.sent, clean", row: withHash(holderA), sent: &postA,
			run: notify(postA),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusPaymentInitiated),
				events: []wantEvent{{typ: EventSent, from: fromSent, receipt: rcpt(StatusPaymentInitiated)}}, hash: &yes}},
		{key: "X9-MAP-032", cells: []string{"post/sender"}, name: "Solana, the holder: sent, clean, window stopped", row: held(holderA), sent: &postA,
			run: notify(postA),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusPaymentInitiated),
				events: []wantEvent{{typ: EventSent, from: fromSent, receipt: rcpt(StatusPaymentInitiated)}}, holder: &holderA, until: &noLapse, hash: &yes}},
		{key: "X9-MAP-032", cells: []string{"post/sender"}, name: "FedNow, the holder: no event, window stopped", row: held(holderA), sent: new(withTx(bank(holderA, "FedNow"), "E2E-1")),
			run:  notify(withTx(bank(holderA, "FedNow"), "E2E-1")),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusPaymentInitiated), holder: &holderA, until: &noLapse, hash: &yes}},
		{key: "X9-MAP-033", cells: []string{"post/else"}, name: "another payer: the holder keeps it, sent flagged", row: held(holderA), sent: &postOther,
			run: notify(postOther),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusPaymentInitiated, riws),
				events: []wantEvent{{typ: EventSent, from: fromSent, receipt: rcpt(StatusPaymentInitiated, riws)}}, holder: &holderA, until: &noLapse, hash: &yes}},
		{key: "X9-MAP-033", cells: []string{"post/else"}, name: "a sender without payer.info is never the holder", row: held(noPayerInfoA), sent: &postNoInfo,
			run: notify(postNoInfo),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusPaymentInitiated, riws),
				events: []wantEvent{{typ: EventSent, from: fromSent, receipt: rcpt(StatusPaymentInitiated, riws)}}, holder: &noPayerInfoA, hash: &yes}},
		{key: "X9-MAP-033", cells: []string{"post/else"}, name: "unattributed: stays unattributed, sent flagged", row: unattributed(), sent: &postA,
			run: notify(postA),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusPaymentInitiated, riws),
				events: []wantEvent{{typ: EventSent, from: fromSent, receipt: rcpt(StatusPaymentInitiated, riws)}}, holder: &Holder{}, until: &noLapse, hash: &yes}},
		{key: "X9-MAP-033", name: "FedNow, another payer: no event, window stopped", row: held(holderA), sent: new(withTx(bank(otherInfo, "FedNow"), "E2E-2")),
			run:  notify(withTx(bank(otherInfo, "FedNow"), "E2E-2")),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusPaymentInitiated, riws), holder: &holderA, until: &noLapse, hash: &yes}},
		{key: "X9-MAP-034", cells: []string{"post/closed"}, name: "Solana on PAID: never reopens, sent flagged", row: closed(StatusPaid), sent: &postA,
			run: notify(postA),
			want: want{status: StatusPaid, stored: StatusPaid, write: true, record: rcpt(StatusPaid, riws),
				events: []wantEvent{{typ: EventSent, from: fromSent, receipt: rcpt(StatusPaid, riws)}}, hash: &no, until: &noLapse}},
		{key: "X9-MAP-034", cells: []string{"post/closed"}, name: "Solana on CANCELLED", row: closed(StatusCancelled), sent: &postA,
			run: notify(postA),
			want: want{status: StatusCancelled, stored: StatusCancelled, write: true, record: rcpt(StatusCancelled, riws),
				events: []wantEvent{{typ: EventSent, from: fromSent, receipt: rcpt(StatusCancelled, riws)}}, hash: &no}},
		{key: "X9-MAP-034", name: "FedNow on PAID: recorded, no event", row: closed(StatusPaid), sent: new(withTx(bank(holderA, "FedNow"), "E2E-1")),
			run:  notify(withTx(bank(holderA, "FedNow"), "E2E-1")),
			want: want{status: StatusPaid, stored: StatusPaid, write: true, record: rcpt(StatusPaid, riws), hash: &no}},

		// ============================================================ what blockchain.action says
		{key: "X9-MAP-040", cells: []string{"pi+hash/ACTIVE"}, name: "on ACTIVE: as a post-commit, plus ACTION_CONTRADICTS_HASH", row: active(), sent: &piHashA,
			run: notify(piHashA),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusActive, riws, ach),
				events: []wantEvent{
					{typ: EventInitiated, from: fromSent, receipt: rcpt(StatusActive, riws, ach)},
					{typ: EventSent, from: fromSent, receipt: rcpt(StatusActive, riws, ach)},
				}, holder: &holderA, hash: &yes}},
		{key: "X9-MAP-040", cells: []string{"pi+hash/sender"}, name: "held by the sender", row: held(holderA), sent: &piHashA,
			run: notify(piHashA),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusPaymentInitiated, ach),
				events: []wantEvent{{typ: EventSent, from: fromSent, receipt: rcpt(StatusPaymentInitiated, ach)}}, hash: &yes}},
		{key: "X9-MAP-040", cells: []string{"pi+hash/else"}, name: "held by someone else", row: held(holderA), sent: &piHashOther,
			run: notify(piHashOther),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusPaymentInitiated, riws, ach),
				events: []wantEvent{{typ: EventSent, from: fromSent, receipt: rcpt(StatusPaymentInitiated, riws, ach)}}, holder: &holderA, hash: &yes}},
		{key: "X9-MAP-040", cells: []string{"pi+hash/closed"}, name: "on PAID", row: closed(StatusPaid), sent: &piHashA,
			run: notify(piHashA),
			want: want{status: StatusPaid, stored: StatusPaid, write: true, record: rcpt(StatusPaid, riws, ach),
				events: []wantEvent{{typ: EventSent, from: fromSent, receipt: rcpt(StatusPaid, riws, ach)}}}},
		{key: "X9-MAP-041", cells: []string{"sent-no-hash/ACTIVE"}, name: "on ACTIVE: 400", row: active(),
			run:  notify(sentNoHashA),
			want: want{status: StatusActive, stored: StatusActive, refusal: KindBusinessRule, field: "paymentNotification.data.payment.transactionId", text: "Transaction ID is required when blockchain notification action is 'sent'."}},
		{key: "X9-MAP-041", cells: []string{"sent-no-hash/sender"}, name: "held by the sender: 400", row: held(holderA),
			run:  notify(sentNoHashA),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, refusal: KindBusinessRule, field: "paymentNotification.data.payment.transactionId"}},
		{key: "X9-MAP-041", cells: []string{"sent-no-hash/else"}, name: "held by someone else: 400, not 409", row: held(holderA),
			run:  notify(sentNoHashOther),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, refusal: KindBusinessRule, field: "paymentNotification.data.payment.transactionId"}},
		{key: "X9-MAP-041", cells: []string{"sent-no-hash/closed"}, name: "on PAID: 400", row: closed(StatusPaid),
			run:  notify(sentNoHashA),
			want: want{status: StatusPaid, stored: StatusPaid, refusal: KindBusinessRule, field: "paymentNotification.data.payment.transactionId"}},
		{key: "X9-MAP-041", cells: []string{"sent-no-hash/closed"}, name: "on CANCELLED: 400", row: closed(StatusCancelled),
			run:  notify(sentNoHashA),
			want: want{status: StatusCancelled, stored: StatusCancelled, refusal: KindBusinessRule, field: "paymentNotification.data.payment.transactionId"}},

		// ============================================================ NOT_SENT
		{key: "X9-MAP-042", cells: []string{"not-sent/sender"}, name: "the holder gives it back: ACTIVE, failed", row: held(holderA), sent: &notSentA,
			run: notify(notSentA),
			want: want{status: StatusActive, stored: StatusActive, write: true, record: rcpt(StatusPaymentInitiated),
				events: []wantEvent{{typ: EventFailed, from: fromSent, receipt: rcpt(StatusPaymentInitiated), reason: "payer reported the payment did not proceed"}},
				holder: &Holder{}, until: &noLapse, hash: &no}},
		{key: "X9-MAP-042", cells: []string{"not-sent/ACTIVE lapsed holder"}, name: "the holder of a lapsed reservation: accepted, failed", row: lapsed(holderA), sent: &notSentA,
			run: notify(notSentA),
			want: want{status: StatusActive, stored: StatusActive, write: true, record: rcpt(StatusActive),
				events: []wantEvent{{typ: EventFailed, from: fromSent, receipt: rcpt(StatusActive), reason: "did not proceed"}}, holder: &Holder{}}},
		{key: "X9-MAP-042", name: "ttl-seconds 0: NOT_SENT still releases", row: func() Row { r := held(holderA); r.ReservedUntil = NoLapse; return r }(), sent: &notSentA,
			run: notify(notSentA),
			want: want{status: StatusActive, stored: StatusActive, write: true, record: rcpt(StatusPaymentInitiated),
				events: []wantEvent{{typ: EventFailed, from: fromSent, receipt: rcpt(StatusPaymentInitiated), reason: "did not proceed"}}}},
		{key: "X9-MAP-043", cells: []string{"not-sent/sender § hash"}, name: "once a hash is registered: accepted, nothing changes, no event", row: withHash(holderA), sent: &notSentA,
			run:  notify(notSentA),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusPaymentInitiated, ach), holder: &holderA, until: &noLapse, hash: &yes}},
		{key: "X9-MAP-044", cells: []string{"not-sent+tx/ACTIVE"}, name: "on ACTIVE: as a post-commit, plus ACTION_CONTRADICTS_HASH", row: active(), sent: &notSentTxA,
			run: notify(notSentTxA),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusActive, riws, ach),
				events: []wantEvent{
					{typ: EventInitiated, from: fromSent, receipt: rcpt(StatusActive, riws, ach)},
					{typ: EventSent, from: fromSent, receipt: rcpt(StatusActive, riws, ach)},
				}, holder: &holderA, hash: &yes}},
		{key: "X9-MAP-044", cells: []string{"not-sent+tx/sender"}, name: "held by the sender", row: held(holderA), sent: &notSentTxA,
			run: notify(notSentTxA),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusPaymentInitiated, ach),
				events: []wantEvent{{typ: EventSent, from: fromSent, receipt: rcpt(StatusPaymentInitiated, ach)}}, hash: &yes}},
		{key: "X9-MAP-044", cells: []string{"not-sent+tx/else"}, name: "held by someone else", row: held(holderA), sent: &notSentTxOther,
			run: notify(notSentTxOther),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, write: true, record: rcpt(StatusPaymentInitiated, riws, ach),
				events: []wantEvent{{typ: EventSent, from: fromSent, receipt: rcpt(StatusPaymentInitiated, riws, ach)}}, holder: &holderA, hash: &yes}},
		{key: "X9-MAP-044", cells: []string{"not-sent+tx/closed"}, name: "on CANCELLED", row: closed(StatusCancelled), sent: &notSentTxA,
			run: notify(notSentTxA),
			want: want{status: StatusCancelled, stored: StatusCancelled, write: true, record: rcpt(StatusCancelled, riws, ach),
				events: []wantEvent{{typ: EventSent, from: fromSent, receipt: rcpt(StatusCancelled, riws, ach)}}}},
		{key: "X9-MAP-045", cells: []string{"not-sent/else"}, name: "held by another payer: 409", row: held(holderA),
			run:  notify(notSentOther),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, refusal: KindConflict, text: "not yours to release"}},
		{key: "X9-MAP-045", cells: []string{"not-sent/else"}, name: "a holder without payer.info cannot release: 409", row: held(noPayerInfoA),
			run:  notify(notSentNoInfo),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, refusal: KindConflict, text: "not yours to release"}},
		{key: "X9-MAP-045", cells: []string{"not-sent/else"}, name: "unattributed: 409", row: unattributed(),
			run:  notify(notSentA),
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, refusal: KindConflict}},
		{key: "X9-MAP-045", cells: []string{"not-sent/ACTIVE"}, name: "on ACTIVE: 400", row: active(),
			run:  notify(notSentA),
			want: want{status: StatusActive, stored: StatusActive, refusal: KindBusinessRule, field: "paymentNotification.data.blockchain.action", text: "this QR Code is ACTIVE with no reservation of yours"}},
		{key: "X9-MAP-045", cells: []string{"not-sent/ACTIVE"}, name: "somebody else's lapsed reservation: 400", row: lapsed(otherInfo),
			run:  notify(notSentA),
			want: want{status: StatusActive, stored: StatusPaymentInitiated, refusal: KindBusinessRule, field: "paymentNotification.data.blockchain.action"}},
		{key: "X9-MAP-045", cells: []string{"not-sent/closed"}, name: "on PAID: 400", row: closed(StatusPaid),
			run:  notify(notSentA),
			want: want{status: StatusPaid, stored: StatusPaid, refusal: KindBusinessRule, field: "paymentNotification.data.blockchain.action", text: "PAID"}},
		{key: "X9-MAP-045", cells: []string{"not-sent/closed"}, name: "on CANCELLED: 400", row: closed(StatusCancelled),
			run:  notify(notSentA),
			want: want{status: StatusCancelled, stored: StatusCancelled, refusal: KindBusinessRule, field: "paymentNotification.data.blockchain.action"}},

		// ============================================================ location and time
		{key: "X9-MAP-050", name: "a CANCELLED holder gives its location up", row: closed(StatusCancelled),
			run:  func(r Row) (Decision, error) { return ReleaseLocation(r, freshLoc, t0), nil },
			want: want{status: StatusCancelled, stored: StatusCancelled, write: true}},
		{key: "X9-MAP-050", name: "a PAID holder gives its location up", row: closed(StatusPaid),
			run:  func(r Row) (Decision, error) { return ReleaseLocation(r, freshLoc, t0), nil },
			want: want{status: StatusPaid, stored: StatusPaid, write: true}},
		{key: "X9-MAP-050", name: "an expired ACTIVE holder gives it up (ADR-0024, not Java's BUG-004)", row: func() Row { r := active(); r.ValidUntil = t0.Add(-time.Millisecond); return r }(),
			run:  func(r Row) (Decision, error) { return ReleaseLocation(r, freshLoc, t0), nil },
			want: want{status: StatusActive, stored: StatusActive, write: true}},
		{key: "X9-MAP-050", name: "a live ACTIVE holder keeps it", row: active(),
			run:  func(r Row) (Decision, error) { return ReleaseLocation(r, freshLoc, t0), nil },
			want: want{status: StatusActive, stored: StatusActive, refusal: KindBusinessRule, text: "The QRCode needs to be inactive to reuse location. Current status: ACTIVE"}},
		{key: "X9-MAP-050", name: "a holder at exactly validUntil is still live", row: func() Row { r := active(); r.ValidUntil = t0; return r }(),
			run:  func(r Row) (Decision, error) { return ReleaseLocation(r, freshLoc, t0), nil },
			want: want{status: StatusActive, stored: StatusActive, refusal: KindBusinessRule}},
		{key: "X9-MAP-050", name: "a live PAYMENT_INITIATED holder keeps it", row: held(holderA),
			run:  func(r Row) (Decision, error) { return ReleaseLocation(r, freshLoc, t0), nil },
			want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated, refusal: KindBusinessRule, text: "PAYMENT_INITIATED"}},
		{key: "X9-MAP-051", cells: []string{"window/held"}, name: "a lapsed reservation reads ACTIVE, nothing written", row: lapsed(holderA),
			run: read, want: want{status: StatusActive, stored: StatusPaymentInitiated}},
		{key: "X9-MAP-051", cells: []string{"window/else"}, name: "an unattributed reservation lapses too", row: func() Row { r := unattributed(); r.ReservedUntil = t0.Add(-time.Second); return r }(),
			run: read, want: want{status: StatusActive, stored: StatusPaymentInitiated}},
		{key: "X9-MAP-051", name: "at exactly its stamp it still holds", row: func() Row { r := held(holderA); r.ReservedUntil = t0; return r }(),
			run: read, want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated}},
		{key: "X9-MAP-051", cells: []string{"window/hash"}, name: "never once a hash is registered", row: func() Row { r := withHash(holderA); r.ReservedUntil = t0.Add(-time.Hour); return r }(),
			run: read, want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated}},
		{key: "X9-MAP-051", cells: []string{"window/ttl0"}, name: "never with ttl-seconds 0 (no lapse instant)", row: func() Row { r := held(holderA); r.ReservedUntil = NoLapse; return r }(),
			run: read, want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated}},
		{key: "X9-MAP-051", name: "a reservation with no stamp holds", row: func() Row { r := held(holderA); r.ReservedUntil = time.Time{}; return r }(),
			run: read, want: want{status: StatusPaymentInitiated, stored: StatusPaymentInitiated}},
	}
	return cases
}

func TestTransitionMap(t *testing.T) {
	for _, c := range mapCases() {
		t.Run(c.key+"/"+c.name, func(t *testing.T) {
			in := c.row
			d, err := c.run(in)
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			checkOutcome(t, c, in, d)
		})
	}
}

func checkOutcome(t *testing.T, c mapCase, in Row, d Decision) {
	t.Helper()
	w := c.want
	if d.Status != w.status {
		t.Errorf("answer status %s, want %s", d.Status, w.status)
	}
	if d.Row.Status != w.stored {
		t.Errorf("stored status %s, want %s", d.Row.Status, w.stored)
	}
	if d.Status != EffectiveStatus(d.Row, t0) {
		t.Errorf("answer status %s is not the row's effective status %s", d.Status, EffectiveStatus(d.Row, t0))
	}

	// S9.3-AC7: an accepted transition sets revised_at, and only PATCH moves the revision.
	if d.Write != w.write {
		t.Errorf("write %v, want %v", d.Write, w.write)
	}
	if d.Write && !d.Row.RevisedAt.Equal(t0) {
		t.Errorf("revised_at %v, want now %v", d.Row.RevisedAt, t0)
	}
	if !d.Write {
		if diff := cmp.Diff(in, d.Row); diff != "" {
			t.Errorf("nothing written, yet the row changed (-in +out):\n%s", diff)
		}
		if d.Notification != nil || len(d.Events) > 0 {
			t.Errorf("nothing written, yet a notification or events: %+v %+v", d.Notification, d.Events)
		}
	}
	if got := d.Row.Revision - in.Revision; got != w.revision {
		t.Errorf("revision moved by %d, want %d", got, w.revision)
	}

	checkRefusal(t, w, d.Refusal)
	checkReservation(t, w, d.Row)

	// Stored notification and its receipt.
	switch {
	case w.record == nil && d.Notification != nil:
		t.Errorf("a notification is stored, want none: %+v", d.Notification)
	case w.record != nil && d.Notification == nil:
		t.Errorf("no notification stored, want one received in %s", w.record.ReceivedIn)
	case w.record != nil:
		if diff := cmp.Diff(*w.record, d.Notification.Receipt, cmpDeviations); diff != "" {
			t.Errorf("stored receipt (-want +got):\n%s", diff)
		}
		if c.sent != nil && !cmp.Equal(*c.sent, d.Notification.Notification) {
			t.Errorf("stored notification is not the one sent")
		}
		if d.Notification.Phase != d.Notification.Notification.Phase() || !d.Notification.ReceivedAt.Equal(t0) {
			t.Errorf("stored phase %s at %v", d.Notification.Phase, d.Notification.ReceivedAt)
		}
	}

	checkEvents(t, c, d)
}

// cmpDeviations treats nil and empty deviations alike in receipts compared here; events are held
// to "absent, never empty" separately.
var cmpDeviations = cmp.Comparer(func(a, b []Deviation) bool { return slices.Equal(a, b) })

func checkRefusal(t *testing.T, w want, r *Refusal) {
	t.Helper()
	if w.refusal == 0 {
		if r != nil {
			t.Errorf("refused, want accepted: %v", r)
		}
		return
	}
	if r == nil {
		t.Errorf("accepted, want a refusal of kind %d", w.refusal)
		return
	}
	if r.Kind != w.refusal {
		t.Errorf("refusal kind %d, want %d (%v)", r.Kind, w.refusal, r)
	}
	if w.field != "" && r.Field != w.field {
		t.Errorf("refusal field %q, want %q", r.Field, w.field)
	}
	if !strings.Contains(r.Message, w.text) {
		t.Errorf("refusal %q does not say %q", r.Message, w.text)
	}
	if (r.Kind == KindConflict || r.Kind == KindPreconditionFailed) && r.CurrentStatus == "" {
		t.Errorf("a %d refusal carries no current status", r.Kind)
	}
}

func checkReservation(t *testing.T, w want, row Row) {
	t.Helper()
	if w.holder != nil && row.ReservedBy != *w.holder {
		t.Errorf("holder %+v, want %+v", row.ReservedBy, *w.holder)
	}
	if w.until != nil && !row.ReservedUntil.Equal(*w.until) {
		t.Errorf("reserved_until %v, want %v", row.ReservedUntil, *w.until)
	}
	if w.hash != nil && row.HashRegistered != *w.hash {
		t.Errorf("hash registered %v, want %v", row.HashRegistered, *w.hash)
	}
	// Leaving PAYMENT_INITIATED clears the stamp and the holder (S9.3-AC2).
	if row.Status != StatusPaymentInitiated && (row.ReservedBy != Holder{} || !row.ReservedUntil.Equal(NoLapse) || row.HashRegistered) {
		t.Errorf("a %s row still carries a reservation: %+v until %v hash %v", row.Status, row.ReservedBy, row.ReservedUntil, row.HashRegistered)
	}
}

func checkEvents(t *testing.T, c mapCase, d Decision) {
	t.Helper()
	w := c.want
	got := make([]EventType, len(d.Events))
	for i, e := range d.Events {
		got[i] = e.Type
	}
	wantTypes := make([]EventType, len(w.events))
	for i, e := range w.events {
		wantTypes[i] = e.typ
	}
	if !slices.Equal(got, wantTypes) {
		t.Fatalf("events %v, want %v", got, wantTypes)
	}
	for i, we := range w.events {
		e := d.Events[i]
		if !e.OccurredAt.Equal(t0) || e.QRCodeID != d.Row.ID || e.LocationID != d.Row.LocationID ||
			e.QRCodeRevision != d.Row.Revision || e.SchemaVersion != "1.1" {
			t.Errorf("%s header: %+v", e.Type, e)
		}
		if e.InvoiceNumber == nil || *e.InvoiceNumber != "INV-1" || e.OrderNumber == nil || *e.OrderNumber != "ORD-1" {
			t.Errorf("%s: invoice and order numbers not from the bill", e.Type)
		}
		checkBody(t, c, d, e, we.from)

		var wantReceived *Status
		var wantDevs []Deviation
		if we.receipt != nil {
			wantReceived = &we.receipt.ReceivedIn
			wantDevs = we.receipt.Deviations
		}
		if !cmp.Equal(wantReceived, e.ReceivedInStatus) {
			t.Errorf("%s receivedInStatus %v, want %v", e.Type, deref(e.ReceivedInStatus), deref(wantReceived))
		}
		if len(wantDevs) == 0 && e.Deviations != nil {
			t.Errorf("%s deviations must be absent, not %#v", e.Type, e.Deviations)
		}
		if !slices.Equal(wantDevs, e.Deviations) {
			t.Errorf("%s deviations %v, want %v", e.Type, e.Deviations, wantDevs)
		}
		switch {
		case we.reason == "" && e.Reason != nil:
			t.Errorf("%s reason %q, want none", e.Type, *e.Reason)
		case we.reason != "" && (e.Reason == nil || !strings.Contains(*e.Reason, we.reason)):
			t.Errorf("%s reason %v, want one saying %q", e.Type, deref(e.Reason), we.reason)
		}
	}
}

func deref[T any](p *T) any {
	if p == nil {
		return nil
	}
	return *p
}

// checkBody: the body comes from the last stored notification, else from the first payment
// method and the payment details (ADR-0035, S4.1-AC3).
func checkBody(t *testing.T, c mapCase, d Decision, e Event, from source) {
	t.Helper()
	type body struct {
		Amount, Tip                    any
		Payer, Currency, Network, TxID any
	}
	got := body{deref(e.Amount), deref(e.TipAmount), deref(e.PayerInfo), deref(e.Currency), deref(e.Network), deref(e.TransactionID)}
	var want body
	switch from {
	case fromSent, fromLast:
		var n Notification
		if from == fromSent {
			n = *c.sent
		} else {
			n = c.last.Notification
		}
		want = body{n.Amount, deref(n.TipAmount), deref(n.PayerInfo), n.Currency, n.Network, deref(present(n.TransactionID))}
	case fromMethod:
		want = body{int64(1000), nil, nil, "USDC", deref(present(d.Row.PaymentNetwork)), deref(present(d.Row.EndToEndID))}
	}
	if diff := cmp.Diff(want, got); diff != "" {
		t.Errorf("%s body (-want +got):\n%s", e.Type, diff)
	}
}

// matrixCells are the cells of ADR-0036's matrix ("Every case, by message and status"), with the
// § and ¶ amendments, as the cases above name them.
var matrixCells = []string{
	"pre/ACTIVE yes", "pre/ACTIVE no", "pre/sender retry yes", "pre/sender retry no (chain)",
	"pre/sender retry no (bank)", "pre/sender § hash", "pre/else", "pre/closed",
	"post/ACTIVE", "post/sender", "post/else", "post/closed",
	"pi+hash/ACTIVE", "pi+hash/sender", "pi+hash/else", "pi+hash/closed",
	"sent-no-hash/ACTIVE", "sent-no-hash/sender", "sent-no-hash/else", "sent-no-hash/closed",
	"not-sent/ACTIVE", "not-sent/ACTIVE lapsed holder", "not-sent/sender", "not-sent/sender § hash",
	"not-sent/else", "not-sent/closed",
	"not-sent+tx/ACTIVE", "not-sent+tx/sender", "not-sent+tx/else", "not-sent+tx/closed",
	"su-init/ACTIVE", "su-init/ACTIVE lapsed", "su-init/held ¶", "su-init/unattributed ¶", "su-init/closed",
	"su-paid/ACTIVE", "su-paid/held", "su-paid/unattributed", "su-paid/PAID ¶", "su-paid/CANCELLED",
	"su-cancel/ACTIVE", "su-cancel/held", "su-cancel/unattributed", "su-cancel/CANCELLED ¶", "su-cancel/PAID",
	"su-active/ACTIVE ¶", "su-active/ACTIVE lapsed", "su-active/held", "su-active/unattributed", "su-active/closed",
	"window/held", "window/else", "window/hash", "window/ttl0",
}

// TestEveryMapKeyAndMatrixCellHasACase is S9.3-AC4's coverage proof: the keys are read from
// ADR-0035 itself, so a row added there without a case here fails, as does a case for no row.
func TestEveryMapKeyAndMatrixCellHasACase(t *testing.T) {
	adrs, err := filepath.Glob("../../docs/adr/0035-*.md")
	if err != nil || len(adrs) != 1 {
		t.Fatalf("ADR-0035 not found: %v %v", adrs, err)
	}
	text, err := os.ReadFile(adrs[0])
	if err != nil {
		t.Fatal(err)
	}
	inADR := map[string]bool{}
	for _, k := range regexp.MustCompile(`X9-MAP-\d{3}`).FindAllString(string(text), -1) {
		inADR[k] = true
	}
	if len(inADR) == 0 {
		t.Fatal("ADR-0035 lists no X9-MAP keys")
	}

	keys, cells := map[string]bool{}, map[string]bool{}
	for _, c := range mapCases() {
		keys[c.key] = true
		for _, cell := range c.cells {
			if !slices.Contains(matrixCells, cell) {
				t.Errorf("case %q names an unknown cell %q", c.name, cell)
			}
			cells[cell] = true
		}
	}
	for k := range inADR {
		if !keys[k] {
			t.Errorf("ADR-0035 row %s has no case", k)
		}
	}
	for k := range keys {
		if !inADR[k] {
			t.Errorf("case key %s is not a row of ADR-0035", k)
		}
	}
	for _, cell := range matrixCells {
		if !cells[cell] {
			t.Errorf("ADR-0036 matrix cell %q has no case", cell)
		}
	}
}
