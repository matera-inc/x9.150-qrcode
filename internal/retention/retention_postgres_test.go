// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package retention

import (
	"context"
	"fmt"
	"slices"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/matera-inc/x9.150-qrcode/internal/database"
	"github.com/matera-inc/x9.150-qrcode/internal/dbtest"
)

// The job against a real PostgreSQL with the changelog applied, connected as the application role,
// as the service runs it: so the role's DELETE grants are proved too. One container; the subtests
// run in order, each on empty tables.
func TestPostgres(t *testing.T) {
	db := dbtest.Start(t)
	pools, err := database.Open(t.Context(), db.Config(dbtest.AppRole))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pools.Close)
	e := &pgEnv{db: db, pool: pools.Write, now: time.Date(2027, 6, 1, 12, 0, 0, 0, time.UTC)}

	for _, tc := range []struct {
		name string
		test func(t *testing.T, e *pgEnv)
	}{
		{"EventsThenQRCodesByTheBothDatesRule", testEventsThenQRCodes},
		{"Batches", testBatches},
		{"LockedRowIsSkipped", testLockedRowIsSkipped},
		{"QueriesUseIndexes", testQueriesUseIndexes},
	} {
		e.truncate(t)
		t.Run(tc.name, func(t *testing.T) { tc.test(t, e) })
	}
}

type pgEnv struct {
	db   *dbtest.DB
	pool *pgxpool.Pool
	now  time.Time
	seq  int
}

// countingDB counts the transactions the job begins.
type countingDB struct {
	DB
	begins atomic.Int64
}

func (c *countingDB) BeginTx(ctx context.Context, opts pgx.TxOptions) (pgx.Tx, error) {
	c.begins.Add(1)
	return c.DB.BeginTx(ctx, opts)
}

func (e *pgEnv) psql(t *testing.T, sql string) string {
	t.Helper()
	return e.db.Psql(t.Context(), t, "SET search_path TO "+dbtest.Schema+"; "+sql)
}

func (e *pgEnv) truncate(t *testing.T) {
	t.Helper()
	e.psql(t, "TRUNCATE payment_event_legacy_cursor, payment_event, qr_code_audit_log, qr_code_notification, qr_code")
}

func ts(t time.Time) string { return "'" + t.UTC().Format("2006-01-02 15:04:05.999999") + "'" }

// qrCode inserts a QR Code with the given dates and status, an audit row and a notification.
func (e *pgEnv) qrCode(t *testing.T, status string, validUntil, revisedAt time.Time) uuid.UUID {
	t.Helper()
	id := uuid.New()
	e.psql(t, fmt.Sprintf(`INSERT INTO qr_code (id, location_id, emv_content, valid_until, status_code, revision,
 lock_version, request_json, created_at, revised_at) VALUES ('%[1]s', '%[2]s', 'emv', %[3]s, '%[4]s', 2, 1, '{}', %[5]s, %[5]s);
 INSERT INTO qr_code_audit_log (qr_code_id, revision, changed_at, changed_fields, previous_values)
 VALUES ('%[1]s', 1, %[5]s, 'bill', '{}');
 INSERT INTO qr_code_notification (id, qr_code_id, received_at, received_in_status_code, phase, network,
 currency, amount, notification_json) VALUES ('%[6]s', '%[1]s', %[5]s, 'ACTV', 'PRE_COMMIT', 'solana', 'USDC', 1, '{}')`,
		id, uuid.New(), ts(validUntil), status, ts(revisedAt), uuid.New()))
	return id
}

// event inserts an event of qr that occurred at, with a legacy cursor when legacy is set.
func (e *pgEnv) event(t *testing.T, qr uuid.UUID, at time.Time, legacy bool) int {
	t.Helper()
	e.seq++
	e.psql(t, fmt.Sprintf(`INSERT INTO payment_event (seq, event_id, type_code, occurred_at, qr_code_id,
 qr_code_revision, location_id, schema_version) VALUES (%d, '%s', 'CANC', %s, '%s', 1, '%s', '1.0')`,
		e.seq, uuid.New(), ts(at), qr, uuid.New()))
	if legacy {
		e.psql(t, fmt.Sprintf(`INSERT INTO payment_event_legacy_cursor (legacy_cursor, seq) VALUES ('%026d', %d)`, e.seq, e.seq))
	}
	return e.seq
}

func (e *pgEnv) ids(t *testing.T, sql string) []string {
	t.Helper()
	out := strings.Fields(e.psql(t, sql))
	slices.Sort(out)
	return out
}

func sorted(v ...string) []string {
	slices.Sort(v)
	return v
}

func (e *pgEnv) job(db DB, batch int) *Job {
	j, _, _ := newJob(db, &fixedClock{now: e.now}, Config{Events: 30 * day, QRCodes: 400 * day, Interval: time.Hour, BatchSize: batch, Pause: time.Millisecond})
	return j
}

// S12.1-AC2, S12.1-AC5: events older than events.retention go, with their legacy cursors; then QR
// Codes whose valid_until and revised_at are both older than qrcode.retention, in any status, with
// their audit-log and notification rows. Nothing younger is touched, a row exactly at a cutoff is
// kept, an event outlives its QR Code (no foreign key), and no kept row changes its status.
func testEventsThenQRCodes(t *testing.T, e *pgEnv) {
	events, qrs := e.now.Add(-30*day), e.now.Add(-400*day)
	old := func(d time.Time) time.Time { return d.Add(-time.Second) }
	young := func(d time.Time) time.Time { return d.Add(time.Second) }

	paid := e.qrCode(t, "PAID", old(qrs), old(qrs))      // both dates old: purged
	e.qrCode(t, "ACTV", old(qrs), old(qrs))              // any status: purged
	revised := e.qrCode(t, "CANC", old(qrs), young(qrs)) // revised since: kept
	valid := e.qrCode(t, "INIT", young(qrs), old(qrs))   // still valid then: kept
	atCutoff := e.qrCode(t, "ACTV", qrs, qrs)            // exactly at the cutoff: kept

	oldEvent := e.event(t, paid, old(events), true)
	youngEvent := e.event(t, paid, young(events), false) // an event of a purged QR Code stays
	cutoffEvent := e.event(t, revised, events, false)

	r, err := e.job(e.pool, 0).RunOnce(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]int64{TableLegacyCursor: 1, TablePaymentEvent: 1, TableQRCodeAuditLog: 2, TableQRCodeNotification: 2, TableQRCode: 2}
	if diff := cmp.Diff(want, r.Deleted); diff != "" {
		t.Fatalf("deleted (-want +got):\n%s", diff)
	}
	kept := sorted(revised.String(), valid.String(), atCutoff.String())
	if got := e.ids(t, "SELECT id FROM qr_code"); !slices.Equal(got, kept) {
		t.Fatalf("qr_code kept %v, want %v", got, kept)
	}
	for _, table := range []string{"qr_code_audit_log", "qr_code_notification"} {
		if got := e.ids(t, "SELECT qr_code_id FROM "+table); !slices.Equal(got, kept) {
			t.Fatalf("%s kept %v, want %v", table, got, kept)
		}
	}
	if got, want := e.ids(t, "SELECT seq FROM payment_event"), sorted(strconv.Itoa(youngEvent), strconv.Itoa(cutoffEvent)); !slices.Equal(got, want) {
		t.Fatalf("payment_event kept %v, want %v (and not %d)", got, want, oldEvent)
	}
	if got := e.psql(t, "SELECT count(*) FROM payment_event_legacy_cursor"); got != "0" {
		t.Fatalf("legacy cursors left: %s", got)
	}
	// S12.1-AC4: it never changes a status.
	if got := e.psql(t, "SELECT string_agg(status_code, ',' ORDER BY status_code) FROM qr_code"); got != "ACTV,CANC,INIT" {
		t.Fatalf("statuses after the run: %s", got)
	}

	// A second run finds nothing.
	r, err = e.job(e.pool, 0).RunOnce(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	for table, n := range r.Deleted {
		if n != 0 {
			t.Fatalf("second run deleted %d from %s", n, table)
		}
	}
}

// S12.1-AC3: a batch per transaction, until a batch is not full.
func testBatches(t *testing.T, e *pgEnv) {
	qr := uuid.New()
	for range 10 {
		e.event(t, qr, e.now.Add(-31*day), false)
	}
	for range 7 {
		e.qrCode(t, "CANC", e.now.Add(-401*day), e.now.Add(-401*day))
	}
	db := &countingDB{DB: e.pool}
	r, err := e.job(db, 3).RunOnce(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if r.Deleted[TablePaymentEvent] != 10 || r.Deleted[TableQRCode] != 7 {
		t.Fatalf("deleted %v", r.Deleted)
	}
	// Events: 3, 3, 3, 1. QR Codes: 3, 3, 1.
	if got := db.begins.Load(); got != 7 {
		t.Fatalf("%d transactions, want 7", got)
	}
}

// A QR Code a transition holds is skipped, never waited for, and purged by a later run.
func testLockedRowIsSkipped(t *testing.T, e *pgEnv) {
	held := e.qrCode(t, "INIT", e.now.Add(-401*day), e.now.Add(-401*day))
	free := e.qrCode(t, "ACTV", e.now.Add(-401*day), e.now.Add(-401*day))

	tx, err := e.pool.Begin(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := tx.Exec(t.Context(), "SELECT id FROM qr_code WHERE id = $1 FOR UPDATE", held); err != nil {
		t.Fatal(err)
	}
	r, err := e.job(e.pool, 0).RunOnce(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if r.Deleted[TableQRCode] != 1 || e.psql(t, "SELECT id FROM qr_code") != held.String() {
		t.Fatalf("with %s held: deleted %v; %s left", held, r.Deleted, e.psql(t, "SELECT id FROM qr_code"))
	}
	if err := tx.Rollback(t.Context()); err != nil {
		t.Fatal(err)
	}
	if _, err := e.job(e.pool, 0).RunOnce(t.Context()); err != nil {
		t.Fatal(err)
	}
	if got := e.psql(t, "SELECT count(*) FROM qr_code"); got != "0" {
		t.Fatalf("%s rows left after the lock was released (free was %s)", got, free)
	}
}

// S12.1-AC3: each statement the job runs can use an index (proved with sequential scans off, as
// the tables here are too small for the planner to prefer one).
func testQueriesUseIndexes(t *testing.T, e *pgEnv) {
	for _, tc := range []struct{ sql, index string }{
		{strings.ReplaceAll(strings.ReplaceAll(sqlSelectOldEvents, "$1", ts(e.now)), "$2", "500"), "ix_payment_event_occurred_at"},
		{strings.ReplaceAll(strings.ReplaceAll(sqlSelectOldQRCodes, "$1", ts(e.now)), "$2", "500"), "ix_qr_code_purge"},
		{fmt.Sprintf(sqlDeleteLegacyCursors, "1, 2"), "ix_payment_event_legacy_cursor_seq"},
		{fmt.Sprintf(sqlDeleteEvents, "1, 2"), "payment_event_pkey"},
		{fmt.Sprintf(sqlDeleteAuditLog, "'"+uuid.NewString()+"'"), "pk_qr_code_audit_log"},
		{fmt.Sprintf(sqlDeleteNotifications, "'"+uuid.NewString()+"'"), "ix_qr_code_notification_qr_code_id"},
		{fmt.Sprintf(sqlDeleteQRCodes, "'"+uuid.NewString()+"'"), "qr_code_pkey"},
	} {
		plan := e.psql(t, "SET enable_seqscan = off; EXPLAIN "+tc.sql)
		if !strings.Contains(plan, tc.index) {
			t.Errorf("%s\ndoes not use %s:\n%s", tc.sql, tc.index, plan)
		}
	}
}
