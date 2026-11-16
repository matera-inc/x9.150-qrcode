// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package retention is the service's one background job (ADR-0029): one goroutine with one
// time.Timer, reset after each run. Each run takes the business clock once (ADR-0033) and
//
//  1. deletes the payment_event rows older than events.retention, with their legacy cursors;
//  2. then deletes the qr_code rows whose valid_until and revised_at are both older than
//     qrcode.retention, in any status, with their qr_code_audit_log and qr_code_notification rows.
//
// Each purge works in batches of a few hundred rows, one transaction per batch, with a short pause
// between batches, so a payment never waits behind it. A QR Code locked by a transition in flight
// is skipped and left for the next run. The job only deletes: it never writes a status, so it
// cannot race a payment. A run that fails is logged, counted and retried at the next tick.
package retention

import (
	"context"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/matera-inc/x9.150-qrcode/internal/clock"
	"github.com/matera-inc/x9.150-qrcode/internal/config"
	"github.com/matera-inc/x9.150-qrcode/internal/metrics"
)

// The tables the job deletes from, as named in its log line and its metrics.
const (
	TablePaymentEvent       = "payment_event"
	TableLegacyCursor       = "payment_event_legacy_cursor"
	TableQRCode             = "qr_code"
	TableQRCodeAuditLog     = "qr_code_audit_log"
	TableQRCodeNotification = "qr_code_notification"
)

// The metrics the job exports (S12.2-AC1), at /actuator/prometheus.
const (
	MetricDeletedRows = "x9_retention_deleted_rows_total"
	MetricRuns        = "x9_retention_runs_total"
	MetricFailedRuns  = "x9_retention_runs_failed_total"
)

const (
	defaultBatchSize = 500
	defaultPause     = 100 * time.Millisecond
	statementTimeout = "30s"
	lockTimeout      = "2s"
)

// Every statement of the job. None is schema-qualified and none reads the database's clock
// (ADR-0023): the cutoffs are computed in Go and bound in UTC. Each SELECT is served by an index
// of the changelog: ix_payment_event_occurred_at, and ix_qr_code_purge (valid_until, revised_at);
// each DELETE by a primary key, ix_payment_event_legacy_cursor_seq,
// ix_qr_code_notification_qr_code_id and pk_qr_code_audit_log, which leads with qr_code_id.
const (
	sqlSetStatementTimeout = `SELECT set_config('statement_timeout', '` + statementTimeout + `', true)`
	sqlSetLockTimeout      = `SELECT set_config('lock_timeout', '` + lockTimeout + `', true)`

	sqlSelectOldEvents = `SELECT seq FROM payment_event WHERE occurred_at < $1
 ORDER BY occurred_at FETCH FIRST $2 ROWS ONLY`
	// sqlSelectOldQRCodes locks what it chooses, skipping a row a transition holds.
	sqlSelectOldQRCodes = `SELECT id FROM qr_code WHERE valid_until < $1 AND revised_at < $1
 ORDER BY valid_until FETCH FIRST $2 ROWS ONLY FOR UPDATE SKIP LOCKED`

	sqlDeleteLegacyCursors = `DELETE FROM payment_event_legacy_cursor WHERE seq IN (%s)`
	sqlDeleteEvents        = `DELETE FROM payment_event WHERE seq IN (%s)`
	sqlDeleteAuditLog      = `DELETE FROM qr_code_audit_log WHERE qr_code_id IN (%s)`
	sqlDeleteNotifications = `DELETE FROM qr_code_notification WHERE qr_code_id IN (%s)`
	sqlDeleteQRCodes       = `DELETE FROM qr_code WHERE id IN (%s)`
)

// Tables are the tables a run deletes from, in the order it deletes from them.
var Tables = []string{TableLegacyCursor, TablePaymentEvent, TableQRCodeAuditLog, TableQRCodeNotification, TableQRCode}

// DB begins a transaction: a *pgxpool.Pool, the write pool.
type DB interface {
	BeginTx(ctx context.Context, opts pgx.TxOptions) (pgx.Tx, error)
}

// Clock is the business clock (internal/clock).
type Clock interface {
	Now() time.Time
}

// Config is the job's configuration.
type Config struct {
	// Events is events.retention: an event older than this is deleted.
	Events time.Duration
	// QRCodes is qrcode.retention: a QR Code whose valid_until and revised_at are both older than
	// this is deleted.
	QRCodes time.Duration
	// Interval is the real time between the end of one run and the start of the next.
	Interval time.Duration
	// BatchSize is how many rows one transaction deletes from the table it purges; 0 is 500.
	BatchSize int
	// Pause is the real time between two batches; 0 is 100 ms.
	Pause time.Duration
}

// Job is the retention job.
type Job struct {
	db      DB
	clock   Clock
	cfg     Config
	logger  *slog.Logger
	deleted map[string]*metrics.Counter
	runs    *metrics.Counter
	failed  *metrics.Counter
}

// New builds the job. Its counters are registered on reg.
func New(db DB, business Clock, cfg Config, reg *metrics.Registry, logger *slog.Logger) *Job {
	if cfg.BatchSize <= 0 {
		cfg.BatchSize = defaultBatchSize
	}
	if cfg.Pause <= 0 {
		cfg.Pause = defaultPause
	}
	j := &Job{
		db: db, clock: business, cfg: cfg, logger: logger,
		deleted: make(map[string]*metrics.Counter, len(Tables)),
		runs:    reg.Counter(MetricRuns, "Retention runs that ran to the end or failed (ADR-0029)."),
		failed:  reg.Counter(MetricFailedRuns, "Retention runs that failed; each is logged and retried at the next tick."),
	}
	for _, t := range Tables {
		j.deleted[t] = reg.Counter(MetricDeletedRows, "Rows deleted by the retention job (ADR-0029), per table.",
			metrics.Label{Name: "table", Value: t})
	}
	return j
}

// Report is what one run did.
type Report struct {
	// Now is the business time the run took, once; the cutoffs are computed from it.
	Now           time.Time
	EventsCutoff  time.Time
	QRCodesCutoff time.Time
	// Deleted counts the rows deleted, per table.
	Deleted map[string]int64
}

// Run runs the job until ctx ends: a first run at once, then one run Interval after the end of
// the previous one. It returns when ctx ends, after the run in progress, if any, has stopped.
func (j *Job) Run(ctx context.Context) {
	j.logger.Info("x9-qrcode retention started", "eventsRetention", config.Duration(j.cfg.Events).String(),
		"qrCodeRetention", config.Duration(j.cfg.QRCodes).String(), "interval", config.Duration(j.cfg.Interval).String())
	defer j.logger.Info("x9-qrcode retention stopped")
	timer := time.NewTimer(0) //nolint:forbidigo // the real interval between runs (ADR-0029), not business time
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
		}
		_, _ = j.RunOnce(ctx) // RunOnce logs and counts its own failure
		timer.Reset(j.cfg.Interval)
	}
}

// RunOnce runs one purge: events, then QR Codes. It logs one line, at INFO when it finishes and at
// ERROR when it fails, and counts what it deleted even when it fails part-way. A run cut short by
// ctx is neither an error line nor a failure.
func (j *Job) RunOnce(ctx context.Context) (Report, error) {
	started := time.Now() //nolint:forbidigo // measures the run's real duration
	now := j.clock.Now().UTC()
	r := Report{
		Now:           now,
		EventsCutoff:  now.Add(-j.cfg.Events),
		QRCodesCutoff: now.Add(-j.cfg.QRCodes),
		Deleted:       make(map[string]int64, len(Tables)),
	}
	err := j.purgeEvents(ctx, &r)
	if err == nil {
		err = j.purgeQRCodes(ctx, &r)
	}
	for t, n := range r.Deleted {
		if c, ok := j.deleted[t]; ok && c != nil {
			c.Add(uint64(n)) //nolint:gosec // G115: a row count is never negative
		}
	}
	attrs := []any{
		"eventsCutoff", r.EventsCutoff, "qrCodesCutoff", r.QRCodesCutoff,
		"deleted", deletedGroup(r.Deleted),
		"durationMs", time.Since(started).Milliseconds(), //nolint:forbidigo // the run's real duration
	}
	switch {
	case err != nil && ctx.Err() != nil:
		j.logger.Info("x9-qrcode retention run cut short by the shutdown", append(attrs, "err", err)...)
		return r, err
	case err != nil:
		j.runs.Inc()
		j.failed.Inc()
		j.logger.Error("x9-qrcode retention run failed; retried at the next tick", append(attrs, "err", err)...)
		return r, err
	}
	j.runs.Inc()
	j.logger.Info("x9-qrcode retention run", attrs...)
	return r, nil
}

func deletedGroup(deleted map[string]int64) slog.Value {
	attrs := make([]slog.Attr, 0, len(Tables))
	for _, t := range Tables {
		attrs = append(attrs, slog.Int64(t, deleted[t]))
	}
	return slog.GroupValue(attrs...)
}

// purgeEvents deletes the events older than the cutoff, a batch per transaction. An event's
// legacy cursor (a migrated event's) goes with it, in the same transaction.
func (j *Job) purgeEvents(ctx context.Context, r *Report) error {
	return j.batches(ctx, func(tx pgx.Tx) (int, error) {
		seqs, err := collect[int64](ctx, tx, sqlSelectOldEvents, r.EventsCutoff, j.cfg.BatchSize)
		if err != nil || len(seqs) == 0 {
			return 0, err
		}
		if err := deleteIn(ctx, tx, r, TableLegacyCursor, sqlDeleteLegacyCursors, seqs); err != nil {
			return 0, err
		}
		return len(seqs), deleteIn(ctx, tx, r, TablePaymentEvent, sqlDeleteEvents, seqs)
	})
}

// purgeQRCodes deletes the QR Codes whose valid_until and revised_at are both older than the
// cutoff, in any status, a batch per transaction, each with its audit-log and notification rows.
// The rows are locked as they are chosen, and a row a transition holds is skipped: it is being
// revised, so it may not be old once that transition commits.
func (j *Job) purgeQRCodes(ctx context.Context, r *Report) error {
	return j.batches(ctx, func(tx pgx.Tx) (int, error) {
		ids, err := collect[uuid.UUID](ctx, tx, sqlSelectOldQRCodes, r.QRCodesCutoff, j.cfg.BatchSize)
		if err != nil || len(ids) == 0 {
			return 0, err
		}
		for _, d := range []struct{ table, sql string }{
			{TableQRCodeAuditLog, sqlDeleteAuditLog},
			{TableQRCodeNotification, sqlDeleteNotifications},
			{TableQRCode, sqlDeleteQRCodes},
		} {
			if err := deleteIn(ctx, tx, r, d.table, d.sql, ids); err != nil {
				return 0, err
			}
		}
		return len(ids), nil
	})
}

// batches runs batch, one transaction each, until one finds fewer rows than a full batch, pausing
// between them.
func (j *Job) batches(ctx context.Context, batch func(pgx.Tx) (int, error)) error {
	for {
		var n int
		err := pgx.BeginTxFunc(ctx, j.db, pgx.TxOptions{IsoLevel: pgx.ReadCommitted}, func(tx pgx.Tx) error {
			for _, s := range []string{sqlSetStatementTimeout, sqlSetLockTimeout} {
				if _, err := tx.Exec(ctx, s); err != nil {
					return fmt.Errorf("setting the transaction's timeouts: %w", err)
				}
			}
			var err error
			n, err = batch(tx)
			return err
		})
		if err != nil {
			return err
		}
		if n < j.cfg.BatchSize {
			return nil
		}
		if err := clock.Sleep(ctx, j.cfg.Pause); err != nil {
			return err
		}
	}
}

// collect reads the first column of every row of query.
func collect[T any](ctx context.Context, tx pgx.Tx, query string, cutoff time.Time, limit int) ([]T, error) {
	rows, err := tx.Query(ctx, query, cutoff, limit)
	if err != nil {
		return nil, fmt.Errorf("choosing a batch: %w", err)
	}
	keys, err := pgx.CollectRows(rows, pgx.RowTo[T])
	if err != nil {
		return nil, fmt.Errorf("choosing a batch: %w", err)
	}
	return keys, nil
}

// deleteIn runs query, whose IN list is filled with one parameter per key, and adds the rows it
// deleted to r. A list of parameters, not an array: the SQL stays portable (ADR-0023).
func deleteIn[T any](ctx context.Context, tx pgx.Tx, r *Report, table, query string, keys []T) error {
	args := make([]any, len(keys))
	placeholders := make([]string, len(keys))
	for i, k := range keys {
		args[i] = k
		placeholders[i] = "$" + strconv.Itoa(i+1)
	}
	tag, err := tx.Exec(ctx, fmt.Sprintf(query, strings.Join(placeholders, ", ")), args...)
	if err != nil {
		return fmt.Errorf("deleting from %s: %w", table, err)
	}
	r.Deleted[table] += tag.RowsAffected()
	return nil
}
