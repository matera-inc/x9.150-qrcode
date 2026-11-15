// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package eventstream serves GET /pub/api/v1/events: the payment events of the outbox
// (docs/EVENTS-TABLE.md), paged by a cursor and long-polled, in the Java build's shape (ADR-0001,
// ADR-0026, ADR-0028, ADR-0031, ADR-0037).
//
// The cursor is payment_event.seq as a decimal string. A cursor the Java build handed out, a ULID,
// is translated through payment_event_legacy_cursor. Events come in seq order: seq is gapless and in
// commit order, so a page never skips an event that commits later with a smaller number.
//
// A request with nothing to return and wait > 0 is parked: it holds no database connection, and
// reads again when an event commits in this process (store.EventCommitted), every second as a
// backstop, and once more when wait ends or the server shuts down (ADR-0031).
package eventstream

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/matera-inc/x9.150-qrcode/internal/domain"
	"github.com/matera-inc/x9.150-qrcode/internal/httpapi"
	"github.com/matera-inc/x9.150-qrcode/internal/store"
)

// Path is the route's path, and Operation its operationId in openapi.yaml, which Java's
// validation messages name.
const (
	Path      = "/pub/api/v1/events"
	Operation = "listPaymentEvents"
)

// The bounds of openapi.yaml (S10.1-AC2).
const (
	DefaultLimit = 100
	MinLimit     = 1
	MaxLimit     = 500
	MinWait      = 0
	MaxWait      = 30
)

// Backstop is how often a parked request reads again when no wake-up came (S10.1-AC4).
const Backstop = time.Second

// Log is what the stream reads; *store.Store is one.
type Log interface {
	// Events reads up to limit events after seq, in seq order.
	Events(ctx context.Context, after int64, limit int) ([]store.EventRow, error)
	// LastSeq is the highest seq handed out.
	LastSeq(ctx context.Context) (int64, error)
	// LegacyCursor translates a Java build's ULID cursor to the seq a read resumes after.
	LegacyCursor(ctx context.Context, cursor string) (int64, error)
	// EventCommitted returns a channel closed by the next commit that inserted an event.
	EventCommitted() <-chan struct{}
}

// Stream is the event stream's handler.
type Stream struct {
	log      Log
	backstop time.Duration

	stopOnce sync.Once
	stop     chan struct{}
}

// New returns the stream over log.
func New(log Log) *Stream {
	return &Stream{log: log, backstop: Backstop, stop: make(chan struct{})}
}

// Register adds the route to api.
func (s *Stream) Register(api *httpapi.API) {
	api.Handle("GET "+Path, s.List)
}

// Stop wakes every parked request, which answers with its current page, and makes every later
// request answer at once (ADR-0031: on SIGTERM, parked long-polls return). It is safe to call more
// than once; main registers it with http.Server.RegisterOnShutdown.
func (s *Stream) Stop() {
	s.stopOnce.Do(func() { close(s.stop) })
}

// List serves GET /pub/api/v1/events?after=&limit=&wait=.
func (s *Stream) List(w http.ResponseWriter, r *http.Request) error {
	limit, wait, err := params(r)
	if err != nil {
		return err
	}
	ctx := r.Context()
	raw := r.URL.Query().Get("after")
	after, err := s.position(ctx, raw)
	if err != nil {
		return err
	}
	rows, err := s.read(ctx, raw, after, limit, wait)
	if ctx.Err() != nil {
		return nil //nolint:nilerr // the client went away while parked: nobody to answer, nothing to log
	}
	if err != nil {
		return err
	}
	page, err := toPage(rows, limit)
	if err != nil {
		return err
	}
	return httpapi.WriteJSON(w, http.StatusOK, page)
}

// params reads limit and wait as Spring converts them and Bean Validation checks them; both are
// checked before anything is read, and a request breaking both bounds is told of both (S10.1-AC2).
func params(r *http.Request) (limit int, wait time.Duration, err error) {
	l, lok, lerr := httpapi.QueryInt(r, Operation, "limit",
		httpapi.IntBounds{Min: new(int64(MinLimit)), Max: new(int64(MaxLimit))})
	w, _, werr := httpapi.QueryInt(r, Operation, "wait",
		httpapi.IntBounds{Min: new(int64(MinWait)), Max: new(int64(MaxWait))})
	if lerr != nil || werr != nil {
		return 0, 0, joinViolations(lerr, werr)
	}
	limit = DefaultLimit
	if lok {
		limit = int(l)
	}
	return limit, time.Duration(w) * time.Second, nil
}

// joinViolations is one 400 carrying the violations of every refused parameter, in order.
func joinViolations(errs ...error) error {
	var violations []string
	for _, err := range errs {
		if p, ok := errors.AsType[*httpapi.Problem](err); ok {
			violations = append(violations, p.Violations...)
		}
	}
	return httpapi.Validation(violations...)
}

// position reads after (S10.1-AC1): empty is the beginning of retained history; a decimal is a seq;
// a ULID is a Java build's cursor, translated; anything else is a 400 naming after. Whether a
// decimal is ahead of the log is checked by read, only when there is nothing after it.
func (s *Stream) position(ctx context.Context, raw string) (int64, error) {
	switch {
	case raw == "":
		return 0, nil
	case isULID(raw):
		return s.log.LegacyCursor(ctx, strings.ToUpper(raw))
	case isDecimal(raw):
		seq, err := strconv.ParseInt(raw, 10, 64)
		if err != nil {
			// More digits than any seq can have: ahead of every log.
			last, lerr := s.log.LastSeq(ctx)
			if lerr != nil {
				return 0, lerr
			}
			return 0, aheadOfLog(raw, last)
		}
		return seq, nil
	default:
		return 0, httpapi.Validation(Operation + ".after: must be a cursor this service handed out " +
			"(a decimal sequence number, or a ULID from the Java build)")
	}
}

// read returns up to limit+1 events after after: at once when there are some or wait is zero,
// otherwise when an event commits, the backstop finds one, wait ends or the stream stops.
func (s *Stream) read(ctx context.Context, raw string, after int64, limit int, wait time.Duration) ([]store.EventRow, error) {
	woken := s.log.EventCommitted() // taken before the read, so a commit after it is not missed
	rows, err := s.log.Events(ctx, after, limit+1)
	if err != nil || len(rows) > 0 {
		return rows, err
	}
	if after > 0 {
		last, err := s.log.LastSeq(ctx)
		if err != nil {
			return nil, err
		}
		if after > last {
			return nil, aheadOfLog(raw, last)
		}
	}
	if wait <= 0 {
		return rows, nil
	}

	// Parked: no connection is held between reads. The hold is real time, not business time, so a
	// moved test clock does not shorten it.
	expired := time.NewTimer(wait) //nolint:forbidigo // the long poll's real hold, not business time
	defer expired.Stop()
	tick := time.NewTicker(s.backstop)
	defer tick.Stop()
	for {
		select {
		case <-woken:
		case <-tick.C:
		case <-expired.C:
			return s.log.Events(ctx, after, limit+1) // one last read, then the page, empty or not
		case <-s.stop:
			return s.log.Events(ctx, after, limit+1) // the current page (ADR-0031)
		case <-ctx.Done():
			return nil, ctx.Err()
		}
		woken = s.log.EventCommitted()
		rows, err := s.log.Events(ctx, after, limit+1)
		if err != nil || len(rows) > 0 {
			return rows, err
		}
	}
}

// aheadOfLog is the Go build's 409 for a cursor after the last event, as after a point-in-time
// restore of the database (ADR-0031): the consumer re-synchronises instead of waiting forever. The
// Java build has no such answer; it is a recorded difference.
func aheadOfLog(raw string, last int64) *httpapi.Problem {
	return &httpapi.Problem{
		Type:   httpapi.TypeStatusConflict,
		Title:  "Cursor Ahead Of Log",
		Status: http.StatusConflict,
		Detail: "The cursor is after the last event of the log, as after a restore of the database. " +
			"Re-synchronise from the QR Codes, then read from lastSeq.",
		Violations: []string{fmt.Sprintf("after: %s is after the last event, %d", raw, last)},
		Extra:      map[string]any{"lastSeq": strconv.FormatInt(last, 10)},
	}
}

// isDecimal reports whether s is ASCII digits only.
func isDecimal(s string) bool {
	if s == "" {
		return false
	}
	for i := range len(s) {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return true
}

// isULID reports whether s is a ULID as the Java build wrote its cursors: 26 characters of
// Crockford's base32, the first at most 7 (a 48-bit time). Crockford's alphabet is read in either
// case, as ULIDs are.
func isULID(s string) bool {
	if len(s) != 26 || s[0] < '0' || s[0] > '7' {
		return false
	}
	for i := range len(s) {
		c := s[i]
		if 'a' <= c && c <= 'z' {
			c -= 'a' - 'A'
		}
		if !strings.ContainsRune(crockford, rune(c)) {
			return false
		}
	}
	return true
}

const crockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// ------------------------------------------------------------------ the answer

// Page is PaymentEventPage of openapi.yaml, members in its order.
type Page struct {
	Events []Event `json:"events"`
	// NextCursor is the last event's seq, absent when the page is empty.
	NextCursor *string `json:"nextCursor,omitzero"`
	HasMore    bool    `json:"hasMore"`
}

// Event is PaymentEvent of openapi.yaml, as the Java build writes it: the members it declares
// nullable are written as null when absent; the others are omitted when absent (NON_NULL).
type Event struct {
	EventID          string   `json:"eventId"`
	Type             string   `json:"type"`
	OccurredAt       string   `json:"occurredAt"`
	QRCodeID         string   `json:"qrCodeId"`
	QRCodeRevision   int      `json:"qrCodeRevision"`
	LocationID       string   `json:"locationId"`
	Amount           *int64   `json:"amount,omitzero"`
	TipAmount        *int64   `json:"tipAmount"`
	PayerInfo        *string  `json:"payerInfo"`
	Currency         *string  `json:"currency,omitzero"`
	Network          *string  `json:"network,omitzero"`
	TransactionID    *string  `json:"transactionId"`
	InvoiceNumber    *string  `json:"invoiceNumber"`
	OrderNumber      *string  `json:"orderNumber"`
	Reason           *string  `json:"reason"`
	Deviations       []string `json:"deviations,omitzero"`
	ReceivedInStatus *string  `json:"receivedInStatus,omitzero"`
	SchemaVersion    string   `json:"schemaVersion"`
}

// absentAmount is the sentinel of an amount column whose value is absent (docs/EVENTS-TABLE.md).
const absentAmount = -1

// toPage cuts rows (up to limit+1) to a page of limit (S10.1-AC3).
func toPage(rows []store.EventRow, limit int) (Page, error) {
	page := Page{Events: make([]Event, 0, min(len(rows), limit)), HasMore: len(rows) > limit}
	var last int64
	for _, row := range rows {
		if len(page.Events) == limit {
			break
		}
		e, err := toEvent(row)
		if err != nil {
			return Page{}, err
		}
		page.Events = append(page.Events, e)
		last = row.Seq
	}
	if len(page.Events) > 0 {
		page.NextCursor = new(strconv.FormatInt(last, 10))
	}
	return page, nil
}

// toEvent puts a row on the wire: sentinels become absent, codes become names, ids take the forms
// of ADR-0026 (the QR Code and location ids in 32 upper-case hexadecimal characters, the event id
// dashed), and times are UTC in RFC 3339.
func toEvent(row store.EventRow) (Event, error) {
	typ, err := domain.EventTypeFromCode(row.TypeCode)
	if err != nil {
		return Event{}, fmt.Errorf("event %d: %w", row.Seq, err)
	}
	deviations, err := domain.ParseDeviations(row.Deviations)
	if err != nil {
		return Event{}, fmt.Errorf("event %d: %w", row.Seq, err)
	}
	e := Event{
		EventID:        row.EventID.String(),
		Type:           string(typ),
		OccurredAt:     row.OccurredAt.UTC().Format(time.RFC3339Nano),
		QRCodeID:       httpapi.FormatID(row.QRCodeID),
		QRCodeRevision: row.QRCodeRevision,
		LocationID:     httpapi.FormatID(row.LocationID),
		Amount:         amount(row.Amount),
		TipAmount:      amount(row.TipAmount),
		PayerInfo:      text(row.PayerInfo),
		Currency:       text(row.Currency),
		Network:        text(row.Network),
		TransactionID:  text(row.TransactionID),
		InvoiceNumber:  text(row.InvoiceNumber),
		OrderNumber:    text(row.OrderNumber),
		Reason:         text(row.Reason),
		SchemaVersion:  row.SchemaVersion,
	}
	for _, d := range deviations {
		e.Deviations = append(e.Deviations, string(d))
	}
	if row.ReceivedInStatusCode != "" {
		status, err := domain.StatusFromCode(row.ReceivedInStatusCode)
		if err != nil {
			return Event{}, fmt.Errorf("event %d: %w", row.Seq, err)
		}
		e.ReceivedInStatus = new(string(status))
	}
	return e, nil
}

func amount(v int64) *int64 {
	if v == absentAmount {
		return nil
	}
	return &v
}

func text(v string) *string {
	if v == "" {
		return nil
	}
	return &v
}
