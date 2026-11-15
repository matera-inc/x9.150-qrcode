// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package eventstream

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
	"github.com/google/uuid"

	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/leaktest"
	"github.com/matera-inc/x9.150-qrcode/internal/store"
)

func TestMain(m *testing.M) { leaktest.Main(m) }

// fakeLog is an in-memory outbox: rows in seq order, a commit signal, and the legacy cursors.
type fakeLog struct {
	mu      sync.Mutex
	rows    []store.EventRow
	legacy  map[string]int64
	ch      chan struct{}
	reads   int
	limits  []int
	cursors []string
}

func newFakeLog() *fakeLog {
	return &fakeLog{ch: make(chan struct{}), legacy: map[string]int64{}}
}

func (f *fakeLog) Events(_ context.Context, after int64, limit int) ([]store.EventRow, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.reads++
	f.limits = append(f.limits, limit)
	var out []store.EventRow
	for _, r := range f.rows {
		if r.Seq > after && len(out) < limit {
			out = append(out, r)
		}
	}
	return out, nil
}

func (f *fakeLog) LastSeq(context.Context) (int64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.rows) == 0 {
		return 0, nil
	}
	return f.rows[len(f.rows)-1].Seq, nil
}

func (f *fakeLog) LegacyCursor(_ context.Context, cursor string) (int64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.cursors = append(f.cursors, cursor)
	return f.legacy[cursor], nil
}

func (f *fakeLog) EventCommitted() <-chan struct{} {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.ch
}

// add appends events up to seq n; commit also wakes the waiters, as a store commit does.
func (f *fakeLog) add(n int64, commit bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for seq := int64(len(f.rows)) + 1; seq <= n; seq++ {
		f.rows = append(f.rows, row(seq))
	}
	if commit {
		close(f.ch)
		f.ch = make(chan struct{})
	}
}

func (f *fakeLog) readCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.reads
}

var (
	qrID       = uuid.MustParse("019a8f3e-1c2d-7e4f-8a9b-0c1d2e3f4a5b")
	locationID = uuid.MustParse("6f1e2d3c-4b5a-4968-8776-5a4b3c2d1e0f")
	occurredAt = time.Date(2026, 11, 15, 12, 0, 0, 123456000, time.UTC)
)

// row is a full event, every optional column set.
func row(seq int64) store.EventRow {
	return store.EventRow{
		Seq: seq, EventID: uuid.MustParse("019a8f3e-1c2d-7e4f-8a9b-0c1d2e3f4a00"), TypeCode: "SENT",
		OccurredAt: occurredAt, QRCodeID: qrID, QRCodeRevision: 2, LocationID: locationID, Amount: 1500,
		TipAmount: 500, PayerInfo: "payer-a", Currency: "USDC", Network: "solana", TransactionID: "5ig",
		InvoiceNumber: "INV-1", OrderNumber: "ORD-1", Reason: "why", SchemaVersion: "1.1",
		Deviations: "RECEIVED_IN_WRONG_STATUS,ACTION_CONTRADICTS_HASH", ReceivedInStatusCode: "ACTV",
		ReadStatusCode: "PEND", ReadAt: time.Date(9999, 12, 31, 0, 0, 0, 0, time.UTC),
	}
}

func newServer(t *testing.T, log Log) (*Stream, *httptest.Server) {
	t.Helper()
	s := New(log)
	api := httpapi.New(slog.New(slog.DiscardHandler), time.Now) //nolint:forbidigo // the error body's timestamp, unused here
	s.Register(api)
	srv := httptest.NewServer(api)
	t.Cleanup(srv.Close)
	return s, srv
}

type answer struct {
	status int
	body   map[string]any
	raw    string
}

func get(t *testing.T, srv *httptest.Server, query string) answer {
	t.Helper()
	req, err := http.NewRequestWithContext(t.Context(), http.MethodGet, srv.URL+Path+query, nil)
	if err != nil {
		t.Fatal(err)
	}
	resp, err := srv.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = resp.Body.Close() }()
	b, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	a := answer{status: resp.StatusCode, raw: string(b)}
	if err := json.Unmarshal(b, &a.body); err != nil {
		t.Fatalf("not JSON: %s", b)
	}
	return a
}

func violations(a answer) string {
	v, _ := json.Marshal(a.body["violations"])
	return string(v)
}

// S10.1-AC2: limit 1–500 (100 by default) and wait 0–30 (0 by default); outside either range, a 400
// naming the parameter, before anything is read.
func TestParameters(t *testing.T) {
	tests := []struct {
		query     string
		status    int
		violation string
		limit     int
	}{
		{"", http.StatusOK, "", DefaultLimit + 1},
		{"?limit=1", http.StatusOK, "", 2},
		{"?limit=500", http.StatusOK, "", 501},
		{"?limit=0", http.StatusBadRequest, "listPaymentEvents.limit: must be greater than or equal to 1", 0},
		{"?limit=501", http.StatusBadRequest, "listPaymentEvents.limit: must be less than or equal to 500", 0},
		{"?limit=x", http.StatusBadRequest, "limit has invalid value.", 0},
		{"?wait=0", http.StatusOK, "", DefaultLimit + 1},
		{"?wait=-1", http.StatusBadRequest, "listPaymentEvents.wait: must be greater than or equal to 0", 0},
		{"?wait=31", http.StatusBadRequest, "listPaymentEvents.wait: must be less than or equal to 30", 0},
		{"?wait=300", http.StatusBadRequest, "listPaymentEvents.wait: must be less than or equal to 30", 0},
		{"?limit=0&wait=31", http.StatusBadRequest,
			`listPaymentEvents.limit: must be greater than or equal to 1","listPaymentEvents.wait: must be less than or equal to 30`, 0},
	}
	for _, tc := range tests {
		t.Run(tc.query, func(t *testing.T) {
			log := newFakeLog()
			_, srv := newServer(t, log)
			started := time.Now() //nolint:forbidigo // measures that a refusal is not held
			a := get(t, srv, tc.query)
			if a.status != tc.status {
				t.Fatalf("status %d, want %d: %s", a.status, tc.status, a.raw)
			}
			if tc.status != http.StatusOK {
				if !strings.Contains(violations(a), tc.violation) || a.body["type"] != httpapi.TypeValidation {
					t.Fatalf("violations %s, want %q", violations(a), tc.violation)
				}
				if log.readCount() != 0 {
					t.Fatal("a refused request read the log")
				}
				if held := time.Since(started); held > time.Second { //nolint:forbidigo // measures that a refusal is not held
					t.Fatalf("refused after %v, not at once", held)
				}
				return
			}
			if got := log.limits[0]; got != tc.limit {
				t.Fatalf("read %d rows, want limit+1 = %d", got, tc.limit)
			}
		})
	}
}

// S10.1-AC1: the forms of after.
func TestAfter(t *testing.T) {
	const ulid = "01JC9Z8Y7X6W5V4T3S2R1Q0P9N"
	tests := []struct {
		after  string
		status int
		events int    // read from a log of 5 events
		legacy string // the cursor LegacyCursor was asked for
	}{
		{"", http.StatusOK, 5, ""},
		{"0", http.StatusOK, 5, ""},
		{"3", http.StatusOK, 2, ""},
		{"0003", http.StatusOK, 2, ""},
		{"5", http.StatusOK, 0, ""},
		{"6", http.StatusConflict, 0, ""},
		{"99999999999999999999", http.StatusConflict, 0, ""},
		{ulid, http.StatusOK, 3, ulid},
		{strings.ToLower(ulid), http.StatusOK, 3, ulid},
		{"7ZZZZZZZZZZZZZZZZZZZZZZZZZ", http.StatusOK, 5, "7ZZZZZZZZZZZZZZZZZZZZZZZZZ"},
		{"8ZZZZZZZZZZZZZZZZZZZZZZZZZ", http.StatusBadRequest, 0, ""}, // past a 48-bit time
		{"01JC9Z8Y7X6W5V4T3S2R1Q0P9I", http.StatusBadRequest, 0, ""}, // I is not Crockford's
		{"01JC9Z8Y7X6W5V4T3S2R1Q0P9", http.StatusBadRequest, 0, ""},  // 25 characters
		{"-1", http.StatusBadRequest, 0, ""},
		{"1.5", http.StatusBadRequest, 0, ""},
		{"abc", http.StatusBadRequest, 0, ""},
		{"%201", http.StatusBadRequest, 0, ""},
	}
	for _, tc := range tests {
		t.Run(tc.after, func(t *testing.T) {
			log := newFakeLog()
			log.add(5, false)
			log.legacy[ulid] = 2
			_, srv := newServer(t, log)
			a := get(t, srv, "?after="+tc.after)
			if a.status != tc.status {
				t.Fatalf("status %d, want %d: %s", a.status, tc.status, a.raw)
			}
			switch tc.status {
			case http.StatusOK:
				if n := len(a.body["events"].([]any)); n != tc.events {
					t.Fatalf("%d events, want %d", n, tc.events)
				}
			case http.StatusBadRequest:
				if !strings.Contains(violations(a), "after") {
					t.Fatalf("the refusal does not name after: %s", a.raw)
				}
			case http.StatusConflict:
				if a.body["title"] != "Cursor Ahead Of Log" || a.body["lastSeq"] != "5" ||
					!strings.Contains(violations(a), "after") {
					t.Fatalf("not the cursor-ahead-of-log answer with last_seq: %s", a.raw)
				}
			}
			var want []string
			if tc.legacy != "" {
				want = []string{tc.legacy}
			}
			if diff := cmp.Diff(want, log.cursors); diff != "" {
				t.Fatalf("legacy cursors looked up (-want +got):\n%s", diff)
			}
		})
	}
}

// S10.1-AC3: a page in seq order with Java's fields; nextCursor the last seq; hasMore when cut at
// limit; a read past the end is empty with no nextCursor.
func TestPage(t *testing.T) {
	log := newFakeLog()
	log.add(3, false)
	_, srv := newServer(t, log)

	a := get(t, srv, "?after=0&limit=2")
	if a.status != http.StatusOK || a.body["hasMore"] != true || a.body["nextCursor"] != "2" {
		t.Fatalf("first page: %s", a.raw)
	}
	if seqs := len(a.body["events"].([]any)); seqs != 2 {
		t.Fatalf("%d events, want 2", seqs)
	}
	a = get(t, srv, "?after=2&limit=2")
	if a.body["hasMore"] != false || a.body["nextCursor"] != "3" || len(a.body["events"].([]any)) != 1 {
		t.Fatalf("last page: %s", a.raw)
	}
	a = get(t, srv, "?after=3&limit=2")
	if a.raw != `{"events":[],"hasMore":false}` {
		t.Fatalf("a read past the end: %s", a.raw)
	}
}

// S10.1-AC3: the body of an event, member for member as Java writes it: the members openapi.yaml
// declares nullable are null when absent, the others omitted (NON_NULL), ids in ADR-0026's forms.
func TestEventBody(t *testing.T) {
	full := row(1)
	bare := row(2)
	bare.TypeCode, bare.Amount, bare.TipAmount, bare.PayerInfo, bare.Currency, bare.Network = "CLRD", -1, -1, "", "", ""
	bare.TransactionID, bare.InvoiceNumber, bare.OrderNumber, bare.Reason = "", "", "", ""
	bare.Deviations, bare.ReceivedInStatusCode = "", ""
	bare.TipAmount = -1
	zero := row(3)
	zero.TypeCode, zero.Amount, zero.TipAmount, zero.OccurredAt = "INIT", 0, 0, occurredAt.Truncate(time.Second)

	page, err := toPage([]store.EventRow{full, bare, zero}, 3)
	if err != nil {
		t.Fatal(err)
	}
	body, err := httpapi.Marshal(page)
	if err != nil {
		t.Fatal(err)
	}
	common := `"occurredAt":"2026-11-15T12:00:00.123456Z","qrCodeId":"019A8F3E1C2D7E4F8A9B0C1D2E3F4A5B",` +
		`"qrCodeRevision":2,"locationId":"6F1E2D3C4B5A496887765A4B3C2D1E0F"`
	want := `{"events":[` +
		`{"eventId":"019a8f3e-1c2d-7e4f-8a9b-0c1d2e3f4a00","type":"payment.sent",` + common +
		`,"amount":1500,"tipAmount":500,"payerInfo":"payer-a","currency":"USDC","network":"solana",` +
		`"transactionId":"5ig","invoiceNumber":"INV-1","orderNumber":"ORD-1","reason":"why",` +
		`"deviations":["RECEIVED_IN_WRONG_STATUS","ACTION_CONTRADICTS_HASH"],"receivedInStatus":"ACTIVE",` +
		`"schemaVersion":"1.1"},` +
		`{"eventId":"019a8f3e-1c2d-7e4f-8a9b-0c1d2e3f4a00","type":"payment.cleared",` + common +
		`,"tipAmount":null,"payerInfo":null,"transactionId":null,"invoiceNumber":null,"orderNumber":null,` +
		`"reason":null,"schemaVersion":"1.1"},` +
		`{"eventId":"019a8f3e-1c2d-7e4f-8a9b-0c1d2e3f4a00","type":"payment.initiated",` +
		strings.Replace(common, "12:00:00.123456Z", "12:00:00Z", 1) +
		`,"amount":0,"tipAmount":0,"payerInfo":"payer-a","currency":"USDC","network":"solana",` +
		`"transactionId":"5ig","invoiceNumber":"INV-1","orderNumber":"ORD-1","reason":"why",` +
		`"deviations":["RECEIVED_IN_WRONG_STATUS","ACTION_CONTRADICTS_HASH"],"receivedInStatus":"ACTIVE",` +
		`"schemaVersion":"1.1"}` +
		`],"nextCursor":"3","hasMore":false}`
	if diff := cmp.Diff(want, string(body)); diff != "" {
		t.Fatalf("body (-want +got):\n%s", diff)
	}

	for _, bad := range []func(r *store.EventRow){
		func(r *store.EventRow) { r.TypeCode = "XXXX" },
		func(r *store.EventRow) { r.Deviations = "SOMETHING_ELSE" },
		func(r *store.EventRow) { r.ReceivedInStatusCode = "XXXX" },
	} {
		r := row(1)
		bad(&r)
		if _, err := toPage([]store.EventRow{r}, 1); err == nil {
			t.Errorf("an unreadable row was put on the wire: %+v", r)
		}
	}
}

// longPoll starts a request with wait and returns its answer on a channel, and when it started.
func longPoll(t *testing.T, srv *httptest.Server, query string) (<-chan answer, time.Time) {
	t.Helper()
	out := make(chan answer, 1)
	started := time.Now() //nolint:forbidigo // measures how long a long poll was held
	go func() { out <- get(t, srv, query) }()
	return out, started
}

// waitParked waits until the request has read the log once, so it is parked.
func waitParked(t *testing.T, log *fakeLog, reads int) {
	t.Helper()
	for range 500 {
		if log.readCount() >= reads {
			return
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatal("the request never read the log")
}

func since(start time.Time) time.Duration {
	return time.Since(start) //nolint:forbidigo // measures how long a long poll was held
}

// S10.1-AC4: a parked request answers as soon as an event commits, not at the end of wait.
func TestLongPollWokenByACommit(t *testing.T) {
	log := newFakeLog()
	s, srv := newServer(t, log)
	s.backstop = time.Hour // only the commit can wake it
	got, started := longPoll(t, srv, "?wait=30")
	waitParked(t, log, 1)
	log.add(1, true)
	a := <-got
	if a.status != http.StatusOK || len(a.body["events"].([]any)) != 1 || a.body["nextCursor"] != "1" {
		t.Fatalf("woken page: %s", a.raw)
	}
	if held := since(started); held > 5*time.Second {
		t.Fatalf("held %v after the commit", held)
	}
}

// S10.1-AC4: the backstop reads again with no wake-up (an event another process committed).
func TestLongPollBackstop(t *testing.T) {
	log := newFakeLog()
	s, srv := newServer(t, log)
	s.backstop = 20 * time.Millisecond
	got, started := longPoll(t, srv, "?wait=30")
	waitParked(t, log, 1)
	log.add(1, false)
	a := <-got
	if a.status != http.StatusOK || len(a.body["events"].([]any)) != 1 {
		t.Fatalf("backstop page: %s", a.raw)
	}
	if held := since(started); held > 5*time.Second {
		t.Fatalf("held %v; the backstop did not read", held)
	}
}

// S10.1-AC4: with nothing to return, the request is held for wait, then answers an empty page.
func TestLongPollHeldForWait(t *testing.T) {
	log := newFakeLog()
	_, srv := newServer(t, log)
	got, started := longPoll(t, srv, "?wait=1")
	a := <-got
	held := since(started)
	if a.raw != `{"events":[],"hasMore":false}` {
		t.Fatalf("expired page: %s", a.raw)
	}
	if held < 900*time.Millisecond || held > 3*time.Second {
		t.Fatalf("held %v for wait=1", held)
	}
}

// S10.1-AC4: Stop (SIGTERM) answers a parked request with its current page at once, and a request
// made after it is not parked.
func TestStopAnswersParkedRequests(t *testing.T) {
	log := newFakeLog()
	s, srv := newServer(t, log)
	s.backstop = time.Hour
	got, started := longPoll(t, srv, "?wait=30")
	waitParked(t, log, 1)
	s.Stop()
	s.Stop() // idempotent
	if a := <-got; a.raw != `{"events":[],"hasMore":false}` {
		t.Fatalf("stopped page: %s", a.raw)
	}
	if held := since(started); held > 5*time.Second {
		t.Fatalf("held %v after Stop", held)
	}
	got, started = longPoll(t, srv, "?wait=30")
	<-got
	if held := since(started); held > 5*time.Second {
		t.Fatalf("a request after Stop was held %v", held)
	}
}

// A parked request whose client goes away stops reading.
func TestLongPollClientGone(t *testing.T) {
	log := newFakeLog()
	s, srv := newServer(t, log)
	s.backstop = 10 * time.Millisecond
	ctx, cancel := context.WithCancel(t.Context())
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL+Path+"?wait=30", nil)
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		resp, err := srv.Client().Do(req)
		if err == nil {
			_ = resp.Body.Close()
		}
	}()
	waitParked(t, log, 1)
	cancel()
	<-done
	time.Sleep(100 * time.Millisecond)
	before := log.readCount()
	time.Sleep(100 * time.Millisecond)
	if after := log.readCount(); after != before {
		t.Fatalf("the backstop kept reading for a client that left: %d reads, then %d", before, after)
	}
}
