// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package store

import (
	"context"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/matera-inc/x9.150-qrcode/internal/database"
	"github.com/matera-inc/x9.150-qrcode/internal/dbtest"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
)

// The tests against a real PostgreSQL with the changelog applied, as the service runs it: one
// container, shared by the subtests, which run in order.
func TestPostgres(t *testing.T) {
	db := dbtest.Start(t)
	e := newEnv(t, db)

	for _, tc := range []struct {
		name string
		test func(t *testing.T, e *env)
	}{
		{"CreateAndRead", testCreateAndRead},
		{"HashRegisteredIsItsOwnColumn", testHashRegisteredIsItsOwnColumn},
		{"EventBodyKeepsTheHash", testEventBodyKeepsTheHash},
		{"StatementOrder", testStatementOrder},
		{"StatementOrderOfANotification", testStatementOrderOfANotification},
		{"EventRow", testEventRow},
		{"Pools", testPools},
		{"StatementTimeout", testStatementTimeout},
		{"StaleRowReruns", testStaleRowReruns},
		{"ExhaustedRerunsAreBusy", testExhaustedRerunsAreBusy},
		{"LockTimeoutRerunsThenBusy", testLockTimeoutRerunsThenBusy},
		{"DuplicateLocationIsNotRerun", testDuplicateLocationIsNotRerun},
		{"RefusalCommitsWhatItDecided", testRefusalCommitsWhatItDecided},
		{"ConcurrentPayersOnOneRow", testConcurrentPayersOnOneRow},
		{"ConcurrentRetriesOfOnePayer", testConcurrentRetriesOfOnePayer},
		{"ConcurrentPatchesLoseNoUpdate", testConcurrentPatchesLoseNoUpdate},
		{"GaplessSeqInCommitOrder", testGaplessSeqInCommitOrder},
		{"RollbackLeavesNoEventAndNoGap", testRollbackLeavesNoEventAndNoGap},
		{"EventColumnsAreTheDocument", testEventColumnsAreTheDocument},
		{"PlainSQLReader", testPlainSQLReader},
		{"LegacyCursor", testLegacyCursor},
		{"NoStatementIsSchemaQualified", testNoStatementIsSchemaQualified},
	} {
		e.reset()
		t.Run(tc.name, func(t *testing.T) { tc.test(t, e) })
	}
}

// ------------------------------------------------------------------ the environment

var settings = domain.Settings{ReservationTTL: 5 * time.Minute}

const requestJSON = `{"bill":{"invoice":{"number":"INV-1"},"order":{"number":"ORD-1"}},` +
	`"paymentMethods":[{"currency":"USDC","amount":1000,"networks":{"solana":{"recipient":"r"}}}]}`

type testClock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *testClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *testClock) set(t time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = t
}

// stmt is one statement a pool sent: its SQL, its first argument (the row's id, for UPDATE
// qr_code) and whether it was part of a batch.
type stmt struct {
	sql   string
	first any
	batch bool
}

// recorder traces every statement a pool sends, in order.
type recorder struct {
	mu    sync.Mutex
	stmts []stmt
	all   []stmt
}

func (r *recorder) add(s stmt) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.stmts = append(r.stmts, s)
	r.all = append(r.all, s)
}

func (r *recorder) take() []stmt {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := r.stmts
	r.stmts = nil
	return out
}

func first(args []any) any {
	if len(args) == 0 {
		return nil
	}
	return args[0]
}

func (r *recorder) TraceQueryStart(ctx context.Context, _ *pgx.Conn, d pgx.TraceQueryStartData) context.Context {
	r.add(stmt{sql: d.SQL, first: first(d.Args)})
	return ctx
}
func (r *recorder) TraceQueryEnd(context.Context, *pgx.Conn, pgx.TraceQueryEndData) {}
func (r *recorder) TraceBatchStart(ctx context.Context, _ *pgx.Conn, _ pgx.TraceBatchStartData) context.Context {
	return ctx
}
func (r *recorder) TraceBatchQuery(_ context.Context, _ *pgx.Conn, d pgx.TraceBatchQueryData) {
	r.add(stmt{sql: d.SQL, first: first(d.Args), batch: true})
}
func (r *recorder) TraceBatchEnd(context.Context, *pgx.Conn, pgx.TraceBatchEndData) {}

type env struct {
	db            *dbtest.DB
	writes, reads *recorder
	write, read   *pgxpool.Pool
	other         *pgxpool.Pool // another client of the database, untraced
	clock         *testClock
	store         *Store
	evidence      *Evidence
	baseTime      time.Time
}

func newEnv(t *testing.T, db *dbtest.DB) *env {
	t.Helper()
	cfg := db.Config(dbtest.AppRole)
	pool := func(size int, tracer pgx.QueryTracer) *pgxpool.Pool {
		pc, err := database.PoolConfig(cfg, size)
		if err != nil {
			t.Fatal(err)
		}
		if tracer != nil {
			pc.ConnConfig.Tracer = tracer
		}
		p, err := pgxpool.NewWithConfig(t.Context(), pc)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(p.Close)
		return p
	}
	e := &env{db: db, writes: &recorder{}, reads: &recorder{}, clock: &testClock{}}
	e.write = pool(cfg.PoolWrites, e.writes)
	e.read = pool(cfg.PoolReads, e.reads)
	e.other = pool(4, nil)
	e.baseTime = time.Date(2026, 11, 15, 12, 0, 0, 123456000, time.UTC)
	e.evidence = &Evidence{JSON: json.RawMessage(`{"payment":{"network":"solana"}}`), JWSCompact: "e30.e30.c2ln"}
	return e
}

// reset gives each subtest a fresh store (no hooks), the base time and empty recorders.
func (e *env) reset() {
	e.clock.set(e.baseTime)
	e.store = New(e.write, e.read, e.clock)
	e.writes.take()
	e.reads.take()
}

func (e *env) create(t *testing.T) QRCode {
	t.Helper()
	id, err := NewQRCodeID()
	if err != nil {
		t.Fatal(err)
	}
	return e.createAt(t, id, id)
}

func (e *env) createAt(t *testing.T, id, location uuid.UUID) QRCode {
	t.Helper()
	res, err := e.store.Run(t.Context(), func(_ context.Context, _ *Reader, now time.Time) (Change, error) {
		d := domain.Create(domain.NewQRCode{
			ID: id, LocationID: location, ValidUntil: now.Add(30 * 24 * time.Hour), RequestJSON: json.RawMessage(requestJSON),
		}, now)
		return Change{Decision: d, EMVContent: emvFor(location)}, nil
	})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	return res.QRCode
}

func emvFor(location uuid.UUID) string { return "EMV-" + location.String() }

func (e *env) setStatus(ctx context.Context, id uuid.UUID, s domain.Status) (Result, error) {
	return e.store.Transition(ctx, id, nil, func(row domain.Row, last *domain.NotificationRecord, now time.Time) (domain.Decision, error) {
		return domain.UpdateStatus(row, domain.StatusUpdate{Status: s}, last, now, settings)
	})
}

func payer(info string, txID string) domain.Notification {
	return domain.Notification{
		Network: "solana", Currency: "USDC", Amount: 1000, TransactionID: txID,
		PayerInfo: &info, SignerSubject: "CN=" + info,
	}
}

func (e *env) notify(ctx context.Context, id uuid.UUID, n domain.Notification, gate *domain.Refusal, s domain.Settings) (Result, error) {
	return e.store.Transition(ctx, id, e.evidence, func(row domain.Row, last *domain.NotificationRecord, now time.Time) (domain.Decision, error) {
		return domain.Notify(row, n, gate, last, now, s)
	})
}

func (e *env) count(t *testing.T, sql string, args ...any) int {
	t.Helper()
	var n int
	if err := e.other.QueryRow(t.Context(), sql, args...).Scan(&n); err != nil {
		t.Fatalf("%s: %v", sql, err)
	}
	return n
}

func (e *env) lastSeq(t *testing.T) int64 {
	t.Helper()
	seq, err := e.store.LastSeq(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	return seq
}

// writes keeps the statements that change something, and the lock timeout, dropping reads and
// the transaction's own BEGIN and COMMIT.
func writes(stmts []stmt) []stmt {
	var out []stmt
	for _, s := range stmts {
		head := strings.ToUpper(strings.Fields(s.sql)[0])
		if head == "INSERT" || head == "UPDATE" || head == "SET" {
			out = append(out, s)
		}
	}
	return out
}

func describe(stmts []stmt) []string {
	out := make([]string, len(stmts))
	for i, s := range stmts {
		words := strings.Fields(s.sql)
		n := min(len(words), 3)
		out[i] = strings.Join(words[:n], " ")
		if s.batch {
			out[i] += " (batch)"
		}
	}
	return out
}

// ------------------------------------------------------------------ the tests

// S9.5-AC4: new rows carry a UUIDv7 QR Code id, the location defaults to it, and every id column
// is a UUID; what is read back is what was written.
func testCreateAndRead(t *testing.T, e *env) {
	ctx := t.Context()
	q := e.create(t)
	if q.ID.Version() != 7 || q.LocationID != q.ID {
		t.Fatalf("id %s (v%d), location %s", q.ID, q.ID.Version(), q.LocationID)
	}
	got, err := e.store.Get(ctx, q.ID)
	if err != nil {
		t.Fatal(err)
	}
	if diff := cmp.Diff(q, got); diff != "" {
		t.Fatalf("read back differs (-written +read):\n%s", diff)
	}
	byLoc, err := e.store.GetByLocation(ctx, q.LocationID)
	if err != nil || byLoc.ID != q.ID {
		t.Fatalf("by location: %v, %v", byLoc.ID, err)
	}
	if _, err := e.store.Get(ctx, uuid.New()); !errors.Is(err, ErrNotFound) {
		t.Fatalf("a missing QR Code: %v", err)
	}
	types := e.db.Psql(ctx, t, `SELECT string_agg(table_name || '.' || column_name || '=' || data_type, ' ' ORDER BY table_name, column_name)
		FROM information_schema.columns WHERE table_schema = 'x9_qrcode'
		AND table_name IN ('qr_code', 'qr_code_audit_log', 'qr_code_notification', 'payment_event')
		AND column_name IN ('id', 'location_id', 'event_id', 'qr_code_id')`)
	if n := len(strings.Fields(types)); n != 8 {
		t.Errorf("found %d id columns, want 8: %s", n, types)
	}
	for f := range strings.FieldsSeq(types) {
		if !strings.HasSuffix(f, "=uuid") {
			t.Errorf("%s is not a UUID column", f)
		}
	}
}

// The lead's decision: hash_registered is stored, so a reservation stopped by a hash and one taken
// with ttl-seconds = 0, which share the "no lapse" stamp, still read back as different.
func testHashRegisteredIsItsOwnColumn(t *testing.T, e *env) {
	ctx := t.Context()
	noWindow, hashed := e.create(t), e.create(t)
	if _, err := e.notify(ctx, noWindow.ID, payer("payer-a", ""), nil, domain.Settings{}); err != nil {
		t.Fatal(err)
	}
	if _, err := e.notify(ctx, hashed.ID, payer("payer-a", "5ig"), nil, settings); err != nil {
		t.Fatal(err)
	}
	a, err := e.store.Get(ctx, noWindow.ID)
	if err != nil {
		t.Fatal(err)
	}
	b, err := e.store.Get(ctx, hashed.ID)
	if err != nil {
		t.Fatal(err)
	}
	if !a.ReservedUntil.Equal(domain.NoLapse) || !b.ReservedUntil.Equal(domain.NoLapse) {
		t.Fatalf("reserved_until: %v and %v, want the no-lapse sentinel on both", a.ReservedUntil, b.ReservedUntil)
	}
	if a.HashRegistered || !b.HashRegistered {
		t.Fatalf("hash_registered: no window %v, hashed %v", a.HashRegistered, b.HashRegistered)
	}
	// The holder's retry is a 200 without a window and a 409 once a hash is registered (ADR-0036).
	r, err := e.notify(ctx, noWindow.ID, payer("payer-a", ""), nil, domain.Settings{})
	if err != nil || r.Decision.Refusal != nil {
		t.Fatalf("retry without a window: %v, %v", r.Decision.Refusal, err)
	}
	r, err = e.notify(ctx, hashed.ID, payer("payer-a", ""), nil, settings)
	if err != nil || r.Decision.Refusal == nil || r.Decision.Refusal.Kind != domain.KindConflict {
		t.Fatalf("retry after a hash: %v, %v", r.Decision.Refusal, err)
	}
	// Leaving PAYMENT_INITIATED clears it.
	if _, err := e.setStatus(ctx, hashed.ID, domain.StatusActive); err != nil {
		t.Fatal(err)
	}
	if b, err = e.store.Get(ctx, hashed.ID); err != nil || b.HashRegistered {
		t.Fatalf("after ACTIVE: hash_registered %v, %v", b.HashRegistered, err)
	}
}

// ADR-0035 (owner, 2026-11-15): a NOT_SENT once a hash is registered is stored, flagged
// ACTION_CONTRADICTS_HASH, and carries no transactionId; the next event's body still comes from the
// latest stored notification that carries one, so payment.cancelled reports the hash.
func testEventBodyKeepsTheHash(t *testing.T, e *env) {
	ctx := t.Context()
	q := e.create(t)
	if _, err := e.notify(ctx, q.ID, payer("payer-a", ""), nil, settings); err != nil {
		t.Fatal(err)
	}
	if _, err := e.notify(ctx, q.ID, payer("payer-a", "5ig"), nil, settings); err != nil {
		t.Fatal(err)
	}
	notSent := payer("payer-a", "")
	notSent.Action = domain.ActionNotSent
	r, err := e.notify(ctx, q.ID, notSent, nil, settings)
	if err != nil || r.Decision.Refusal != nil || len(r.Events) != 0 {
		t.Fatalf("NOT_SENT after the hash: %v, %v, %d events", r.Decision.Refusal, err, len(r.Events))
	}
	if n := e.count(t, "SELECT COUNT(*) FROM qr_code_notification WHERE qr_code_id = $1", q.ID); n != 3 {
		t.Fatalf("%d notifications stored, want 3 (the NOT_SENT included)", n)
	}
	r, err = e.setStatus(ctx, q.ID, domain.StatusCancelled)
	if err != nil || len(r.Events) != 1 {
		t.Fatalf("CANCELLED: %v, %v, %d events", r.Decision.Refusal, err, len(r.Events))
	}
	if ev := r.Events[0]; ev.Type != domain.EventCancelled || ev.TransactionID == nil || *ev.TransactionID != "5ig" {
		t.Fatalf("payment.cancelled: %+v, want the hash 5ig in its body", ev.Event)
	}
}

// S9.5-AC2: a location holder's release, the row's update, the audit or notification row, and
// last the event, as one batch of the sequence UPDATE and the INSERT … SELECT. The change is
// built by hand so one transaction carries every kind of statement.
func testStatementOrder(t *testing.T, e *env) {
	ctx := t.Context()
	holder, row := e.create(t), e.create(t)
	if _, err := e.setStatus(ctx, holder.ID, domain.StatusCancelled); err != nil {
		t.Fatal(err)
	}
	fresh, err := NewLocationID()
	if err != nil {
		t.Fatal(err)
	}
	e.writes.take()

	res, err := e.store.Run(ctx, func(ctx context.Context, r *Reader, now time.Time) (Change, error) {
		h, err := r.QRCode(ctx, holder.ID)
		if err != nil {
			return Change{}, err
		}
		m, err := r.QRCode(ctx, row.ID)
		if err != nil {
			return Change{}, err
		}
		d := domain.Patch(m.Row, nil, now)
		d.Row.LocationID = h.LocationID // takes the holder's location, released first
		ev, err := domain.BuildEvent(domain.EventCancelled, d.Row, nil, now, domain.Cause{})
		if err != nil {
			return Change{}, err
		}
		d.Events = append(d.Events, ev)
		d.Notification = &domain.NotificationRecord{
			Notification: payer("payer-a", ""), Phase: domain.PhasePreCommit, ReceivedAt: now,
			Receipt: domain.Receipt{ReceivedIn: domain.StatusActive},
		}
		return Change{
			Release:    &Release{Holder: h, Decision: domain.ReleaseLocation(h.Row, fresh, now), EMVContent: emvFor(fresh)},
			Before:     &m,
			Decision:   d,
			EMVContent: emvFor(h.LocationID),
			Audit:      &Audit{ChangedFields: []string{"locationId"}, PreviousValues: json.RawMessage(`{"locationId":null}`)},
			Evidence:   e.evidence,
		}, nil
	})
	if err != nil {
		t.Fatal(err)
	}

	got := writes(e.writes.take())
	want := []string{
		"SET LOCAL lock_timeout",
		"UPDATE qr_code SET",
		"UPDATE qr_code SET",
		"INSERT INTO qr_code_audit_log",
		"INSERT INTO qr_code_notification",
		"UPDATE event_sequence SET (batch)",
		"INSERT INTO payment_event (batch)",
	}
	if diff := cmp.Diff(want, describe(got)); diff != "" || len(got) != len(want) {
		t.Fatalf("statement order (-want +got):\n%s", diff)
	}
	if got[1].first != holder.ID || got[2].first != row.ID {
		t.Fatalf("the holder is not released before the row takes its location: %v, %v", got[1].first, got[2].first)
	}
	if !strings.Contains(got[0].sql, "'2s'") {
		t.Fatalf("lock timeout: %s", got[0].sql)
	}
	for _, s := range got {
		if strings.Contains(strings.ToUpper(s.sql), "RETURNING") {
			t.Fatalf("RETURNING in %s", s.sql)
		}
	}

	h, err := e.store.Get(ctx, holder.ID)
	if err != nil {
		t.Fatal(err)
	}
	if h.LocationID != fresh || h.EMVContent != emvFor(fresh) || h.Revision != 0 || h.Status != domain.StatusCancelled {
		t.Fatalf("holder after release: location %s, emv %q, revision %d, %s", h.LocationID, h.EMVContent, h.Revision, h.Status)
	}
	m, err := e.store.Get(ctx, row.ID)
	if err != nil {
		t.Fatal(err)
	}
	if diff := cmp.Diff(res.QRCode, m); diff != "" {
		t.Fatalf("Result.QRCode is not what was committed (-result +read):\n%s", diff)
	}
	if m.LocationID != holder.LocationID || m.EMVContent != emvFor(holder.LocationID) || m.Revision != 1 || m.LockVersion != 1 {
		t.Fatalf("row after taking the location: %s, %q, revision %d, lock %d", m.LocationID, m.EMVContent, m.Revision, m.LockVersion)
	}
	if n := e.count(t, "SELECT COUNT(*) FROM qr_code_audit_log WHERE qr_code_id = $1 AND revision = 0", row.ID); n != 1 {
		t.Fatalf("audit rows for revision 0: %d", n)
	}
}

// S9.5-AC2: the realistic case, a pre-commit: the row, the notification, then the event batch.
func testStatementOrderOfANotification(t *testing.T, e *env) {
	q := e.create(t)
	e.writes.take()
	if _, err := e.notify(t.Context(), q.ID, payer("payer-a", ""), nil, settings); err != nil {
		t.Fatal(err)
	}
	want := []string{
		"SET LOCAL lock_timeout",
		"UPDATE qr_code SET",
		"INSERT INTO qr_code_notification",
		"UPDATE event_sequence SET (batch)",
		"INSERT INTO payment_event (batch)",
	}
	if diff := cmp.Diff(want, describe(writes(e.writes.take()))); diff != "" {
		t.Fatalf("statement order (-want +got):\n%s", diff)
	}

	// A transition without an event sets no lock timeout and touches no sequence.
	if _, err := e.setStatus(t.Context(), q.ID, domain.StatusActive); err != nil {
		t.Fatal(err)
	}
	if diff := cmp.Diff([]string{"UPDATE qr_code SET"}, describe(writes(e.writes.take()))); diff != "" {
		t.Fatalf("reactivation (-want +got):\n%s", diff)
	}
}

// S9.5-AC3: the event row: a UUIDv7 event id, PEND and the no-instant sentinel, and the body
// BuildEvent made, column for column.
func testEventRow(t *testing.T, e *env) {
	ctx := t.Context()
	q := e.create(t)
	start := e.lastSeq(t)
	res, err := e.notify(ctx, q.ID, payer("payer-a", "5ig"), nil, settings)
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Events) != 2 {
		t.Fatalf("a post-commit on ACTIVE on a chain emits payment.initiated and payment.sent; got %d", len(res.Events))
	}
	rows, err := e.store.Events(ctx, start, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 {
		t.Fatalf("read %d events", len(rows))
	}
	now := e.clock.Now()
	for i, row := range rows {
		ev := res.Events[i]
		if row.EventID != ev.ID || row.EventID.Version() != 7 {
			t.Errorf("event %d: id %s (v%d), want %s", i, row.EventID, row.EventID.Version(), ev.ID)
		}
		want := EventRow{
			Seq: start + int64(i) + 1, EventID: ev.ID, TypeCode: ev.Type.Code(), OccurredAt: now, QRCodeID: q.ID,
			QRCodeRevision: 0, LocationID: q.LocationID, Amount: 1000, TipAmount: -1, PayerInfo: "payer-a",
			Currency: "USDC", Network: "solana", TransactionID: "5ig", InvoiceNumber: "INV-1", OrderNumber: "ORD-1",
			SchemaVersion: domain.SchemaVersion, Deviations: "RECEIVED_IN_WRONG_STATUS", ReceivedInStatusCode: "ACTV",
			ReadStatusCode: "PEND", ReadAt: domain.NoLapse,
		}
		if diff := cmp.Diff(want, row); diff != "" {
			t.Errorf("event %d (-want +got):\n%s", i, diff)
		}
	}
	if rows[0].TypeCode != "INIT" || rows[1].TypeCode != "SENT" {
		t.Fatalf("types %s, %s", rows[0].TypeCode, rows[1].TypeCode)
	}
}

// S9.5-AC5: writes on the write pool; GET, the payload and the events on the read pool.
func testPools(t *testing.T, e *env) {
	ctx := t.Context()
	q := e.create(t)
	if _, err := e.setStatus(ctx, q.ID, domain.StatusCancelled); err != nil {
		t.Fatal(err)
	}
	if len(e.reads.take()) != 0 {
		t.Fatal("a transition used the read pool")
	}
	if len(writes(e.writes.take())) == 0 {
		t.Fatal("a transition wrote nothing on the write pool")
	}
	if _, err := e.store.Get(ctx, q.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := e.store.GetByLocation(ctx, q.LocationID); err != nil {
		t.Fatal(err)
	}
	if _, err := e.store.Events(ctx, 0, 1); err != nil {
		t.Fatal(err)
	}
	_ = e.lastSeq(t)
	if n := len(e.writes.take()); n != 0 {
		t.Fatalf("reads sent %d statements on the write pool", n)
	}
	reads := e.reads.take()
	var selects, timeouts int
	for _, s := range reads {
		head := strings.ToLower(strings.Fields(s.sql)[0])
		switch head {
		case "select":
			if s.sql == sqlStatementTimeout {
				timeouts++
				continue
			}
			selects++
		case "begin", "commit":
			if head == "begin" && !strings.Contains(strings.ToLower(s.sql), "read committed") {
				t.Errorf("a read transaction does not set READ COMMITTED: %s", s.sql)
			}
		default:
			t.Errorf("the read pool sent %s", s.sql)
		}
	}
	if selects != 4 {
		t.Fatalf("%d selects on the read pool, want 4", selects)
	}
	if timeouts != 4 {
		t.Fatalf("%d of 4 read transactions set the statement timeout", timeouts)
	}
}

// Every transaction, on either pool, runs under StatementTimeout, set first, for itself only: a
// stuck query is cancelled (57014) and answered 503, never left to hang a request.
func testStatementTimeout(t *testing.T, e *env) {
	ctx := t.Context()
	var inTransition, inRead string
	_, err := e.store.Run(ctx, func(ctx context.Context, r *Reader, _ time.Time) (Change, error) {
		return Change{}, r.tx.QueryRow(ctx, "SELECT current_setting('statement_timeout')").Scan(&inTransition)
	})
	if err != nil {
		t.Fatal(err)
	}
	if first := e.writes.take(); len(first) < 2 || first[1].sql != sqlStatementTimeout {
		t.Fatalf("the statement timeout is not the transition's first statement: %v", describe(first))
	}
	err = e.store.readOnly(ctx, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, "SELECT current_setting('statement_timeout')").Scan(&inRead)
	})
	if err != nil {
		t.Fatal(err)
	}
	if inTransition != StatementTimeout || inRead != StatementTimeout {
		t.Fatalf("statement_timeout: transition %q, read %q, want %q", inTransition, inRead, StatementTimeout)
	}
	var outside string
	if err := e.write.QueryRow(ctx, "SELECT current_setting('statement_timeout')").Scan(&outside); err != nil {
		t.Fatal(err)
	}
	if outside == StatementTimeout {
		t.Fatalf("statement_timeout leaked out of the transaction: %q", outside)
	}

	// A statement past the timeout is ErrUnavailable, and is not re-run.
	attempts := 0
	_, err = e.store.Run(ctx, func(ctx context.Context, r *Reader, _ time.Time) (Change, error) {
		attempts++
		if _, err := r.tx.Exec(ctx, "SELECT set_config('statement_timeout', '50ms', true)"); err != nil {
			return Change{}, err
		}
		_, err := r.tx.Exec(ctx, "SELECT pg_sleep(1)")
		return Change{}, err
	})
	if !errors.Is(err, ErrUnavailable) || attempts != 1 {
		t.Fatalf("a cancelled statement: err %v after %d attempts, want ErrUnavailable after 1", err, attempts)
	}
}

// S9.5-AC1: a conditional UPDATE that matches no row re-runs the whole script from its read.
func testStaleRowReruns(t *testing.T, e *env) {
	ctx := t.Context()
	q := e.create(t)
	start := e.lastSeq(t)
	e.store.beforeWrite = func(ctx context.Context, attempt int, _ Change) error {
		if attempt == 1 {
			_, err := e.other.Exec(ctx, "UPDATE qr_code SET lock_version = lock_version + 1 WHERE id = $1", q.ID)
			return err
		}
		return nil
	}
	res, err := e.setStatus(ctx, q.ID, domain.StatusCancelled)
	if err != nil {
		t.Fatal(err)
	}
	if res.Attempts != 2 || res.Decision.Refusal != nil {
		t.Fatalf("attempts %d, refusal %v", res.Attempts, res.Decision.Refusal)
	}
	if got := e.lastSeq(t) - start; got != 1 {
		t.Fatalf("%d events for one cancellation", got)
	}
	got, err := e.store.Get(ctx, q.ID)
	if err != nil || got.Status != domain.StatusCancelled || got.LockVersion != 2 {
		t.Fatalf("after the re-run: %s, lock %d, %v", got.Status, got.LockVersion, err)
	}
}

// S9.5-AC1: three attempts in all, then a 409 "busy" with the current status, and nothing written.
func testExhaustedRerunsAreBusy(t *testing.T, e *env) {
	ctx := t.Context()
	q := e.create(t)
	start := e.lastSeq(t)
	attempts := 0
	e.store.beforeWrite = func(ctx context.Context, _ int, _ Change) error {
		attempts++
		_, err := e.other.Exec(ctx, "UPDATE qr_code SET lock_version = lock_version + 1 WHERE id = $1", q.ID)
		return err
	}
	_, err := e.setStatus(ctx, q.ID, domain.StatusCancelled)
	var r *domain.Refusal
	if !errors.Is(err, ErrBusy) || !errors.As(err, &r) || r.CurrentStatus != domain.StatusActive {
		t.Fatalf("got %v (%+v), want busy with ACTIVE", err, r)
	}
	if attempts != Attempts {
		t.Fatalf("%d attempts, want %d", attempts, Attempts)
	}
	if e.lastSeq(t) != start {
		t.Fatal("an exhausted transition left an event")
	}
	if got, err := e.store.Get(ctx, q.ID); err != nil || got.Status != domain.StatusActive {
		t.Fatalf("an exhausted transition wrote: %s, %v", got.Status, err)
	}
}

// S9.5-AC1: a lock timeout (55P03) on the sequence row re-runs, and after three a "busy".
func testLockTimeoutRerunsThenBusy(t *testing.T, e *env) {
	ctx := t.Context()
	q := e.create(t)
	e.store.lockTimeout = "200ms"
	attempts := 0
	e.store.beforeWrite = func(context.Context, int, Change) error { attempts++; return nil }

	blocker, err := e.other.Begin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := blocker.Exec(ctx, "UPDATE event_sequence SET last_seq = last_seq WHERE id = 1"); err != nil {
		t.Fatal(err)
	}
	_, err = e.setStatus(ctx, q.ID, domain.StatusCancelled)
	if rbErr := blocker.Rollback(ctx); rbErr != nil {
		t.Fatal(rbErr)
	}
	if !errors.Is(err, ErrBusy) || attempts != Attempts {
		t.Fatalf("got %v after %d attempts, want busy after %d", err, attempts, Attempts)
	}
	if got, err := e.store.Get(ctx, q.ID); err != nil || got.Status != domain.StatusActive || got.LockVersion != 0 {
		t.Fatalf("a timed-out transition wrote: %s, lock %d, %v", got.Status, got.LockVersion, err)
	}
	if res, err := e.setStatus(ctx, q.ID, domain.StatusCancelled); err != nil || len(res.Events) != 1 {
		t.Fatalf("once the lock is free: %v, %d events", err, len(res.Events))
	}
}

// S9.5-AC1: a 23505 is answered, never re-run.
func testDuplicateLocationIsNotRerun(t *testing.T, e *env) {
	taken := e.create(t)
	attempts := 0
	e.store.beforeWrite = func(context.Context, int, Change) error { attempts++; return nil }
	id, err := NewQRCodeID()
	if err != nil {
		t.Fatal(err)
	}
	_, err = e.store.Run(t.Context(), func(_ context.Context, _ *Reader, now time.Time) (Change, error) {
		d := domain.Create(domain.NewQRCode{ID: id, LocationID: taken.LocationID, ValidUntil: now.Add(time.Hour), RequestJSON: json.RawMessage(requestJSON)}, now)
		return Change{Decision: d, EMVContent: emvFor(taken.LocationID)}, nil
	})
	var dup *DuplicateError
	if !errors.As(err, &dup) || dup.Constraint != "ux_qr_code_location_id" || attempts != 1 {
		t.Fatalf("got %v after %d attempts, want a duplicate on ux_qr_code_location_id after 1", err, attempts)
	}
}

// A refusal is an answer: what the domain decided with it is committed. The holder's retried
// pre-commit, refused by the gate, releases the QR Code with payment.failed (ADR-0036).
func testRefusalCommitsWhatItDecided(t *testing.T, e *env) {
	ctx := t.Context()
	q := e.create(t)
	if _, err := e.notify(ctx, q.ID, payer("payer-a", ""), nil, settings); err != nil {
		t.Fatal(err)
	}
	gate := &domain.Refusal{Kind: domain.KindBusinessRule, Message: "the amount is wrong"}
	res, err := e.notify(ctx, q.ID, payer("payer-a", ""), gate, settings)
	if err != nil || res.Decision.Refusal != gate {
		t.Fatalf("refusal %v, %v", res.Decision.Refusal, err)
	}
	if len(res.Events) != 1 || res.Events[0].Type != domain.EventFailed {
		t.Fatalf("events %+v", res.Events)
	}
	got, err := e.store.Get(ctx, q.ID)
	if err != nil || got.Status != domain.StatusActive || got.ReservedBy != (domain.Holder{}) {
		t.Fatalf("after the refused retry: %s held by %+v, %v", got.Status, got.ReservedBy, err)
	}
}

type outcome struct {
	accepted, conflicts, busy int
	other                     []error
}

func (o *outcome) add(mu *sync.Mutex, res Result, err error) {
	mu.Lock()
	defer mu.Unlock()
	switch {
	case errors.Is(err, ErrBusy):
		o.busy++
	case err != nil:
		o.other = append(o.other, err)
	case res.Decision.Refusal == nil:
		o.accepted++
	case res.Decision.Refusal.Kind == domain.KindConflict:
		o.conflicts++
	default:
		o.other = append(o.other, res.Decision.Refusal)
	}
}

// race runs fn n times at once and waits for all of them.
func race(n int, fn func(i int)) {
	var wg sync.WaitGroup
	start := make(chan struct{})
	for i := range n {
		wg.Go(func() {
			<-start
			fn(i)
		})
	}
	close(start)
	wg.Wait()
}

// S9.5-AC6: payers racing for one QR Code: exactly one reservation, one payment.initiated, and
// every other payer the domain's 409 (or "busy"), with no lost update.
func testConcurrentPayersOnOneRow(t *testing.T, e *env) {
	ctx := t.Context()
	q := e.create(t)
	const n = 8
	var (
		mu  sync.Mutex
		out outcome
	)
	race(n, func(i int) {
		res, err := e.notify(ctx, q.ID, payer("payer-"+strconv.Itoa(i), ""), nil, settings)
		out.add(&mu, res, err)
	})
	if len(out.other) != 0 || out.accepted != 1 || out.accepted+out.conflicts+out.busy != n {
		t.Fatalf("outcome %+v", out)
	}
	if got := e.count(t, "SELECT COUNT(*) FROM payment_event WHERE qr_code_id = $1", q.ID); got != 1 {
		t.Fatalf("%d events, want 1", got)
	}
	if got := e.count(t, "SELECT COUNT(*) FROM qr_code_notification WHERE qr_code_id = $1", q.ID); got != 1 {
		t.Fatalf("%d notifications stored, want 1", got)
	}
	if got := e.count(t, "SELECT lock_version FROM qr_code WHERE id = $1", q.ID); got != 1 {
		t.Fatalf("lock_version %d, want 1", got)
	}
}

// S9.5-AC6 (ADR-0021, ADR-0025): one payer racing its own pre-commit is accepted every time,
// because the re-run decides; only the first takes the reservation and emits.
func testConcurrentRetriesOfOnePayer(t *testing.T, e *env) {
	ctx := t.Context()
	q := e.create(t)
	const n = 4
	var (
		mu  sync.Mutex
		out outcome
	)
	race(n, func(int) {
		res, err := e.notify(ctx, q.ID, payer("payer-a", ""), nil, settings)
		out.add(&mu, res, err)
	})
	if len(out.other) != 0 || out.conflicts != 0 || out.accepted == 0 || out.accepted+out.busy != n {
		t.Fatalf("outcome %+v", out)
	}
	if got := e.count(t, "SELECT COUNT(*) FROM payment_event WHERE qr_code_id = $1", q.ID); got != 1 {
		t.Fatalf("%d events, want 1", got)
	}
	if got := e.count(t, "SELECT COUNT(*) FROM qr_code_notification WHERE qr_code_id = $1", q.ID); got != out.accepted {
		t.Fatalf("%d notifications for %d accepted", got, out.accepted)
	}
}

// S9.5-AC6: concurrent PATCHes on one row: every accepted one moved the revision once and wrote its
// audit row; none was lost.
func testConcurrentPatchesLoseNoUpdate(t *testing.T, e *env) {
	ctx := t.Context()
	q := e.create(t)
	const n = 6
	var (
		mu  sync.Mutex
		out outcome
	)
	race(n, func(int) {
		res, err := e.store.Run(ctx, func(ctx context.Context, r *Reader, now time.Time) (Change, error) {
			before, err := r.QRCode(ctx, q.ID)
			if err != nil {
				return Change{}, err
			}
			return Change{
				Before: &before, Decision: domain.Patch(before.Row, nil, now),
				Audit: &Audit{ChangedFields: []string{"unstructured"}, PreviousValues: json.RawMessage(`{"unstructured":null}`)},
			}, nil
		})
		out.add(&mu, res, err)
	})
	if len(out.other) != 0 || out.accepted == 0 || out.accepted+out.busy != n {
		t.Fatalf("outcome %+v", out)
	}
	got, err := e.store.Get(ctx, q.ID)
	if err != nil {
		t.Fatal(err)
	}
	if got.Revision != out.accepted || got.LockVersion != out.accepted {
		t.Fatalf("revision %d, lock_version %d after %d accepted PATCHes", got.Revision, got.LockVersion, out.accepted)
	}
	if audits := e.count(t, "SELECT COUNT(*) FROM qr_code_audit_log WHERE qr_code_id = $1", q.ID); audits != out.accepted {
		t.Fatalf("%d audit rows for %d PATCHes", audits, out.accepted)
	}
}

// S9.5-AC6, S9.5-AC3: event-producing transactions on different rows, at once: seq is gapless, no
// reader ever sees a gap (so seq order is commit order), and, within this one instance (one Store,
// one generator), event_id sorts as seq does. Across instances that is not promised (ADR-0037,
// amended 2026-11-15), and nothing here asserts it.
func testGaplessSeqInCommitOrder(t *testing.T, e *env) {
	ctx := t.Context()
	const n = 32
	rows := make([]QRCode, n)
	for i := range rows {
		rows[i] = e.create(t)
	}
	start := e.lastSeq(t)

	observed, done := make(chan error, 1), make(chan struct{})
	go func() {
		var err error
		defer func() { observed <- err }()
		for {
			select {
			case <-done:
				return
			default:
			}
			var count, top int64
			if err = e.other.QueryRow(ctx, "SELECT COUNT(*), COALESCE(MAX(seq), 0) FROM payment_event WHERE seq > $1", start).Scan(&count, &top); err != nil {
				return
			}
			if count > 0 && top-start != count {
				err = errors.New("a reader saw a gap: " + strings.Repeat("|", int(top-start-count)))
				return
			}
		}
	}()

	var (
		mu  sync.Mutex
		out outcome
	)
	race(n, func(i int) {
		res, err := e.setStatus(ctx, rows[i].ID, domain.StatusCancelled)
		out.add(&mu, res, err)
	})
	close(done)
	if err := <-observed; err != nil {
		t.Fatal(err)
	}
	if out.accepted != n {
		t.Fatalf("outcome %+v", out)
	}
	events, err := e.store.Events(ctx, start, 2*n)
	if err != nil {
		t.Fatal(err)
	}
	if len(events) != n {
		t.Fatalf("%d events, want %d", len(events), n)
	}
	for i, ev := range events {
		if ev.Seq != start+int64(i)+1 {
			t.Fatalf("event %d has seq %d, want %d", i, ev.Seq, start+int64(i)+1)
		}
		if i > 0 && string(ev.EventID[:]) <= string(events[i-1].EventID[:]) {
			t.Fatalf("event_id %s at seq %d does not follow %s", ev.EventID, ev.Seq, events[i-1].EventID)
		}
	}
}

// S9.5-AC6, S9.5-AC7: a transition rolled back after its event batch leaves no event, no change and
// no gap, and wakes no reader; the next commit takes the next number and wakes them.
func testRollbackLeavesNoEventAndNoGap(t *testing.T, e *env) {
	ctx := t.Context()
	q := e.create(t)
	start := e.lastSeq(t)
	woken := e.store.EventCommitted()

	failure := errors.New("failed after the event batch")
	e.store.afterEvents = func(context.Context) error { return failure }
	if _, err := e.setStatus(ctx, q.ID, domain.StatusCancelled); !errors.Is(err, failure) {
		t.Fatalf("got %v, want the failure", err)
	}
	if e.lastSeq(t) != start {
		t.Fatal("the rolled-back event's number was kept")
	}
	if got, err := e.store.Get(ctx, q.ID); err != nil || got.Status != domain.StatusActive {
		t.Fatalf("the rolled-back change was kept: %s, %v", got.Status, err)
	}
	select {
	case <-woken:
		t.Fatal("a rollback woke the event readers")
	default:
	}

	e.store.afterEvents = nil
	res, err := e.setStatus(ctx, q.ID, domain.StatusCancelled)
	if err != nil {
		t.Fatal(err)
	}
	events, err := e.store.Events(ctx, start, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(events) != 1 || events[0].Seq != start+1 || events[0].EventID != res.Events[0].ID {
		t.Fatalf("after the rollback: %+v", events)
	}
	select {
	case <-woken:
	case <-time.After(5 * time.Second): //nolint:forbidigo // a test's deadline, not business time
		t.Fatal("a commit with an event did not wake the event readers")
	}
}

// S9.5-AC4: no statement the store sent in this whole run named the schema.
func testNoStatementIsSchemaQualified(t *testing.T, e *env) {
	for _, r := range []*recorder{e.writes, e.reads} {
		r.mu.Lock()
		all := r.all
		r.mu.Unlock()
		if len(all) == 0 {
			t.Fatal("nothing was recorded")
		}
		for _, s := range all {
			if strings.Contains(s.sql, dbtest.Schema+".") {
				t.Errorf("schema-qualified: %s", s.sql)
			}
		}
	}
}
