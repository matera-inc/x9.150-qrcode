// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package paymentrequest

import (
	"context"
	"encoding/json"
	"maps"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
)

const fedNowAddress = `{"routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "plaintext"}`

// solanaMethod is a USDC method of the create sample, with amount.
func solanaMethod(amount int64) map[string]any {
	return map[string]any{"currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": amount,
		"networks": map[string]any{"solana": map[string]any{"recipient": recipient}}}
}

func fedNowMethod(amount int64) map[string]any {
	var bank map[string]any
	_ = json.Unmarshal([]byte(fedNowAddress), &bank)
	return map[string]any{"currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": amount,
		"networks": map[string]any{"fednow": bank}}
}

// solanaMethodUntil is solanaMethod valid until until.
func solanaMethodUntil(amount int64, until string) map[string]any {
	m := solanaMethod(amount)
	m["validUntil"] = until
	return m
}

// patchOf is a PATCH body: the sample's USDC method at amount, and the overrides.
func patchOf(amount int64, override map[string]any) map[string]any {
	b := map[string]any{"paymentMethods": []any{solanaMethod(amount)}}
	maps.Copy(b, override)
	return b
}

func (e *env) patch(t *testing.T, id string, b map[string]any, ifMatch string) reply {
	t.Helper()
	raw, err := json.Marshal(b)
	if err != nil {
		t.Fatal(err)
	}
	r := httptest.NewRequestWithContext(context.Background(), http.MethodPatch, "/api/v1/payment-request/"+id,
		strings.NewReader(string(raw)))
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

// row is what a PATCH may write to a QR Code: its columns and request_json.
type row struct {
	Location, EMV, RequestJSON string
	Revision                   int
	RevisedAt                  time.Time
}

func (e *env) row(t *testing.T, id string) row {
	t.Helper()
	uid, _ := httpapi.ParseID(id)
	var (
		r   row
		loc uuid.UUID
	)
	if err := e.pool.QueryRow(t.Context(), `SELECT location_id, emv_content, CAST(request_json AS VARCHAR(65535)),
		revision, revised_at FROM qr_code WHERE id = $1`, uid).Scan(&loc, &r.EMV, &r.RequestJSON, &r.Revision, &r.RevisedAt); err != nil {
		t.Fatal(err)
	}
	r.Location = httpapi.FormatID(loc)
	return r
}

type auditEntry struct {
	Revision  int
	ChangedAt time.Time
	Fields    string
	Previous  map[string]any
}

// audit is the QR Code's audit log, newest first. No endpoint reads it (S9.7-AC5); this test does.
func (e *env) audit(t *testing.T, id string) []auditEntry {
	t.Helper()
	uid, _ := httpapi.ParseID(id)
	rows, err := e.pool.Query(t.Context(), `SELECT revision, changed_at, changed_fields,
		CAST(previous_values AS VARCHAR(65535)) FROM qr_code_audit_log WHERE qr_code_id = $1 ORDER BY revision DESC`, uid)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	out := []auditEntry{}
	for rows.Next() {
		var (
			a        auditEntry
			previous string
		)
		if err := rows.Scan(&a.Revision, &a.ChangedAt, &a.Fields, &previous); err != nil {
			t.Fatal(err)
		}
		if err := json.Unmarshal([]byte(previous), &a.Previous); err != nil {
			t.Fatal(err)
		}
		out = append(out, a)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	return out
}

// lastAudit is the newest row of the QR Code's audit log; the test stops when there is none.
func (e *env) lastAudit(t *testing.T, id string) auditEntry {
	t.Helper()
	entries := e.audit(t, id)
	if len(entries) == 0 {
		t.Fatal("no audit row")
		return auditEntry{}
	}
	return entries[0]
}

func TestPatchAgainstPostgres(t *testing.T) {
	e := newEnv(t)
	for _, tc := range []struct {
		name string
		test func(t *testing.T, e *env)
	}{
		{"AnAcceptedPatchIsWrittenWithItsAuditRow", testAnAcceptedPatchIsWrittenWithItsAuditRow},
		{"ARefusedPatchWritesNothing", testARefusedPatchWritesNothing},
		{"OnlyTheSixTagsChange", testOnlyTheSixTagsChange},
		{"CurrenciesAreAllOrNone", testCurrenciesAreAllOrNone},
		{"APatchMovesTheLocation", testAPatchMovesTheLocation},
		{"TheAuditLogRebuildsEveryEarlierRequest", testTheAuditLogRebuildsEveryEarlierRequest},
	} {
		e.clock.set(time.Date(2026, 11, 15, 12, 0, 0, 0, time.UTC))
		t.Run(tc.name, func(t *testing.T) { tc.test(t, e) })
	}
}

// S9.7-AC1, S9.7-AC3, X9-MAP-002: an accepted PATCH answers 200 with the create's four members
// and the new ETag; the row holds the merged request at revision n + 1 and revised_at now, with an
// audit row (id, n, now, the changed tags, their previous values) and no event.
func testAnAcceptedPatchIsWrittenWithItsAuditRow(t *testing.T, e *env) {
	id := e.create(t, nil)
	created := e.row(t, id)
	events := e.count(t, "SELECT count(*) FROM payment_event")
	e.clock.set(e.clock.Now().Add(time.Minute))

	a := e.patch(t, id, patchOf(600, map[string]any{"unstructured": "second half"}), `"0-ACTIVE"`)

	if a.status != http.StatusOK || a.etag != `"1-ACTIVE"` {
		t.Fatalf("PATCH: %d ETag %s %s", a.status, a.etag, a.text)
	}
	if a.body["id"] != id || a.body["qrCode"] != created.EMV || len(a.body) != 4 {
		t.Errorf("the answer is not the create's four members: %v", a.body)
	}
	got := e.get(t, id).body
	if got["revision"] != float64(1) || got["revisedAt"] != "2026-11-15T12:01:00Z" || got["unstructured"] != "second half" ||
		got["paymentMethods"].([]any)[0].(map[string]any)["amount"] != float64(600) || got["status"] != "ACTIVE" {
		t.Errorf("after the PATCH: %v", got)
	}
	log := e.audit(t, id)
	want := []auditEntry{{Revision: 0, ChangedAt: time.Date(2026, 11, 15, 12, 1, 0, 0, time.UTC),
		Fields: "unstructured,paymentMethods", Previous: map[string]any{
			"unstructured":   nil,
			"paymentMethods": []any{map[string]any{"currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": float64(1000), "networks": map[string]any{"solana": map[string]any{"recipient": recipient}}}},
		}}}
	if diff := cmp.Diff(want, log); diff != "" {
		t.Errorf("audit log (-want +got):\n%s", diff)
	}
	if n := e.count(t, "SELECT count(*) FROM payment_event"); n != events {
		t.Errorf("a PATCH wrote %d events, want none (X9-MAP-002)", n-events)
	}

	// The second PATCH changes a tag beside unchanged methods: paymentMethods is not in its row.
	e.clock.set(e.clock.Now().Add(time.Minute))
	if a := e.patch(t, id, patchOf(600, map[string]any{"additionalInformation": []any{map[string]any{"key": "k", "value": "v"}}}), ""); a.status != http.StatusOK {
		t.Fatalf("second PATCH: %d %s", a.status, a.text)
	}
	if last := e.lastAudit(t, id); last.Revision != 1 || last.Fields != "additionalInformation" ||
		!cmp.Equal(last.Previous, map[string]any{"additionalInformation": nil}) {
		t.Errorf("second audit row: %+v", last)
	}
}

// S9.7-AC1, S9.7-AC2, S9.7-AC4: every refusal answers as Java does and writes nothing: the row,
// its revision, its ETag and the audit log as they were.
func testARefusedPatchWritesNothing(t *testing.T, e *env) {
	invoiced := map[string]any{"description": "blackbox", "paymentTiming": "immediate",
		"invoice":   map[string]any{"number": "INV-1", "date": "2030-01-01", "dueDate": "2030-12-30T23:59:59Z"},
		"amountDue": map[string]any{"amount": 1000, "currency": "USDC"}}
	id := e.create(t, map[string]any{"bill": invoiced})
	closed := e.create(t, nil)
	e.setStatus(t, closed, domain.StatusCancelled)
	lateFee := e.create(t, map[string]any{"bill": map[string]any{"description": "fee", "paymentTiming": "immediate",
		"invoice": map[string]any{"number": "INV-1", "date": "2030-01-01", "dueDate": "2030-12-30T23:59:59Z"},
		"amountDue": map[string]any{"amount": 999999999999998999, "currency": "USDC", "adjustments": map[string]any{
			"formula":    "FixedDiscountLateFeeLinearInterest",
			"parameters": map[string]any{"lateFees": map[string]any{"fixed": 900, "perDay": 100, "explanation": "Late fee"}}}}},
		"paymentMethods": []any{solanaMethod(999999999999998999)}})
	bill := map[string]any{"description": "blackbox", "paymentTiming": "immediate", "amountDue": map[string]any{"amount": 1000}}
	pastMethod := solanaMethodUntil(700, "2026-11-15T11:00:00Z")

	for _, tc := range []struct {
		name   string
		target string
		body   map[string]any
		match  string
		status int
		naming string
	}{
		{"nothing changes (X9-PATCH-422)", id, patchOf(1000, nil), "", 400,
			"paymentMethods: can not find any paymentMethod to be updated."},
		{"a closed QR Code (X9-MAP-002)", closed, patchOf(700, nil), "", 400,
			"must be active to be updated."},
		{"a stale If-Match (X9-PATCH-502)", id, patchOf(700, nil), `"1-ACTIVE"`, 412,
			`"currentRevision":0`},
		{"a malformed If-Match (X9-PATCH-434)", id, patchOf(700, nil), "abc", 412, `"currentStatus":"ACTIVE"`},
		{"a currency not offered (ADR-0018)", id, map[string]any{"paymentMethods": []any{solanaMethod(700), fedNowMethod(700)}}, "", 400,
			"This QR Code offers USDC. A patch may change the amount of a currency already on it, never add one — USD is not here."},
		{"a currency named twice", id, map[string]any{"paymentMethods": []any{solanaMethod(700), solanaMethod(800)}}, "", 400,
			"paymentMethods: must not contain duplicated currency: USDC"},
		{"validUntil null", id, patchOf(700, map[string]any{"validUntil": nil}), "", 400, "ValidUntil must not be null."},
		{"bill null", id, patchOf(700, map[string]any{"bill": nil}), "", 400, "bill: must not be updated with null."},
		{"locationId null", id, patchOf(700, map[string]any{"locationId": nil}), "", 400, "LocationId can not be null"},
		{"a locationId that is not hexadecimal", id, patchOf(700, map[string]any{"locationId": strings.Repeat("Z", 32)}), "", 400,
			"LocationId is invalid."},
		{"validUntil before the due date, on the merged result (R-EP-27)", id,
			map[string]any{"validUntil": "2030-12-01T00:00:00Z", "paymentMethods": []any{solanaMethodUntil(1000, "2030-06-30T00:00:00Z")}}, "", 400,
			"bill.invoice.dueDate: must be before or equal to validUntil date."},
		{"a method after validUntil (X9-PATCH-426)", id,
			map[string]any{"paymentMethods": []any{solanaMethodUntil(700, "2031-01-01T00:00:00Z")}}, "", 400, "paymentMethods[0].validUntil: must be before or equal than payment request validUntil."},
		{"a changed method already past", id, map[string]any{"paymentMethods": []any{pastMethod}}, "", 400,
			"Can not update paymentMethods with currency USDC. ValidUntil must be after or equal to actual date."},
		{"a bill the contract refuses", id, patchOf(700, map[string]any{"bill": map[string]any{"description": "x"}}), "", 400,
			"bill.paymentTiming: must not be null"},
		{"an unknown solana field (X9-RAIL-503)", id, map[string]any{"paymentMethods": []any{map[string]any{
			"currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 700,
			"networks": map[string]any{"solana": map[string]any{"recipient": recipient, "x": 1}}}}}, "", 400,
			"paymentMethods[0].networks.solana.x: is not a field of solana"},
		{"an unknown fednow field (I-16)", id, map[string]any{"paymentMethods": []any{map[string]any{
			"currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 700,
			"networks": map[string]any{"fednow": map[string]any{"routingNumber": "021000021", "accountNumber": "1234567890",
				"protectionType": "plaintext", "x": 1}}}}}, "", 400, "paymentMethods[0].networks.fednow.x: is not a field of fednow"},
		{"an uninterpreted network", id, map[string]any{"paymentMethods": []any{map[string]any{
			"currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 700,
			"networks": map[string]any{"tron": map[string]any{"walletAddress": "T"}}}}}, "", 400, "Unsupported network(s): tron."},
		{"a later validUntil lets the late fee pass 18 digits (X9-AMT-423)", lateFee,
			map[string]any{"validUntil": "2031-01-01T23:59:59Z", "paymentMethods": []any{solanaMethod(999999999999998999)}}, "", 400,
			"bill.amountDue.amount: 999999999999998999 with the largest late fee"},
		{"a bill whose discount is not below the amount", id, patchOf(700, map[string]any{"bill": map[string]any{
			"description": "x", "paymentTiming": "immediate",
			"invoice": map[string]any{"number": "INV-1", "date": "2030-01-01", "dueDate": "2030-12-30T23:59:59Z"},
			"amountDue": map[string]any{"amount": 1000, "adjustments": map[string]any{"formula": "FixedDiscountLateFeeLinearInterest",
				"parameters": map[string]any{"discounts": []any{map[string]any{"daysBefore": 1, "discount": 1000, "explanation": "d"}},
					"lateFees": map[string]any{"fixed": 0, "perDay": 0, "explanation": "none"}}}}}}), "", 400,
			"Bill discount at index 0. Must not be greater than or equal to amount due."},
		{"an unknown QR Code", strings.Repeat("0", 32), patchOf(700, nil), "", 404, "Could not find QR code with id: " + strings.Repeat("0", 32)},
		{"a QR Code id that is not hexadecimal", strings.Repeat("G", 32), patchOf(700, nil), "", 400, "QRCodeId is invalid."},
		{"bill restated with an unchanged amount still on a refused currency set", id,
			map[string]any{"bill": bill, "paymentMethods": []any{fedNowMethod(700)}}, "", 400, "USD is not here"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var before row
			var etag string
			if tc.status != http.StatusNotFound && !strings.HasPrefix(tc.target, "G") {
				before, etag = e.row(t, tc.target), e.get(t, tc.target).etag
			}
			audits := e.count(t, "SELECT count(*) FROM qr_code_audit_log")
			a := e.patch(t, tc.target, tc.body, tc.match)
			if a.status != tc.status || !strings.Contains(a.text, tc.naming) {
				t.Fatalf("%d %s, want %d naming %q", a.status, a.text, tc.status, tc.naming)
			}
			if n := e.count(t, "SELECT count(*) FROM qr_code_audit_log"); n != audits {
				t.Errorf("a refused PATCH wrote %d audit rows", n-audits)
			}
			if etag == "" {
				return
			}
			if after := e.row(t, tc.target); after != before {
				t.Errorf("a refused PATCH wrote the row:\n%+v\n%+v", before, after)
			}
			if got := e.get(t, tc.target).etag; got != etag {
				t.Errorf("a refused PATCH moved the ETag from %s to %s", etag, got)
			}
		})
	}
}

// S9.7-AC1, X9-PATCH-420, X9-PATCH-423: a member that is not one of the six tags is never applied:
// a PATCH is closed (INTERPRETATION I-16), so it is refused by name and nothing moves; a bill
// restated as it is counts as a change.
func testOnlyTheSixTagsChange(t *testing.T, e *env) {
	id := e.create(t, nil)
	before := e.get(t, id).body
	a := e.patch(t, id, patchOf(900, map[string]any{"creditor": map[string]any{"name": "Another Co"}, "status": "CANCELLED",
		"revision": 7, "paymentNotification": map[string]any{"kind": "EXTERNAL"}}), "")
	if a.status != http.StatusBadRequest {
		t.Fatalf("PATCH: %d %s, want 400", a.status, a.text)
	}
	for _, key := range []string{"creditor", "status", "revision", "paymentNotification"} {
		if !strings.Contains(a.text, key+": is not a field of the payment request, whose fields are locationId, validUntil, bill, unstructured, additionalInformation and paymentMethods") {
			t.Errorf("the refusal does not name %s: %s", key, a.text)
		}
	}
	after := e.get(t, id).body
	for _, key := range []string{"creditor", "paymentNotification", "createdAt", "bill", "paymentMethods"} {
		if !cmp.Equal(before[key], after[key]) {
			t.Errorf("%s changed: %v -> %v", key, before[key], after[key])
		}
	}
	if after["status"] != "ACTIVE" || after["revision"] != float64(0) {
		t.Errorf("status %v, revision %v", after["status"], after["revision"])
	}
	if a := e.patch(t, id, patchOf(900, map[string]any{"creditor": map[string]any{"name": "Another Co"}}), ""); a.status != http.StatusBadRequest {
		t.Errorf("a PATCH changing only a member that is not a tag: %d %s, want 400 (X9-PATCH-421)", a.status, a.text)
	}

	restated := map[string]any{"description": "blackbox", "paymentTiming": "immediate", "amountDue": map[string]any{"amount": 1000}}
	if a := e.patch(t, id, patchOf(900, map[string]any{"bill": restated}), ""); a.status != http.StatusOK || a.etag != `"1-ACTIVE"` {
		t.Fatalf("a restated bill: %d %s %s", a.status, a.etag, a.text)
	}
	if got := e.get(t, id).body; !cmp.Equal(got["bill"], before["bill"]) {
		t.Errorf("a restated bill changed it: %v -> %v", before["bill"], got["bill"])
	}
	if last := e.lastAudit(t, id); last.Fields != "bill" {
		t.Errorf("a restated bill is audited as %q, want bill", last.Fields)
	}
}

// S9.7-AC2, ADR-0018: a PATCH names every currency, in any order; each method merges by currency.
func testCurrenciesAreAllOrNone(t *testing.T, e *env) {
	id := e.create(t, map[string]any{"paymentMethods": []any{solanaMethod(1000), fedNowMethod(1000)}})
	if a := e.patch(t, id, map[string]any{"paymentMethods": []any{solanaMethod(500)}}, ""); a.status != http.StatusBadRequest ||
		!strings.Contains(a.text, "A patch that changes amounts must inform every currency this QR Code offers (USDC, USD); USD was left out.") {
		t.Fatalf("a currency left out: %d %s", a.status, a.text)
	}
	if a := e.patch(t, id, map[string]any{"paymentMethods": []any{fedNowMethod(400), solanaMethod(500)}}, ""); a.status != http.StatusOK {
		t.Fatalf("every currency, in another order: %d %s", a.status, a.text)
	}
	methods := e.get(t, id).body["paymentMethods"].([]any)
	got := []any{methods[0].(map[string]any)["currency"], methods[0].(map[string]any)["amount"],
		methods[1].(map[string]any)["currency"], methods[1].(map[string]any)["amount"]}
	if diff := cmp.Diff([]any{"USDC", float64(500), "USD", float64(400)}, got); diff != "" {
		t.Errorf("merged by currency, in the stored order (-want +got):\n%s", diff)
	}
}

// S9.7-AC3: a PATCH moving the location writes it and its EMV string; a closed holder of the same
// creditor is released first, in the same transaction; a live holder keeps it; the QR Code's own
// location moves nothing.
func testAPatchMovesTheLocation(t *testing.T, e *env) {
	id := e.create(t, nil)
	before := e.row(t, id)

	own := e.patch(t, id, patchOf(1000, map[string]any{"locationId": before.Location}), "")
	if own.status != http.StatusOK || e.row(t, id).EMV != before.EMV || e.row(t, id).Location != before.Location {
		t.Fatalf("its own location: %d %s", own.status, own.text)
	}
	if last := e.lastAudit(t, id); last.Fields != "locationId" || last.Previous["locationId"] != before.Location {
		t.Errorf("audit row: %+v", last)
	}

	free := strings.Repeat("AB", 16)
	a := e.patch(t, id, patchOf(1000, map[string]any{"locationId": strings.ToLower(free)}), "")
	moved := e.row(t, id)
	if a.status != http.StatusOK || moved.Location != free || moved.EMV == before.EMV ||
		!strings.Contains(moved.EMV, "/pub/api/v1/loc/"+free) || a.body["qrCode"] != moved.EMV {
		t.Fatalf("a free location: %d %s %+v", a.status, a.text, moved)
	}
	if last := e.lastAudit(t, id); last.Previous["locationId"] != before.Location {
		t.Errorf("the previous locationId is the column's: %+v", last)
	}

	holder := e.create(t, nil)
	e.setStatus(t, holder, domain.StatusCancelled)
	held := e.row(t, holder)
	if a := e.patch(t, id, patchOf(1000, map[string]any{"locationId": held.Location}), ""); a.status != http.StatusOK {
		t.Fatalf("a closed holder's location: %d %s", a.status, a.text)
	}
	released := e.row(t, holder)
	if e.row(t, id).Location != held.Location || released.Location == held.Location || released.EMV == held.EMV ||
		released.Revision != held.Revision {
		t.Errorf("the closed holder after the PATCH took its location: %+v", released)
	}

	live := e.create(t, nil)
	liveRow := e.row(t, live)
	a = e.patch(t, id, patchOf(1000, map[string]any{"locationId": liveRow.Location}), "")
	if a.status != http.StatusBadRequest || !strings.Contains(a.text, "The QRCode needs to be inactive to reuse location. Current status: ACTIVE") {
		t.Fatalf("a live holder: %d %s", a.status, a.text)
	}
	if e.row(t, live) != liveRow {
		t.Error("the live holder lost its location")
	}

	other := e.create(t, map[string]any{"creditor": map[string]any{"name": "Another Co", "MCC": "5812",
		"address": map[string]any{"line1": "2 B St", "city": "Denver", "country": "US"}}})
	e.setStatus(t, other, domain.StatusCancelled)
	a = e.patch(t, id, patchOf(1000, map[string]any{"locationId": e.row(t, other).Location}), "")
	if a.status != http.StatusBadRequest || !strings.Contains(a.text, msgPatchCreditorChanged) {
		t.Fatalf("another creditor: %d %s", a.status, a.text)
	}
}

// S9.7-AC5: walking the audit log backwards from the current row rebuilds the request, and the
// location, of every earlier revision.
func testTheAuditLogRebuildsEveryEarlierRequest(t *testing.T, e *env) {
	id := e.create(t, nil)
	type version struct {
		request  map[string]any
		location string
	}
	snapshot := func() version {
		r := e.row(t, id)
		var v version
		if err := json.Unmarshal([]byte(r.RequestJSON), &v.request); err != nil {
			t.Fatal(err)
		}
		v.location = r.Location
		return v
	}
	history := make([]version, 0, 5)
	history = append(history, snapshot())
	invoice := map[string]any{"number": "INV-9", "date": "2030-01-01", "dueDate": "2030-06-30T00:00:00Z"}
	for i, p := range []map[string]any{
		patchOf(900, map[string]any{"unstructured": "first"}),
		patchOf(900, map[string]any{"bill": map[string]any{"description": "second", "paymentTiming": "deferred",
			"invoice": invoice, "amountDue": map[string]any{"amount": 900}}, "locationId": strings.Repeat("CD", 16)}),
		{"unstructured": nil, "validUntil": "2030-11-30T00:00:00Z", "paymentMethods": []any{solanaMethodUntil(800, "2030-11-30T00:00:00Z")},
			"additionalInformation": []any{
				map[string]any{"key": "Partial payment", "value": "first"}, map[string]any{"key": "Partial payment", "value": "second"}}},
		{"additionalInformation": []any{}, "paymentMethods": []any{solanaMethodUntil(700, "2030-11-30T00:00:00Z")}},
	} {
		e.clock.set(e.clock.Now().Add(time.Minute))
		if a := e.patch(t, id, p, ""); a.status != http.StatusOK {
			t.Fatalf("PATCH %d: %d %s", i+1, a.status, a.text)
		}
		history = append(history, snapshot())
	}

	current := history[len(history)-1]
	request, location := maps.Clone(current.request), current.location
	log := e.audit(t, id)
	if len(log) != len(history)-1 {
		t.Fatalf("%d audit rows for %d PATCHes", len(log), len(history)-1)
	}
	for _, entry := range log {
		for tag := range strings.SplitSeq(entry.Fields, ",") {
			previous, ok := entry.Previous[tag]
			switch {
			case !ok:
				t.Fatalf("revision %d: %s changed without its previous value", entry.Revision, tag)
			case tag == tagLocationID:
				location = previous.(string)
			case previous == nil:
				delete(request, tag)
			default:
				request[tag] = previous
			}
		}
		want := history[entry.Revision]
		if diff := cmp.Diff(want.request, request); diff != "" {
			t.Errorf("revision %d rebuilt (-want +got):\n%s", entry.Revision, diff)
		}
		if location != want.location {
			t.Errorf("revision %d: location %s, want %s", entry.Revision, location, want.location)
		}
	}
}
