// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package paymentrequest

import (
	"maps"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"

	"github.com/matera-inc/x9.150-qrcode/internal/contract"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
)

// patchBill is a PATCH bill restating the sample's, with the overrides.
func patchBill(override map[string]any) map[string]any {
	b := map[string]any{"description": "blackbox", "paymentTiming": "immediate", "amountDue": map[string]any{"amount": 1000}}
	maps.Copy(b, override)
	return b
}

// refusedUnchanged sends a PATCH that must be refused with 400 naming naming, and checks that the
// row, its ETag and the audit log did not move.
func (e *env) refusedUnchanged(t *testing.T, id string, b map[string]any, naming string) {
	t.Helper()
	before, etag := e.row(t, id), e.get(t, id).etag
	audits := e.count(t, "SELECT count(*) FROM qr_code_audit_log")
	if a := e.patch(t, id, b, ""); a.status != http.StatusBadRequest || !strings.Contains(a.text, naming) {
		t.Fatalf("%d %s, want 400 naming %q", a.status, a.text, naming)
	}
	if after := e.row(t, id); after != before || e.get(t, id).etag != etag {
		t.Errorf("a refused PATCH wrote the row:\n%+v\n%+v", before, after)
	}
	if n := e.count(t, "SELECT count(*) FROM qr_code_audit_log"); n != audits {
		t.Errorf("a refused PATCH wrote %d audit rows", n-audits)
	}
}

// reserve puts the QR Code in PAYMENT_INITIATED with a window of ttl (0 is no lapse).
func (e *env) reserve(t *testing.T, id string, ttl time.Duration) {
	t.Helper()
	uid, _ := httpapi.ParseID(id)
	_, err := e.store.Transition(t.Context(), uid, nil, func(row domain.Row, last *domain.NotificationRecord, now time.Time) (domain.Decision, error) {
		return domain.UpdateStatus(row, domain.StatusUpdate{Status: domain.StatusPaymentInitiated}, last, now,
			domain.Settings{ReservationTTL: ttl})
	})
	if err != nil {
		t.Fatal(err)
	}
}

// The owner's PATCH rules of 2026-11-15, on the merged result, against PostgreSQL.
func TestPatchRulesAgainstPostgres(t *testing.T) {
	e := newEnv(t)
	for _, tc := range []struct {
		name string
		test func(t *testing.T, e *env)
	}{
		{"TheMergedTipIsHeldToTheCreatesRule", testTheMergedTipIsHeldToTheCreatesRule},
		{"ARefusedTipCarriesNoOffer", testARefusedTipCarriesNoOffer},
		{"ADateThePatchSetsIsNotPast", testADateThePatchSetsIsNotPast},
		{"AnEarlierValidUntilCutsTheReservationBack", testAnEarlierValidUntilCutsTheReservationBack},
		{"WhatJavaDroppedOrFailedOnIsApplied", testWhatJavaDroppedOrFailedOnIsApplied},
	} {
		e.clock.set(time.Date(2026, 11, 15, 12, 0, 0, 0, time.UTC))
		t.Run(tc.name, func(t *testing.T) { tc.test(t, e) })
	}
}

// Owner 2026-11-15, ADR-0017, X9-PATCH-512: the merged tip is held to the create's rule. An
// allowed tip with neither a range nor presets is refused in the create's words; one whose presets
// the PATCH left out keeps the stored presets, and is an offer.
func testTheMergedTipIsHeldToTheCreatesRule(t *testing.T, e *env) {
	id := e.create(t, nil)
	e.refusedUnchanged(t, id, patchOf(1000, map[string]any{"bill": patchBill(map[string]any{"tip": map[string]any{"allowed": true}})}),
		"tip.allow: should inform range or presets when tip is allowed")

	presets := e.create(t, map[string]any{"bill": map[string]any{"description": "blackbox",
		"tip": map[string]any{"allowed": true, "presets": []any{10, 15}}, "amountDue": map[string]any{"amount": 1000, "currency": "USDC"}}})
	if a := e.patch(t, presets, patchOf(1000, map[string]any{"bill": patchBill(map[string]any{"tip": map[string]any{"allowed": true}})}), ""); a.status != http.StatusOK {
		t.Fatalf("an allowed tip that keeps its stored presets: %d %s", a.status, a.text)
	}
	tip := e.get(t, presets).body["bill"].(map[string]any)["tip"]
	if !cmp.Equal(tip, map[string]any{"allowed": true, "presets": []any{float64(10), float64(15)}}) {
		t.Errorf("tip after the PATCH: %v", tip)
	}
}

// ADR-0017, X9-PATCH-519, X9-PATCH-520: a PATCH that refuses tips and still sends a range or
// presets is refused in the create's words, where it used to answer 200 and drop them. Refusing
// tips with nothing else, or with presets null, turns the stored offer off.
func testARefusedTipCarriesNoOffer(t *testing.T, e *env) {
	const naming = "tip.allow: cannot provided range or presets when tip is not allowed"
	tipPatch := func(tip map[string]any) map[string]any {
		return patchOf(1000, map[string]any{"bill": patchBill(map[string]any{"tip": tip})})
	}
	tipRange := map[string]any{"min": 5, "max": 25}
	offer := map[string]any{"allowed": true, "range": tipRange, "presets": []any{10, 15}}
	withOffer := map[string]any{"bill": map[string]any{"description": "blackbox", "tip": offer,
		"amountDue": map[string]any{"amount": 1000, "currency": "USDC"}}}

	for _, stored := range []map[string]any{nil, withOffer} {
		id := e.create(t, stored)
		for _, tip := range []map[string]any{
			{"allowed": false, "range": tipRange},
			{"allowed": false, "presets": []any{10, 15}},
			{"allowed": false, "range": tipRange, "presets": []any{10, 15}},
		} {
			e.refusedUnchanged(t, id, tipPatch(tip), naming)
		}
	}

	for _, off := range []map[string]any{{"allowed": false}, {"allowed": false, "presets": nil}} {
		id := e.create(t, withOffer)
		if a := e.patch(t, id, tipPatch(off), ""); a.status != http.StatusOK {
			t.Fatalf("turning the tip off with %v: %d %s", off, a.status, a.text)
		}
		if tip := e.get(t, id).body["bill"].(map[string]any)["tip"]; !cmp.Equal(tip, map[string]any{"allowed": false}) {
			t.Errorf("tip after it was turned off with %v: %v", off, tip)
		}
	}
}

// Owner 2026-11-15, X9-PATCH-513, X9-PATCH-514: a date the PATCH sets is not in the past on the
// payee's clock, in the create's words, the edge included; a date it leaves as stored is not judged
// again, so a biller can extend a QR Code that has expired.
func testADateThePatchSetsIsNotPast(t *testing.T, e *env) {
	now := e.clock.Now() // 2026-11-15T12:00:00Z
	invoice := func(due string) map[string]any {
		return map[string]any{"number": "INV-1", "date": "2026-01-01", "dueDate": due}
	}
	id := e.create(t, map[string]any{"validUntil": "2026-11-15T14:00:00Z",
		"bill": map[string]any{"description": "blackbox", "invoice": invoice("2026-11-15T13:00:00Z"),
			"amountDue": map[string]any{"amount": 1000, "currency": "USDC"}},
		"paymentMethods": []any{solanaMethodUntil(1000, "2026-11-15T13:00:00Z")}})
	stored := []any{solanaMethodUntil(1000, "2026-11-15T13:00:00Z")}

	e.refusedUnchanged(t, id, map[string]any{"bill": patchBill(map[string]any{"invoice": invoice("2026-11-15T11:59:59.999Z")}),
		"paymentMethods": stored}, "bill.invoice.dueDate: must be after or equal to actual date.")
	e.refusedUnchanged(t, id, map[string]any{"bill": patchBill(map[string]any{"invoice": invoice("2026-11-15T13:00:00Z"),
		"amountDue": map[string]any{"amount": 1000, "adjustments": map[string]any{"formula": "FixedDiscountLateFeeLinearInterest",
			"parameters": map[string]any{"discounts": []any{map[string]any{"daysBefore": 1, "discount": 10, "explanation": "d"}},
				"lateFees": map[string]any{"fixed": 0, "perDay": 0, "explanation": "none"}}}}}),
		"paymentMethods": stored}, "bill.amountDue.adjustments.discounts[0]: target date must be after the current date.")

	// An hour and a half later the method has lapsed, but the QR Code has not: moving validUntil
	// into the past is refused, the stored method being left alone; to now exactly is accepted.
	e.clock.set(now.Add(90 * time.Minute))
	e.refusedUnchanged(t, id, map[string]any{"validUntil": "2026-11-15T13:29:59.999Z", "paymentMethods": stored},
		"validUntil: must not be less than the current date.")
	if a := e.patch(t, id, map[string]any{"validUntil": "2026-11-15T13:30:00Z", "paymentMethods": stored}, ""); a.status != http.StatusOK {
		t.Fatalf("validUntil set to now exactly is not past: %d %s", a.status, a.text)
	}

	// Past everything: extending the QR Code and its method, the bill restated with its past due
	// date, is accepted; the dates it leaves as they are are not judged again.
	e.clock.set(now.Add(3 * time.Hour))
	if a := e.patch(t, id, map[string]any{"validUntil": "2026-11-16T12:00:00Z",
		"bill":           patchBill(map[string]any{"invoice": invoice("2026-11-15T13:00:00Z")}),
		"paymentMethods": []any{solanaMethodUntil(1000, "2026-11-16T12:00:00Z")}}, ""); a.status != http.StatusOK {
		t.Fatalf("extending an expired QR Code: %d %s", a.status, a.text)
	}
}

// Owner 2026-11-15, ADR-0019, X9-PATCH-515: a PATCH that moves validUntil before the end of a
// running reservation cuts the reservation back to it; a later validUntil does not lengthen it; a
// reservation that never lapses is left alone (ADR-0036).
func testAnEarlierValidUntilCutsTheReservationBack(t *testing.T, e *env) {
	now := e.clock.Now()
	cut := now.Add(30 * time.Second)
	toCut := map[string]any{"validUntil": contract.FormatInstant(cut),
		"paymentMethods": []any{solanaMethodUntil(1000, contract.FormatInstant(cut))}}

	id := e.create(t, nil)
	e.reserve(t, id, 90*time.Second)
	if a := e.patch(t, id, toCut, ""); a.status != http.StatusOK {
		t.Fatalf("PATCH: %d %s", a.status, a.text)
	}
	if q := e.stored(t, id); !q.ReservedUntil.Equal(cut) || q.Status != domain.StatusPaymentInitiated {
		t.Errorf("reserved until %v (%s), want %v", q.ReservedUntil, q.Status, cut)
	}
	e.clock.set(cut.Add(time.Millisecond))
	if s := e.get(t, id).body["status"]; s != "ACTIVE" {
		t.Errorf("past the cut reservation the QR Code reads %v, want ACTIVE", s)
	}

	e.clock.set(now)
	later := e.create(t, nil)
	e.reserve(t, later, 90*time.Second)
	if a := e.patch(t, later, patchOf(900, map[string]any{"validUntil": "2030-12-31T23:59:59Z"}), ""); a.status != http.StatusOK {
		t.Fatalf("PATCH: %d %s", a.status, a.text)
	}
	if q := e.stored(t, later); !q.ReservedUntil.Equal(now.Add(90 * time.Second)) {
		t.Errorf("a PATCH that does not move validUntil earlier moved the reservation to %v", q.ReservedUntil)
	}

	held := e.create(t, nil)
	e.reserve(t, held, 0)
	if a := e.patch(t, held, toCut, ""); a.status != http.StatusOK {
		t.Fatalf("PATCH: %d %s", a.status, a.text)
	}
	if q := e.stored(t, held); !q.ReservedUntil.Equal(domain.NoLapse) {
		t.Errorf("a reservation that never lapses was cut to %v", q.ReservedUntil)
	}
}

// Story 9.7's Java findings, which Go answered right from the start (X9-PATCH-516 to -518): an
// order, an invoice with its invoicee and adjustments sent to a bill that had none are applied; a
// currency named twice is refused; editable null removes editable, and editable {} on a method that
// had none is the create's refusal.
func testWhatJavaDroppedOrFailedOnIsApplied(t *testing.T, e *env) {
	id := e.create(t, nil)
	added := patchBill(map[string]any{"order": map[string]any{"number": "ORD-1"},
		"invoice": map[string]any{"number": "INV-1", "date": "2030-01-01", "dueDate": "2030-12-30T23:59:59Z",
			"invoicee": map[string]any{"name": "Ann"}},
		"amountDue": map[string]any{"amount": 1000, "adjustments": map[string]any{"formula": "FixedDiscountLateFeeLinearInterest",
			"parameters": map[string]any{"lateFees": map[string]any{"fixed": 1, "perDay": 1, "explanation": "late"}}}}})
	if a := e.patch(t, id, patchOf(1000, map[string]any{"bill": added}), ""); a.status != http.StatusOK {
		t.Fatalf("PATCH: %d %s", a.status, a.text)
	}
	bill := e.get(t, id).body["bill"].(map[string]any)
	invoice, _ := bill["invoice"].(map[string]any)
	if bill["order"] == nil || invoice["invoicee"] == nil || bill["amountDue"].(map[string]any)["adjustments"] == nil {
		t.Errorf("what the PATCH added to the bill is not there: %v", bill)
	}

	e.refusedUnchanged(t, id, map[string]any{"paymentMethods": []any{solanaMethod(700), solanaMethod(800)}},
		"paymentMethods: must not contain duplicated currency: USDC")
	e.refusedUnchanged(t, id, patchOf(700, map[string]any{"bill": nil}), "bill: must not be updated with null.")

	editable := e.create(t, map[string]any{"paymentMethods": []any{map[string]any{"currency": "USDC",
		"validUntil": "2030-12-31T23:59:59Z", "amount": 1000, "editable": map[string]any{"range": map[string]any{"min": 1, "max": 2000}},
		"networks": map[string]any{"solana": map[string]any{"recipient": recipient}}}}})
	m := solanaMethod(1000)
	m["editable"] = nil
	if a := e.patch(t, editable, map[string]any{"paymentMethods": []any{m}}, ""); a.status != http.StatusOK {
		t.Fatalf("editable null: %d %s", a.status, a.text)
	}
	if got := e.get(t, editable).body["paymentMethods"].([]any)[0].(map[string]any); got["editable"] != nil {
		t.Errorf("editable null left %v", got["editable"])
	}
	m["editable"] = map[string]any{}
	e.refusedUnchanged(t, editable, map[string]any{"paymentMethods": []any{m}},
		"Editable amount range is required when the amount is editable.")
}
