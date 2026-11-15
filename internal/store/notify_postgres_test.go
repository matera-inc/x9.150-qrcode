// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package store

import (
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/domain"
)

// Story 10.5's transactions against a real PostgreSQL, run by TestPostgres: the concurrency of
// S10.5-AC6, the "no window" setting of S10.5-AC7 on the test clock, and a post-commit naming what
// the QR Code does not offer (ADR-0036, amended 2026-11-15).

// S10.5-AC6: 32 pre-commits from different payers on one QR Code end with exactly one yes and every
// other a 409 (the domain's "another payer holds it", or "busy" once the re-runs are spent, never a
// failure); one reservation, one payment.initiated, one notification stored.
func testThirtyTwoPayersOneReservation(t *testing.T, e *env) {
	ctx := t.Context()
	q := e.create(t)
	const n = 32
	var (
		mu  sync.Mutex
		out outcome
	)
	race(n, func(i int) {
		res, err := e.notify(ctx, q.ID, payer("payer-"+strconv.Itoa(i), ""), nil, settings)
		out.add(&mu, res, err)
	})
	if len(out.other) != 0 || out.accepted != 1 || out.conflicts+out.busy != n-1 {
		t.Fatalf("outcome %+v, want one yes and %d conflicts", out, n-1)
	}
	if got := e.count(t, "SELECT COUNT(*) FROM payment_event WHERE qr_code_id = $1 AND type_code = 'INIT'", q.ID); got != 1 {
		t.Fatalf("%d payment.initiated, want 1", got)
	}
	if got := e.count(t, "SELECT COUNT(*) FROM payment_event WHERE qr_code_id = $1", q.ID); got != 1 {
		t.Fatalf("%d events, want 1", got)
	}
	if got := e.count(t, "SELECT COUNT(*) FROM qr_code_notification WHERE qr_code_id = $1", q.ID); got != 1 {
		t.Fatalf("%d notifications stored, want 1", got)
	}
}

// S10.5-AC7: with x9.reservation.ttl-seconds 0 a reservation never lapses, however far the clock
// moves; another payer stays refused, and it ends by the holder's NOT_SENT.
func testNoWindowNeverLapses(t *testing.T, e *env) {
	ctx := t.Context()
	noWindow := domain.Settings{ReservationTTL: 0}
	q := e.create(t)
	if r, err := e.notify(ctx, q.ID, payer("payer-a", ""), nil, noWindow); err != nil || r.Decision.Refusal != nil {
		t.Fatalf("pre-commit: %v, %v", r.Decision.Refusal, err)
	}
	e.clock.set(e.baseTime.Add(29 * 24 * time.Hour)) // a day before validUntil
	got, err := e.store.Get(ctx, q.ID)
	if err != nil {
		t.Fatal(err)
	}
	if s := domain.EffectiveStatus(got.Row, e.clock.Now()); s != domain.StatusPaymentInitiated || !got.ReservedUntil.Equal(domain.NoLapse) {
		t.Fatalf("29 days on: %s until %v, want PAYMENT_INITIATED with no lapse", s, got.ReservedUntil)
	}
	r, err := e.notify(ctx, q.ID, payer("payer-b", ""), nil, noWindow)
	if err != nil || r.Decision.Refusal == nil || r.Decision.Refusal.Kind != domain.KindConflict {
		t.Fatalf("another payer 29 days on: %v, %v; want 409", r.Decision.Refusal, err)
	}
	notSent := payer("payer-a", "")
	notSent.Action = domain.ActionNotSent
	if r, err = e.notify(ctx, q.ID, notSent, nil, noWindow); err != nil || r.Decision.Refusal != nil {
		t.Fatalf("NOT_SENT: %v, %v", r.Decision.Refusal, err)
	}
	if got, err = e.store.Get(ctx, q.ID); err != nil || got.Status != domain.StatusActive {
		t.Fatalf("after NOT_SENT: %s, %v", got.Status, err)
	}
}

// ADR-0036, amended 2026-11-15: a post-commit in a currency the QR Code does not offer is stored with
// its receipt and published as payment.sent, and the row is not written at all: no lock_version,
// no revised_at, the reservation and its window as they were. The answer is the refusal.
func testNotOfferedPostCommitIsRecorded(t *testing.T, e *env) {
	ctx := t.Context()
	q := e.create(t)
	if _, err := e.notify(ctx, q.ID, payer("payer-a", ""), nil, settings); err != nil {
		t.Fatal(err)
	}
	before, err := e.store.Get(ctx, q.ID)
	if err != nil {
		t.Fatal(err)
	}
	e.clock.set(e.baseTime.Add(time.Second))
	sent := payer("payer-a", "5igHash")
	sent.Currency = "EURC"
	res, err := e.notify(ctx, q.ID, sent, nil, settings)
	if err != nil || res.Decision.Refusal == nil || res.Decision.Refusal.Field != "paymentNotification.data.payment.currency" {
		t.Fatalf("refusal %+v, %v; want the currency named", res.Decision.Refusal, err)
	}
	after, err := e.store.Get(ctx, q.ID)
	if err != nil {
		t.Fatal(err)
	}
	if after.LockVersion != before.LockVersion || !after.RevisedAt.Equal(before.RevisedAt) ||
		!after.ReservedUntil.Equal(before.ReservedUntil) || after.HashRegistered || after.Status != domain.StatusPaymentInitiated {
		t.Fatalf("the row moved:\n before %+v\n after  %+v", before.Row, after.Row)
	}
	if len(res.Events) != 1 || res.Events[0].Type != domain.EventSent {
		t.Fatalf("events %+v, want one payment.sent", res.Events)
	}
	var deviations, received string
	if err := e.other.QueryRow(ctx, "SELECT deviations, received_in_status_code FROM qr_code_notification "+
		"WHERE qr_code_id = $1 AND transaction_id = '5igHash'", q.ID).Scan(&deviations, &received); err != nil {
		t.Fatal(err)
	}
	if deviations != "NOT_OFFERED_CURRENCY" || received != "INIT" {
		t.Fatalf("stored deviations %q in %q, want NOT_OFFERED_CURRENCY in INIT", deviations, received)
	}
	if got := e.count(t, "SELECT COUNT(*) FROM payment_event WHERE qr_code_id = $1 AND deviations = 'NOT_OFFERED_CURRENCY'", q.ID); got != 1 {
		t.Fatalf("%d flagged payment.sent, want 1", got)
	}
	// A later event's body is not built from the refused call.
	r, err := e.setStatus(ctx, q.ID, domain.StatusCancelled)
	if err != nil || len(r.Events) != 1 || r.Events[0].TransactionID != nil || *r.Events[0].Currency != "USDC" {
		t.Fatalf("payment.cancelled %+v, %v; want its body from the accepted pre-commit", r.Events, err)
	}
}
