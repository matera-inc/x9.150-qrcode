// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package domain

import (
	"errors"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
)

// S9.3-AC1: status is a typed string, parsed at the boundary from the wire and from the database.
func TestStatusIsParsedAtTheBoundary(t *testing.T) {
	for _, s := range []Status{StatusActive, StatusPaymentInitiated, StatusPaid, StatusCancelled} {
		got, err := ParseStatus(string(s))
		if err != nil || got != s {
			t.Errorf("ParseStatus(%q) = %q, %v", s, got, err)
		}
		back, err := StatusFromCode(s.Code())
		if err != nil || back != s {
			t.Errorf("StatusFromCode(%q) = %q, %v", s.Code(), back, err)
		}
	}
	for _, bad := range []string{"", "active", "Active", "EXPIRED"} {
		if _, err := ParseStatus(bad); !errors.Is(err, ErrUnknownStatus) {
			t.Errorf("ParseStatus(%q) accepted", bad)
		}
	}
	if _, err := StatusFromCode("XXXX"); !errors.Is(err, ErrUnknownStatus) {
		t.Error("StatusFromCode accepted an unknown code")
	}
	if Status("NOPE").Code() != "" || Status("NOPE").Closed() {
		t.Error("an unknown status has a code or is closed")
	}
}

// S9.3-AC5: the same payer only when both carry payer.info, the values are equal and the
// certificate subjects are equal (ADR-0036).
func TestSamePayer(t *testing.T) {
	tests := []struct {
		name string
		a, b Holder
		want bool
	}{
		{"same payer.info, same certificate", holderA, holderA, true},
		{"another payer.info", holderA, otherInfo, false},
		{"another certificate", holderA, otherCert, false},
		{"the holder sent no payer.info", noPayerInfoA, holderA, false},
		{"the sender sent no payer.info", holderA, noPayerInfoA, false},
		{"neither sent payer.info, same certificate", noPayerInfoA, noPayerInfoA, false},
		{"blank payer.info is absent", Holder{PayerInfo: "  ", SignerSubject: "CN=a"}, Holder{PayerInfo: "  ", SignerSubject: "CN=a"}, false},
		{"unattributed matches nobody", Holder{}, holderA, false},
		{"unattributed matches not even itself", Holder{}, Holder{}, false},
		{"payer.info is compared exactly", holderA, Holder{PayerInfo: "HOLDER@example.com", SignerSubject: holderA.SignerSubject}, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := tt.a.SamePayer(tt.b); got != tt.want {
				t.Errorf("SamePayer = %v, want %v", got, tt.want)
			}
		})
	}
}

// S9.3-AC2: the effective status, and where the window comes from.
func TestEffectiveStatus(t *testing.T) {
	tests := []struct {
		name string
		row  Row
		at   time.Time
		want Status
	}{
		{"ACTIVE", active(), t0, StatusActive},
		{"inside the window", held(holderA), t0, StatusPaymentInitiated},
		{"at the stamp", held(holderA), t0.Add(30 * time.Second), StatusPaymentInitiated},
		{"a nanosecond past the stamp", held(holderA), t0.Add(30*time.Second + time.Nanosecond), StatusActive},
		{"a hash stops the window", withHash(holderA), t0.Add(1000 * time.Hour), StatusPaymentInitiated},
		{"no lapse instant", func() Row { r := held(holderA); r.ReservedUntil = NoLapse; return r }(), t0.Add(1000 * time.Hour), StatusPaymentInitiated},
		{"no stamp at all holds", func() Row { r := held(holderA); r.ReservedUntil = time.Time{}; return r }(), t0, StatusPaymentInitiated},
		{"PAID stays PAID past any stamp", func() Row { r := closed(StatusPaid); r.ReservedUntil = t0.Add(-time.Hour); return r }(), t0, StatusPaid},
		{"expiry is not a status", func() Row { r := active(); r.ValidUntil = t0.Add(-time.Hour); return r }(), t0, StatusActive},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := EffectiveStatus(tt.row, tt.at); got != tt.want {
				t.Errorf("EffectiveStatus = %s, want %s", got, tt.want)
			}
		})
	}
}

func TestReservationEnd(t *testing.T) {
	validUntil := t0.Add(time.Hour)
	tests := []struct {
		name       string
		ttl        time.Duration
		validUntil time.Time
		want       time.Time
	}{
		{"now plus the window", ttl, validUntil, t0.Add(ttl)},
		{"capped by validUntil", ttl, t0.Add(time.Second), t0.Add(time.Second)},
		{"ttl-seconds 0 is no window", 0, validUntil, NoLapse},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := reservationEnd(t0, tt.validUntil, tt.ttl); !got.Equal(tt.want) {
				t.Errorf("reservationEnd = %v, want %v", got, tt.want)
			}
		})
	}
}

// S9.3-AC3: "<revision>-<effective status>", and If-Match compared against the same function.
func TestETag(t *testing.T) {
	if got := ETag(active(), t0); got != "3-ACTIVE" {
		t.Errorf("ETag = %q", got)
	}
	if got := ETag(held(holderA), t0); got != "3-PAYMENT_INITIATED" {
		t.Errorf("ETag = %q", got)
	}
	lapsedRow := lapsed(holderA)
	if got := ETag(lapsedRow, t0); got != "3-ACTIVE" {
		t.Errorf("a lapsed reservation's ETag is %q, want the effective 3-ACTIVE", got)
	}
	if r := requireETag(lapsedRow, t0, new(ETag(lapsedRow, t0))); r != nil {
		t.Errorf("the tag GET hands out is refused: %v", r)
	}
	if r := requireETag(lapsedRow, t0, nil); r != nil {
		t.Errorf("an unconditional request is refused: %v", r)
	}
	r := requireETag(lapsedRow, t0, new("3-PAYMENT_INITIATED"))
	want := &Refusal{
		Kind:            KindPreconditionFailed,
		Message:         "The QR Code has changed since it was read: expected 3-PAYMENT_INITIATED, found 3-ACTIVE.",
		CurrentStatus:   StatusActive,
		CurrentRevision: 3,
	}
	if diff := cmp.Diff(want, r); diff != "" {
		t.Errorf("stale tag (-want +got):\n%s", diff)
	}
	if !errors.Is(r, ErrPreconditionFailed) || errors.Is(r, ErrConflict) {
		t.Error("a failed precondition does not match its sentinel only")
	}
}

// S9.3-AC8: not live when the stored status is PAID or CANCELLED, or valid_until has passed.
func TestHolderIsLive(t *testing.T) {
	expired := func(r Row) Row { r.ValidUntil = t0.Add(-time.Millisecond); return r }
	tests := []struct {
		name string
		row  Row
		want bool
	}{
		{"ACTIVE before validUntil", active(), true},
		{"PAYMENT_INITIATED before validUntil", held(holderA), true},
		{"a lapsed reservation is still a live QR Code", lapsed(holderA), true},
		{"ACTIVE at exactly validUntil", func() Row { r := active(); r.ValidUntil = t0; return r }(), true},
		{"ACTIVE past validUntil (Java's BUG-004 keeps it)", expired(active()), false},
		{"PAYMENT_INITIATED past validUntil", expired(held(holderA)), false},
		{"PAID", closed(StatusPaid), false},
		{"CANCELLED", closed(StatusCancelled), false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := HolderIsLive(tt.row, t0); got != tt.want {
				t.Errorf("HolderIsLive = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestPhaseComesFromTheEvidence(t *testing.T) {
	tests := []struct {
		name     string
		n        Notification
		phase    Phase
		contrads bool
	}{
		{"bank, no transactionId", bank(holderA, "FedNow"), PhasePreCommit, false},
		{"bank, transactionId", withTx(bank(holderA, "FedNow"), "E2E"), PhasePostCommit, false},
		{"blank transactionId is absent", withTx(bank(holderA, "RTP"), "  "), PhasePreCommit, false},
		{"PAYMENT_INITIATED", chain(holderA, ActionPaymentInitiated), PhasePreCommit, false},
		{"PAYMENT_INITIATED with a hash", withTx(chain(holderA, ActionPaymentInitiated), "TX"), PhasePostCommit, true},
		{"SENT with a hash", withTx(chain(holderA, ActionSent), "TX"), PhasePostCommit, false},
		{"SENT without a hash", chain(holderA, ActionSent), PhasePreCommit, false},
		{"NOT_SENT", chain(holderA, ActionNotSent), PhaseRelease, false},
		{"NOT_SENT with a hash", withTx(chain(holderA, ActionNotSent), "TX"), PhasePostCommit, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := tt.n.Phase(); got != tt.phase {
				t.Errorf("Phase = %s, want %s", got, tt.phase)
			}
			if got := tt.n.ActionContradictsHash(); got != tt.contrads {
				t.Errorf("ActionContradictsHash = %v, want %v", got, tt.contrads)
			}
		})
	}
	for network, onChain := range map[string]bool{"solana": true, "Solana": true, "FedNow": false, "RTP": false, "ach": false} {
		if got := (Notification{Network: network}).OnChain(); got != onChain {
			t.Errorf("OnChain(%s) = %v", network, got)
		}
	}
}

func TestDeviationsColumn(t *testing.T) {
	both := []Deviation{DeviationReceivedInWrongStatus, DeviationActionContradictsHash}
	if got := FormatDeviations(both); got != "RECEIVED_IN_WRONG_STATUS,ACTION_CONTRADICTS_HASH" {
		t.Errorf("FormatDeviations = %q", got)
	}
	if got := FormatDeviations(nil); got != "" {
		t.Errorf("FormatDeviations(nil) = %q", got)
	}
	got, err := ParseDeviations("RECEIVED_IN_WRONG_STATUS,ACTION_CONTRADICTS_HASH")
	if err != nil || !cmp.Equal(got, both) {
		t.Errorf("ParseDeviations = %v, %v", got, err)
	}
	if got, err := ParseDeviations(""); err != nil || got != nil {
		t.Errorf("ParseDeviations(\"\") = %#v, %v; want nil: absent, never empty", got, err)
	}
	if _, err := ParseDeviations("LATE"); !errors.Is(err, ErrUnknownDeviation) {
		t.Error("a code outside the closed list was accepted")
	}
}

func TestRefusalMatchesItsKindOnly(t *testing.T) {
	for _, tt := range []struct {
		r    *Refusal
		want error
	}{
		{businessRule("f", "m"), ErrBusinessRule},
		{conflict(StatusPaid, "m"), ErrConflict},
		{&Refusal{Kind: KindPreconditionFailed}, ErrPreconditionFailed},
	} {
		for _, sentinel := range []error{ErrBusinessRule, ErrConflict, ErrPreconditionFailed} {
			if got := errors.Is(tt.r, sentinel); got != errors.Is(sentinel, tt.want) {
				t.Errorf("errors.Is(%v, %v) = %v", tt.r, sentinel, got)
			}
		}
	}
	if got := businessRule("a.b", "must be.").Error(); got != "a.b: must be." {
		t.Errorf("Error() = %q", got)
	}
	d := Decision{Refusal: conflict(StatusPaid, "no")}
	if !errors.Is(d.Err(), ErrConflict) || (Decision{}).Err() != nil {
		t.Error("Decision.Err does not return the refusal")
	}
}

// S9.5: the store hands over every stored notification in arrival order; which one an event body
// is built from is decided here (ADR-0035), not in SQL. Owner, 2026-11-15: the latest stored
// notification that carries a transactionId, else the last stored one, else none (the builder then
// uses the first payment method).
func TestEventBodySource(t *testing.T) {
	pre := NotificationRecord{Phase: PhasePreCommit}
	post := func(tx string) NotificationRecord {
		return NotificationRecord{Phase: PhasePostCommit, Notification: Notification{TransactionID: tx}}
	}
	// A NOT_SENT once a hash is registered: stored, flagged, and carrying no transactionId.
	contradicting := NotificationRecord{Phase: PhaseRelease, Notification: Notification{Action: ActionNotSent},
		Receipt: Receipt{ReceivedIn: StatusPaymentInitiated, Deviations: []Deviation{DeviationActionContradictsHash}}}
	blank := NotificationRecord{Phase: PhasePreCommit, Notification: Notification{TransactionID: "  "}}

	for _, tt := range []struct {
		name   string
		stored []NotificationRecord
		want   *NotificationRecord
	}{
		{"none stored: none, so the first payment method", nil, nil},
		{"none stored, empty slice", []NotificationRecord{}, nil},
		{"no transactionId anywhere: the last stored", []NotificationRecord{pre, contradicting}, &contradicting},
		{"one post-commit, last: it", []NotificationRecord{pre, post("tx-2")}, new(post("tx-2"))},
		{"a contradictory NOT_SENT after the hash: the post-commit, not the NOT_SENT",
			[]NotificationRecord{pre, post("tx-2"), contradicting}, new(post("tx-2"))},
		{"two hashes: the latest", []NotificationRecord{post("tx-1"), pre, post("tx-2"), contradicting}, new(post("tx-2"))},
		{"a blank transactionId is none", []NotificationRecord{post("tx-1"), blank}, new(post("tx-1"))},
	} {
		t.Run(tt.name, func(t *testing.T) {
			got := EventBodySource(tt.stored)
			if diff := cmp.Diff(tt.want, got); diff != "" {
				t.Fatalf("EventBodySource (-want +got):\n%s", diff)
			}
		})
	}

	stored := []NotificationRecord{post("tx-2"), contradicting}
	got := EventBodySource(stored)
	if got == nil {
		t.Fatal("EventBodySource: nil with notifications stored")
	}
	got.Notification.TransactionID = "changed"
	if stored[0].Notification.TransactionID != "tx-2" {
		t.Fatal("EventBodySource aliases the stored slice")
	}
}
