// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package paymentrequest

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/dbtest"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/store"
)

// ttl is the reservation window these tests run with.
const ttl = 90 * time.Second

var start = time.Date(2026, 11, 15, 12, 0, 0, 0, time.UTC)

// put sends a status-update; ifMatch is sent as the header when not empty.
func (e *env) put(t *testing.T, id, payload, ifMatch string) reply {
	t.Helper()
	r := httptest.NewRequestWithContext(context.Background(), http.MethodPut,
		"/api/v1/payment-request/"+id+"/status-update", strings.NewReader(payload))
	r.Header.Set("Content-Type", "application/json")
	if ifMatch != "" {
		r.Header.Set("If-Match", ifMatch)
	}
	w := httptest.NewRecorder()
	e.api.ServeHTTP(w, r)
	a := reply{status: w.Code, etag: w.Header().Get("ETag"), text: w.Body.String()}
	_ = json.Unmarshal(w.Body.Bytes(), &a.body)
	return a
}

// mustPut is put that must answer want.
func (e *env) mustPut(t *testing.T, id, payload, ifMatch string, want int) reply {
	t.Helper()
	a := e.put(t, id, payload, ifMatch)
	if a.status != want {
		t.Fatalf("PUT %s: %d, want %d: %s", payload, a.status, want, a.text)
	}
	return a
}

// stored reads the QR Code as the store reads it.
func (e *env) stored(t *testing.T, id string) store.QRCode {
	t.Helper()
	uid, _ := httpapi.ParseID(id)
	q, err := e.store.Get(t.Context(), uid)
	if err != nil {
		t.Fatal(err)
	}
	return q
}

// events lists the QR Code's events in seq order as "<type code> <network> <transaction id>".
func (e *env) events(t *testing.T, id string) []string {
	t.Helper()
	uid, _ := httpapi.ParseID(id)
	out := e.db.Psql(t.Context(), t, "SET search_path TO "+dbtest.Schema+"; SELECT type_code || ' ' || coalesce(nullif(network, ''), '-') || ' ' || "+
		"coalesce(nullif(transaction_id, ''), '-') || ' ' || qr_code_revision FROM payment_event WHERE qr_code_id = '"+
		uid.String()+"' ORDER BY seq")
	if out == "" {
		return nil
	}
	return strings.Split(out, "\n")
}

const (
	putPaid      = `{"status": "PAID", "network": "solana", "endToEndId": "E2E-1"}`
	putInitiated = `{"status": "PAYMENT_INITIATED"}`
	putCancelled = `{"status": "CANCELLED"}`
	putActive    = `{"status": "ACTIVE"}`
)

func TestStatusUpdateAgainstPostgres(t *testing.T) {
	e := newEnv(t)
	NewStatusUpdates(e.store, ttl).Register(e.api)
	for _, tc := range []struct {
		name string
		test func(t *testing.T, e *env)
	}{
		{"InitiatedReservesForNobody", testInitiatedReservesForNobody},
		{"InitiatedOnALapsedReservationReservesAgain", testInitiatedOnALapsedReservationReservesAgain},
		{"InitiatedRefusals", testInitiatedRefusals},
		{"PaidAndCancelledFromEveryOpenStatus", testPaidAndCancelledFromEveryOpenStatus},
		{"PaidAndCancelledRefusals", testPaidAndCancelledRefusals},
		{"ActiveReleasesAReservation", testActiveReleasesAReservation},
		{"ActiveRefusals", testActiveRefusals},
		{"TheSameStatusIsARetry", testTheSameStatusIsARetry},
		{"IfMatch", testIfMatch},
		{"RequestRefusals", testRequestRefusals},
	} {
		e.clock.set(start)
		t.Run(tc.name, func(t *testing.T) { tc.test(t, e) })
	}
}

// S9.8-AC1, X9-MAP-010, ADR-0036: PAYMENT_INITIATED from ACTIVE takes a reservation held by
// nobody identifiable, with a window, and writes payment.initiated in the same transaction; the
// revision does not move and the ETag follows the status (S9.8-AC6).
func testInitiatedReservesForNobody(t *testing.T, e *env) {
	id := e.create(t, nil)
	a := e.mustPut(t, id, putInitiated, "", http.StatusOK)
	if a.body["id"] != id || a.body["status"] != "PAYMENT_INITIATED" || len(a.body) != 2 {
		t.Fatalf("answer %s", a.text)
	}
	q := e.stored(t, id)
	if q.Status != domain.StatusPaymentInitiated || q.ReservedBy != (domain.Holder{}) || q.HashRegistered ||
		!q.ReservedUntil.Equal(start.Add(ttl)) || q.Revision != 0 || !q.RevisedAt.Equal(start) {
		t.Fatalf("row %+v", q.Row)
	}
	if got := e.events(t, id); len(got) != 1 || got[0] != "INIT - - 0" {
		t.Fatalf("events %q, want one payment.initiated with no settlement details", got)
	}
	if g := e.get(t, id); g.etag != `"0-PAYMENT_INITIATED"` || g.body["revision"] != float64(0) {
		t.Fatalf("GET: ETag %s revision %v", g.etag, g.body["revision"])
	}
}

// S9.8-AC1, BUG-002: a lapsed reservation is ACTIVE in effect, so PAYMENT_INITIATED on it is a
// transition, not a retry: the payment system takes the QR Code again, with payment.initiated.
func testInitiatedOnALapsedReservationReservesAgain(t *testing.T, e *env) {
	id := e.create(t, nil)
	e.mustPut(t, id, putInitiated, "", http.StatusOK)
	later := start.Add(ttl + time.Second)
	e.clock.set(later)
	if g := e.get(t, id); g.body["status"] != "ACTIVE" {
		t.Fatalf("a lapsed reservation reads %v", g.body["status"])
	}
	e.mustPut(t, id, putInitiated, "", http.StatusOK)
	if q := e.stored(t, id); !q.ReservedUntil.Equal(later.Add(ttl)) {
		t.Fatalf("reserved_until %v, want a fresh window", q.ReservedUntil)
	}
	if got := e.events(t, id); len(got) != 2 {
		t.Fatalf("events %q, want two payment.initiated", got)
	}
}

// S9.8-AC1, R-SM-14: PAYMENT_INITIATED carries no settlement details, and on PAID or CANCELLED it
// is a 409 naming the status found.
func testInitiatedRefusals(t *testing.T, e *env) {
	id := e.create(t, nil)
	a := e.mustPut(t, id, `{"status": "PAYMENT_INITIATED", "endToEndId": "E2E-1"}`, "", http.StatusBadRequest)
	if v := a.body["violations"].([]any); v[0] != "paymentDetails must be not informed when initiating a payment." {
		t.Fatalf("violations %v", v)
	}
	for _, closing := range []string{putPaid, putCancelled} {
		id := e.create(t, nil)
		e.mustPut(t, id, closing, "", http.StatusOK)
		before := e.stored(t, id)
		a := e.mustPut(t, id, putInitiated, "", http.StatusConflict)
		if a.body["type"] != httpapi.TypeStatusConflict || a.body["currentStatus"] != string(before.Status) {
			t.Fatalf("409 %s", a.text)
		}
		if after := e.stored(t, id); after.LockVersion != before.LockVersion || len(e.events(t, id)) != 1 {
			t.Fatalf("a refused PAYMENT_INITIATED wrote: %+v", after.Row)
		}
	}
}

// S9.8-AC2, X9-MAP-011, -012, ADR-0020: PAID and CANCELLED are accepted from a stored ACTIVE or
// PAYMENT_INITIATED, a lapsed reservation and a QR Code past validUntil included. PAID records the
// End-to-End ID and the network, and payment.cleared carries them; neither moves the revision.
func testPaidAndCancelledFromEveryOpenStatus(t *testing.T, e *env) {
	for _, from := range []string{"active", "reserved", "lapsed", "expired"} {
		for _, to := range []struct {
			payload, status, event string
		}{
			{putPaid, "PAID", "CLRD solana E2E-1 0"},
			{putCancelled, "CANCELLED", "CANC - - 0"},
		} {
			t.Run(from+"/"+to.status, func(t *testing.T) {
				e.clock.set(start)
				id := e.create(t, map[string]any{"validUntil": "2026-11-15T12:10:00Z", "paymentMethods": []any{map[string]any{
					"currency": "USDC", "validUntil": "2026-11-15T12:10:00Z", "amount": 1000,
					"networks": map[string]any{"solana": map[string]any{"recipient": recipient}},
				}}})
				initiated := make([]string, 0, 1)
				switch from {
				case "reserved":
					e.mustPut(t, id, putInitiated, "", http.StatusOK)
					initiated = append(initiated, "INIT - - 0")
				case "lapsed":
					e.mustPut(t, id, putInitiated, "", http.StatusOK)
					initiated = append(initiated, "INIT - - 0")
					e.clock.set(start.Add(ttl + time.Second))
				case "expired":
					e.clock.set(start.Add(time.Hour))
				}
				a := e.mustPut(t, id, to.payload, "", http.StatusOK)
				if a.body["status"] != to.status {
					t.Fatalf("answer %s", a.text)
				}
				q := e.stored(t, id)
				if string(q.Status) != to.status || q.Revision != 0 || q.ReservedBy != (domain.Holder{}) ||
					!q.ReservedUntil.Equal(domain.NoLapse) {
					t.Fatalf("row %+v", q.Row)
				}
				if to.status == "PAID" && (q.EndToEndID != "E2E-1" || q.PaymentNetwork != "solana") {
					t.Fatalf("payment details %q %q", q.EndToEndID, q.PaymentNetwork)
				}
				want := append(initiated, to.event)
				if got := e.events(t, id); strings.Join(got, ",") != strings.Join(want, ",") {
					t.Fatalf("events %q, want %q", got, want)
				}
				if g := e.get(t, id); g.etag != `"0-`+to.status+`"` {
					t.Fatalf("ETag %s", g.etag)
				}
			})
		}
	}
}

// S9.8-AC2: PAID on CANCELLED and CANCELLED on PAID are 400; PAID needs both details, and a
// network the QR Code does not offer is refused before anything else is decided.
func testPaidAndCancelledRefusals(t *testing.T, e *env) {
	for _, tc := range []struct {
		name, first, then, violation string
	}{
		{"paid on cancelled", putCancelled, putPaid, "The QRCode needs to be active to be paid. Current status: CANCELLED"},
		{"cancelled on paid", putPaid, putCancelled, "The QRCode needs to be active to be cancelled. Current status: PAID"},
		{"paid without details", "", `{"status": "PAID"}`,
			"Both endToEndId and paymentNetwork fields must be provided when marking as paid."},
		{"a network not offered", "", `{"status": "PAID", "network": "fednow", "endToEndId": "E2E-1"}`,
			"Network fednow is not a valid payment method."},
	} {
		t.Run(tc.name, func(t *testing.T) {
			id := e.create(t, nil)
			if tc.first != "" {
				e.mustPut(t, id, tc.first, "", http.StatusOK)
			}
			before, events := e.stored(t, id), len(e.events(t, id))
			a := e.mustPut(t, id, tc.then, "", http.StatusBadRequest)
			if v := a.body["violations"].([]any); len(v) != 1 || v[0] != tc.violation {
				t.Fatalf("violations %v", v)
			}
			if after := e.stored(t, id); after.LockVersion != before.LockVersion || len(e.events(t, id)) != events {
				t.Fatal("a refused status-update wrote")
			}
		})
	}
}

// S9.8-AC3, X9-MAP-013, ADR-0036: ACTIVE on a held reservation releases it, holder and window
// cleared, with no event, also once a hash is registered.
func testActiveReleasesAReservation(t *testing.T, e *env) {
	for _, hash := range []bool{false, true} {
		id := e.create(t, nil)
		e.mustPut(t, id, putInitiated, "", http.StatusOK)
		if hash {
			uid, _ := httpapi.ParseID(id)
			e.db.Psql(t.Context(), t, "SET search_path TO "+dbtest.Schema+"; UPDATE qr_code SET hash_registered = TRUE, reserved_until = '9999-12-31', "+
				"reserved_by_payer_info = 'payer@example.com', reserved_by_signer_subject = 'CN=payer' WHERE id = '"+
				uid.String()+"'")
			e.clock.set(start.Add(ttl * 2)) // a registered hash stops the window
		}
		a := e.mustPut(t, id, putActive, "", http.StatusOK)
		if a.body["status"] != "ACTIVE" {
			t.Fatalf("answer %s", a.text)
		}
		q := e.stored(t, id)
		if q.Status != domain.StatusActive || q.ReservedBy != (domain.Holder{}) || q.HashRegistered ||
			!q.ReservedUntil.Equal(domain.NoLapse) || q.Revision != 0 {
			t.Fatalf("hash %v: row %+v", hash, q.Row)
		}
		if got := e.events(t, id); len(got) != 1 {
			t.Fatalf("hash %v: events %q, want only the reservation's", hash, got)
		}
		e.clock.set(start)
	}
}

// S9.8-AC3: ACTIVE on a lapsed reservation, on PAID or on CANCELLED is a 400.
func testActiveRefusals(t *testing.T, e *env) {
	for _, tc := range []struct {
		name, first string
		lapse       bool
	}{
		{"lapsed", putInitiated, true},
		{"paid", putPaid, false},
		{"cancelled", putCancelled, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			e.clock.set(start)
			id := e.create(t, nil)
			e.mustPut(t, id, tc.first, "", http.StatusOK)
			if tc.lapse {
				e.clock.set(start.Add(ttl + time.Second))
			}
			before := e.stored(t, id)
			a := e.mustPut(t, id, putActive, "", http.StatusBadRequest)
			if v := a.body["violations"].([]any); v[0] != "Only QR Codes with PAYMENT_INITIATED status can be reactivated." {
				t.Fatalf("violations %v", v)
			}
			if after := e.stored(t, id); after.LockVersion != before.LockVersion {
				t.Fatal("a refused ACTIVE wrote")
			}
		})
	}
}

// S9.8-AC4, ADR-0020: the status the QR Code already has, stored and effective, is a 200 that
// writes nothing: no lock_version, no revised_at, no event; a repeated PAID keeps the first
// reference.
func testTheSameStatusIsARetry(t *testing.T, e *env) {
	for _, tc := range []struct{ first, again string }{
		{"", putActive},
		{putInitiated, putInitiated},
		{putPaid, `{"status": "PAID", "network": "solana", "endToEndId": "E2E-2"}`},
		{putCancelled, putCancelled},
	} {
		e.clock.set(start)
		id := e.create(t, nil)
		if tc.first != "" {
			e.mustPut(t, id, tc.first, "", http.StatusOK)
		}
		before, events := e.stored(t, id), e.events(t, id)
		e.clock.set(start.Add(time.Second))
		a := e.mustPut(t, id, tc.again, "", http.StatusOK)
		if a.body["status"] != string(before.Status) {
			t.Fatalf("%s: answer %s", tc.again, a.text)
		}
		after := e.stored(t, id)
		if after.LockVersion != before.LockVersion || !after.RevisedAt.Equal(before.RevisedAt) ||
			after.Revision != before.Revision || after.EndToEndID != before.EndToEndID ||
			!after.ReservedUntil.Equal(before.ReservedUntil) {
			t.Fatalf("%s: the retry wrote: %+v", tc.again, after.Row)
		}
		if got := e.events(t, id); len(got) != len(events) {
			t.Fatalf("%s: the retry wrote an event: %q", tc.again, got)
		}
	}
}

// S9.8-AC5, ADR-0020: only the current tag passes; otherwise 412 with currentStatus and
// currentRevision and nothing written. A retry with the first attempt's tag after it succeeded is a
// 412; the same retry without If-Match is the 200 of S9.8-AC4.
func testIfMatch(t *testing.T, e *env) {
	id := e.create(t, nil)
	tag := e.get(t, id).etag
	e.mustPut(t, id, putInitiated, `"7-ACTIVE"`, http.StatusPreconditionFailed)
	if len(e.events(t, id)) != 0 || e.stored(t, id).Status != domain.StatusActive {
		t.Fatal("a 412 wrote")
	}
	e.mustPut(t, id, putPaid, tag, http.StatusOK)
	a := e.mustPut(t, id, putPaid, tag, http.StatusPreconditionFailed)
	if a.body["type"] != httpapi.TypePreconditionFailed || a.body["currentStatus"] != "PAID" ||
		a.body["currentRevision"] != float64(0) {
		t.Fatalf("412 %s", a.text)
	}
	e.mustPut(t, id, putPaid, "", http.StatusOK)
	e.mustPut(t, id, putPaid, `W/"0-PAID"`, http.StatusOK)
	e.mustPut(t, id, putPaid, "*", http.StatusOK)
	if got := e.events(t, id); len(got) != 1 {
		t.Fatalf("events %q, want one payment.cleared", got)
	}
}

// Java's refusals of the request itself: an unknown QR Code (404), an id that is not one, a status
// missing or outside the enum.
func testRequestRefusals(t *testing.T, e *env) {
	unknown := "0123456789ABCDEF0123456789ABCDEF"
	if a := e.put(t, unknown, putPaid, ""); a.status != http.StatusNotFound ||
		a.body["violations"].([]any)[0] != "Could not find QR code with id: "+unknown {
		t.Fatalf("unknown: %d %s", a.status, a.text)
	}
	for _, tc := range []struct {
		id, payload, typ, violation string
	}{
		{"0123456789ABCDEF0123456789ABCDEZ", putPaid, httpapi.TypeValidation, "QRCodeId is invalid."},
		{"0123", putPaid, httpapi.TypeValidation, "putPaymentRequestStatusUpdate.id: size must be between 32 and 32"},
		{unknown, `{}`, httpapi.TypeValidation, "status: must not be null"},
		{unknown, `{"status": "paid"}`, httpapi.TypeMalformedJSON, ""},
	} {
		a := e.put(t, tc.id, tc.payload, "")
		if a.status != http.StatusBadRequest || a.body["type"] != tc.typ {
			t.Fatalf("%s %s: %d %s", tc.id, tc.payload, a.status, a.text)
		}
		if tc.violation != "" && a.body["violations"].([]any)[0] != tc.violation {
			t.Fatalf("%s %s: %s", tc.id, tc.payload, a.text)
		}
	}
}
