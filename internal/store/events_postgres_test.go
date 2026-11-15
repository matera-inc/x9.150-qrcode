// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package store

import (
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/google/go-cmp/cmp"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/matera-inc/x9.150-qrcode/internal/database"
	"github.com/matera-inc/x9.150-qrcode/internal/dbtest"
	"github.com/matera-inc/x9.150-qrcode/internal/domain"
)

// The outbox as docs/EVENTS-TABLE.md promises it to a payment system (Story 10.1). These run as
// subtests of TestPostgres.

func eventsDoc(t *testing.T) string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(dbtest.RepoRoot(t), "docs", "EVENTS-TABLE.md"))
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

// section is the text of the doc under heading, up to the next heading of the same or a higher
// level.
func section(t *testing.T, doc, heading string) string {
	t.Helper()
	start := strings.Index(doc, "\n"+heading+"\n")
	if start < 0 {
		t.Fatalf("docs/EVENTS-TABLE.md has no %q", heading)
	}
	level := strings.IndexByte(heading, ' ')
	var out []string
	for line := range strings.SplitSeq(doc[start+len(heading)+2:], "\n") {
		if hashes := len(line) - len(strings.TrimLeft(line, "#")); hashes > 0 && hashes <= level &&
			strings.HasPrefix(line[hashes:], " ") {
			break
		}
		out = append(out, line)
	}
	return strings.Join(out, "\n")
}

// S10.1-AC5: payment_event has exactly the columns docs/EVENTS-TABLE.md lists, in its order and
// with its types.
func testEventColumnsAreTheDocument(t *testing.T, e *env) {
	table := section(t, eventsDoc(t), "## The columns")
	cell := regexp.MustCompile("^\\| `([a-z_]+)` \\| `([A-Z]+(?:\\(\\d+\\))?)`")
	var documented []string
	for line := range strings.SplitSeq(table, "\n") {
		if m := cell.FindStringSubmatch(line); m != nil {
			documented = append(documented, m[1]+" "+m[2])
		}
	}
	if len(documented) < 20 {
		t.Fatalf("read only %d columns from the document's table", len(documented))
	}

	rows, err := e.other.Query(t.Context(), `SELECT column_name, data_type, character_maximum_length
		FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'payment_event'
		ORDER BY ordinal_position`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var actual []string
	for rows.Next() {
		var (
			name, typ string
			length    *int32
		)
		if err := rows.Scan(&name, &typ, &length); err != nil {
			t.Fatal(err)
		}
		sqlType := map[string]string{
			"bigint": "BIGINT", "uuid": "UUID", "integer": "INT", "timestamp without time zone": "TIMESTAMP",
			"character varying": "VARCHAR",
		}[typ]
		if sqlType == "" {
			t.Fatalf("column %s has type %s, which the document does not use", name, typ)
		}
		if length != nil {
			sqlType += "(" + strconv.Itoa(int(*length)) + ")"
		}
		actual = append(actual, name+" "+sqlType)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	if diff := cmp.Diff(documented, actual); diff != "" {
		t.Fatalf("payment_event differs from docs/EVENTS-TABLE.md (-document +table):\n%s", diff)
	}
}

// plainSQL is the document's plain-SQL reader, its two statements taken from the document itself:
// the claim (LIMIT and placeholders aside) and the mark.
type plainSQL struct {
	claim, mark string
}

func readPlainSQL(t *testing.T) plainSQL {
	t.Helper()
	block := section(t, eventsDoc(t), "### 2. Plain SQL with the event-reader role")
	start := strings.Index(block, "```sql\n")
	end := strings.Index(block[start+7:], "```")
	if start < 0 || end < 0 {
		t.Fatal("no sql block under the plain-SQL reader")
	}
	var lines []string
	for line := range strings.SplitSeq(block[start+7:start+7+end], "\n") {
		if i := strings.Index(line, "--"); i >= 0 {
			line = line[:i]
		}
		lines = append(lines, line)
	}
	var stmts []string
	for s := range strings.SplitSeq(strings.Join(lines, "\n"), ";") {
		if s = strings.TrimSpace(s); s != "" {
			stmts = append(stmts, s)
		}
	}
	var p plainSQL
	for _, s := range stmts {
		switch {
		case strings.HasPrefix(s, "SELECT"):
			p.claim = strings.Replace(s, "LIMIT 100", "LIMIT $1", 1)
		case strings.HasPrefix(s, "UPDATE"):
			p.mark = strings.Replace(strings.Replace(s, ":now", "$1", 1), "(:claimed)", "(SELECT unnest(CAST($2 AS BIGINT[])))", 1)
		}
	}
	if !strings.Contains(p.claim, "FOR UPDATE OF e SKIP LOCKED") || !strings.Contains(p.claim, "LIMIT $1") ||
		!strings.Contains(p.mark, "read_status_code = 'DONE'") || !strings.Contains(p.mark, "$2") {
		t.Fatalf("the document's reader changed shape; update this test:\nclaim: %s\nmark: %s", p.claim, p.mark)
	}
	return p
}

func (p plainSQL) claimIn(t *testing.T, tx pgx.Tx, limit int) []int64 {
	t.Helper()
	rows, err := tx.Query(t.Context(), p.claim, limit)
	if err != nil {
		t.Fatalf("claim: %v", err)
	}
	defer rows.Close()
	var seqs []int64
	for rows.Next() {
		values, err := rows.Values()
		if err != nil {
			t.Fatal(err)
		}
		seqs = append(seqs, values[0].(int64))
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	return seqs
}

func eventReaderPool(t *testing.T, e *env) *pgxpool.Pool {
	t.Helper()
	pc, err := database.PoolConfig(e.db.Config(dbtest.EventReader), 4)
	if err != nil {
		t.Fatal(err)
	}
	p, err := pgxpool.NewWithConfig(t.Context(), pc)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p.Close)
	return p
}

// S10.1-AC5: the rows Go inserts are read as the document's plain-SQL reader reads them, under the
// event-reader role's grants: two workers claim disjoint rows with SKIP LOCKED; a worker that
// commits marks its rows DONE, one that rolls back gives them back; any other write is refused.
func testPlainSQLReader(t *testing.T, e *env) {
	ctx := t.Context()
	for range 4 {
		q := e.create(t)
		if _, err := e.setStatus(ctx, q.ID, domain.StatusCancelled); err != nil {
			t.Fatal(err)
		}
	}
	p := readPlainSQL(t)
	reader := eventReaderPool(t, e)
	begin := func() pgx.Tx {
		tx, err := reader.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = tx.Rollback(ctx) })
		return tx
	}
	var pending int
	if err := reader.QueryRow(ctx, "SELECT COUNT(*) FROM payment_event WHERE read_status_code = 'PEND'").Scan(&pending); err != nil {
		t.Fatal(err)
	}
	if pending < 4 {
		t.Fatalf("%d pending events, want at least the 4 just written", pending)
	}

	a, b := begin(), begin()
	claimedA := p.claimIn(t, a, 2)
	claimedB := p.claimIn(t, b, 1000)
	if len(claimedA) != 2 || len(claimedA)+len(claimedB) != pending {
		t.Fatalf("A claimed %v, B claimed %v, of %d pending", claimedA, claimedB, pending)
	}
	if !slices.IsSorted(claimedA) || !slices.IsSorted(claimedB) {
		t.Fatalf("a claim is not in seq order: %v, %v", claimedA, claimedB)
	}
	for _, s := range claimedA {
		if slices.Contains(claimedB, s) {
			t.Fatalf("seq %d was claimed by both workers", s)
		}
	}

	readAt := time.Date(2026, 11, 15, 13, 0, 0, 0, time.UTC)
	if tag, err := a.Exec(ctx, p.mark, readAt, claimedA); err != nil || tag.RowsAffected() != 2 {
		t.Fatalf("mark: %v, %v", tag, err)
	}
	if err := a.Commit(ctx); err != nil {
		t.Fatal(err)
	}
	if err := b.Rollback(ctx); err != nil { // a worker that dies before COMMIT
		t.Fatal(err)
	}

	c := begin()
	again := p.claimIn(t, c, 1000)
	if diff := cmp.Diff(claimedB, again); diff != "" {
		t.Fatalf("after A's commit and B's rollback, the pending rows (-B's +again):\n%s", diff)
	}
	_ = c.Rollback(ctx)
	for _, s := range claimedA {
		var status string
		var at time.Time
		if err := reader.QueryRow(ctx, "SELECT read_status_code, read_at FROM payment_event WHERE seq = $1", s).Scan(&status, &at); err != nil {
			t.Fatal(err)
		}
		if status != "DONE" || !at.Equal(readAt) {
			t.Fatalf("seq %d: %s at %v, want DONE at %v", s, status, at, readAt)
		}
	}
	// The read status is the SQL reader's alone: the HTTP stream reads every event regardless.
	rows, err := e.store.Events(ctx, claimedA[0]-1, 1)
	if err != nil || len(rows) != 1 || rows[0].ReadStatusCode != "DONE" {
		t.Fatalf("the stream after a mark: %+v, %v", rows, err)
	}

	for _, sql := range []string{
		"UPDATE payment_event SET amount = 0 WHERE seq = " + strconv.FormatInt(claimedA[0], 10),
		"DELETE FROM payment_event WHERE seq = " + strconv.FormatInt(claimedA[0], 10),
		"INSERT INTO payment_event_legacy_cursor (legacy_cursor, seq) VALUES ('X', 1)",
		"UPDATE event_sequence SET last_seq = 0",
	} {
		_, err := reader.Exec(ctx, sql)
		var pgErr *pgconn.PgError
		if !errors.As(err, &pgErr) || pgErr.Code != "42501" {
			t.Errorf("the event reader ran %q: %v, want a permission error", sql, err)
		}
	}
}

// S10.1-AC1: a Java build's ULID cursor translates to the seq to resume after (ADR-0026).
func testLegacyCursor(t *testing.T, e *env) {
	ctx := t.Context()
	translate := func(cursor string) int64 {
		t.Helper()
		seq, err := e.store.LegacyCursor(ctx, cursor)
		if err != nil {
			t.Fatal(err)
		}
		return seq
	}
	if got := translate("01J00000000000000000000000"); got != 0 {
		t.Fatalf("with nothing migrated, a ULID resumes at %d, want the beginning (0)", got)
	}

	seqs := make([]int64, 0, 3)
	for range 3 {
		q := e.create(t)
		res, err := e.setStatus(ctx, q.ID, domain.StatusCancelled)
		if err != nil || len(res.Events) != 1 {
			t.Fatalf("%+v, %v", res, err)
		}
		seqs = append(seqs, e.lastSeq(t))
	}
	// The first two stand for migrated events, the third for one the Go build wrote.
	const first, second = "01J0000000000000000000000A", "01J0000000000000000000000C"
	for i, c := range []string{first, second} {
		if _, err := e.other.Exec(ctx, "INSERT INTO payment_event_legacy_cursor (legacy_cursor, seq) VALUES ($1, $2)", c, seqs[i]); err != nil {
			t.Fatal(err)
		}
	}
	e.reads.take()
	e.writes.take()
	for _, tc := range []struct {
		cursor string
		want   int64
		why    string
	}{
		{first, seqs[0], "its own row: after that event"},
		{second, seqs[1], "its own row: after that event"},
		{"01J0000000000000000000000B", seqs[1] - 1, "between two: at the next migrated event"},
		{"00000000000000000000000000", seqs[0] - 1, "before every one: at the first migrated event"},
		{"7ZZZZZZZZZZZZZZZZZZZZZZZZZ", seqs[1], "after every one: after the last migrated event"},
	} {
		if got := translate(tc.cursor); got != tc.want {
			t.Errorf("%s resumes after %d, want %d (%s)", tc.cursor, got, tc.want, tc.why)
		}
	}
	if len(e.writes.take()) != 0 || len(e.reads.take()) == 0 {
		t.Fatal("the translation did not read on the read pool alone")
	}
}
