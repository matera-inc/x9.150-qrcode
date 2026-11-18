// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package domain

import (
	"encoding/json"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
)

// NoLapse is reserved_until's sentinel, 9999-12-31 00:00:00 UTC: no instant at which the
// reservation lapses. It is the value when nobody holds the QR Code, when a hash is registered,
// when x9.reservation.ttl-seconds is 0, and for a reservation stamped before the field existed;
// in every case a PAYMENT_INITIATED carrying it holds (ADR-0019, ADR-0036).
var NoLapse = time.Date(9999, time.December, 31, 0, 0, 0, 0, time.UTC)

// Holder is who holds a reservation: the payer.info the payer sent, paired with the subject of the
// certificate that signed its notification (ADR-0021). "" means absent. A PAYMENT_INITIATED with
// both empty is an unattributed reservation, taken through the status-update API.
type Holder struct {
	PayerInfo     string
	SignerSubject string
}

// SamePayer reports whether h and other are the same payer: both carry payer.info, the values are
// equal, and the certificate subjects are equal (ADR-0036, superseding ADR-0021's fallback).
// Without payer.info there is no means to tell two payers apart, so they are assumed to be
// different; an unattributed holder therefore matches nobody.
func (h Holder) SamePayer(other Holder) bool {
	if strings.TrimSpace(h.PayerInfo) == "" || strings.TrimSpace(other.PayerInfo) == "" {
		return false
	}
	return h.PayerInfo == other.PayerInfo && h.SignerSubject == other.SignerSubject
}

// Row is one QR Code as ADR-0024 stores it in qr_code. The domain changes only its lifecycle
// columns, revised_at and, through Patch and ReleaseLocation, revision and location_id; the caller
// writes request_json, valid_until and emv_content, and lock_version.
type Row struct {
	ID         uuid.UUID
	LocationID uuid.UUID
	ValidUntil time.Time
	// Status is the stored status. What it means now is EffectiveStatus.
	Status      Status
	Revision    int
	LockVersion int

	// ReservedUntil is when the reservation lapses, or NoLapse.
	ReservedUntil time.Time
	ReservedBy    Holder
	// HashRegistered is true once a post-commit was registered for the current reservation: the
	// window has stopped, and no payer message changes the status any more (ADR-0036 §). Cleared
	// when the QR Code leaves PAYMENT_INITIATED.
	HashRegistered bool

	// EndToEndID and PaymentNetwork are the payment details given with status-update PAID; "" is
	// none.
	EndToEndID     string
	PaymentNetwork string

	// RequestJSON is the bill content of the create and PATCH bodies (ADR-0024).
	RequestJSON json.RawMessage

	CreatedAt time.Time
	RevisedAt time.Time
}

// Settings are the deployment's settings a decision needs.
type Settings struct {
	// ReservationTTL is x9.reservation.ttl-seconds: how long a pre-commit holds before it lapses.
	// 0 means no window: a reservation never lapses (ADR-0036). Negative values are refused at
	// startup, never here.
	ReservationTTL time.Duration

	// SettlementMargin is x9.notification.settlement-margin-seconds: a pre-commit is refused when
	// less than this is left before the QR Code's or the matched method's validUntil (ADR-0041).
	// 0 is off. Negative values are refused at startup, never here.
	SettlementMargin time.Duration
}

// EffectiveStatus is the status as it stands at now (ADR-0019): a PAYMENT_INITIATED whose
// reservation has lapsed reads ACTIVE, and every other row reads its stored status. A reservation
// never lapses once a hash is registered, when it was taken with no window, or when it carries no
// stamp. Time never writes a status (ADR-0029): this is applied on every read, and nothing else.
func EffectiveStatus(row Row, now time.Time) Status {
	if row.Status != StatusPaymentInitiated || row.HashRegistered || !stamped(row.ReservedUntil) {
		return row.Status
	}
	if now.After(row.ReservedUntil) {
		return StatusActive
	}
	return row.Status
}

// stamped reports whether a reservation end is a real instant rather than "no lapse". The zero
// time counts as unstamped, so a row built without the sentinel still holds.
func stamped(until time.Time) bool {
	return !until.IsZero() && until.Before(NoLapse)
}

// ETag is the entity tag of the row at now: "<revision>-<effective status>", without the quotes
// the ETag header adds (ADR-0028, Story 2.2). It is opaque to callers. If-Match is compared against
// this same function, so a lapsed reservation does not make every conditional request fail.
func ETag(row Row, now time.Time) string {
	return strconv.Itoa(row.Revision) + "-" + string(EffectiveStatus(row, now))
}

// requireETag refuses with 412 unless ifMatch is nil (an unconditional request) or equals the
// row's ETag at now.
func requireETag(row Row, now time.Time, ifMatch *string) *Refusal {
	if ifMatch == nil {
		return nil
	}
	current := ETag(row, now)
	if *ifMatch == current {
		return nil
	}
	return &Refusal{
		Kind:            KindPreconditionFailed,
		Message:         "The QR Code has changed since it was read: expected " + *ifMatch + ", found " + current + ".",
		CurrentStatus:   EffectiveStatus(row, now),
		CurrentRevision: row.Revision,
	}
}

// HolderIsLive reports whether the QR Code holding a location still keeps it from being taken: it
// is live unless its stored status is PAID or CANCELLED or its valid_until has passed (ADR-0024).
// The expiry is checked explicitly because rows are retained (ADR-0029); Java, which relied on
// MongoDB deleting expired documents, does not check it (BUG-004).
func HolderIsLive(holder Row, now time.Time) bool {
	return !holder.Status.Closed() && !holder.ValidUntil.Before(now)
}

// reservationEnd is when a reservation taken at now lapses: now plus the window, capped by the QR
// Code's own validUntil, or NoLapse when the window is 0 (ADR-0019, ADR-0036).
func reservationEnd(now, validUntil time.Time, ttl time.Duration) time.Time {
	if ttl <= 0 {
		return NoLapse
	}
	end := now.Add(ttl)
	if end.After(validUntil) {
		return validUntil
	}
	return end
}

// CapReservation is row with its reservation cut back to its validUntil when the window would end
// after it: what a PATCH that moves validUntil earlier does to a running reservation, since a
// reservation cannot outlive the thing reserved (ADR-0019, owner 2026-11-15). A later validUntil
// never lengthens a window; a reservation that never lapses (a hash registered, ttl 0, or no stamp)
// is left alone, since only the payment system ends it (ADR-0036).
func CapReservation(row Row) Row {
	if stamped(row.ReservedUntil) && row.ValidUntil.Before(row.ReservedUntil) {
		row.ReservedUntil = row.ValidUntil
	}
	return row
}

// reserve puts row in PAYMENT_INITIATED, held by holder, with a fresh window.
func reserve(row *Row, holder Holder, now time.Time, s Settings) {
	row.Status = StatusPaymentInitiated
	row.ReservedBy = holder
	row.HashRegistered = false
	row.ReservedUntil = reservationEnd(now, row.ValidUntil, s.ReservationTTL)
}

// clearReservation is what leaving PAYMENT_INITIATED does: no stamp, no holder, no hash.
func clearReservation(row *Row) {
	row.ReservedUntil = NoLapse
	row.ReservedBy = Holder{}
	row.HashRegistered = false
}

// formatID writes an id as X9.150 does: 32 upper-case hexadecimal characters (ADR-0026).
func formatID(id uuid.UUID) string {
	return strings.ToUpper(strings.ReplaceAll(id.String(), "-", ""))
}
