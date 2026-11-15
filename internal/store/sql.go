// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/matera-inc/x9.150-qrcode/internal/domain"
)

// Every statement of this package. None is schema-qualified, none uses RETURNING, and none reads
// the database's clock (ADR-0023): every time is computed in the application and bound in UTC.

const qrCodeColumns = `id, location_id, emv_content, valid_until, status_code, revision, lock_version,
 reserved_until, reserved_by_payer_info, reserved_by_signer_subject, hash_registered, end_to_end_id,
 payment_network, request_json, created_at, revised_at`

const (
	sqlSelectQRCode           = `SELECT ` + qrCodeColumns + ` FROM qr_code WHERE id = $1`
	sqlSelectQRCodeByLocation = `SELECT ` + qrCodeColumns + ` FROM qr_code WHERE location_id = $1`

	sqlInsertQRCode = `INSERT INTO qr_code (` + qrCodeColumns + `)
 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16)`

	// sqlSelectNotifications reads every notification of a QR Code in arrival order (its UUIDv7
	// id). Which one an event body uses is domain.EventBodySource's choice, not this query's.
	sqlSelectNotifications = `SELECT received_at, received_in_status_code, deviations, phase, network,
 currency, amount, tip_amount, transaction_id, payer_info, signer_subject
 FROM qr_code_notification WHERE qr_code_id = $1 ORDER BY id`

	sqlInsertNotification = `INSERT INTO qr_code_notification (id, qr_code_id, received_at,
 received_in_status_code, deviations, phase, network, currency, amount, tip_amount, transaction_id,
 payer_info, signer_subject, notification_json, jws_compact)
 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15)`

	sqlInsertAudit = `INSERT INTO qr_code_audit_log (qr_code_id, revision, changed_at, changed_fields,
 previous_values) VALUES ($1, $2, $3, $4, $5)`

	// sqlNextSeq takes the sequence row's lock, held until commit (ADR-0025).
	sqlNextSeq = `UPDATE event_sequence SET last_seq = last_seq + 1 WHERE id = 1`

	// sqlInsertEvent numbers the event with the value sqlNextSeq just wrote, which this transaction
	// sees. Parameters in a SELECT list have no type to infer, hence the casts. read_status_code and
	// read_at are written once, here, and X9.150 never updates them (ADR-0037).
	sqlInsertEvent = `INSERT INTO payment_event (seq, event_id, type_code, occurred_at, qr_code_id,
 qr_code_revision, location_id, amount, tip_amount, payer_info, currency, network, transaction_id,
 invoice_number, order_number, reason, schema_version, deviations, received_in_status_code,
 read_status_code, read_at)
 SELECT last_seq, CAST($1 AS UUID), CAST($2 AS VARCHAR(4)), CAST($3 AS TIMESTAMP), CAST($4 AS UUID),
 CAST($5 AS INT), CAST($6 AS UUID), CAST($7 AS BIGINT), CAST($8 AS BIGINT), CAST($9 AS VARCHAR(254)),
 CAST($10 AS VARCHAR(32)), CAST($11 AS VARCHAR(64)), CAST($12 AS VARCHAR(255)),
 CAST($13 AS VARCHAR(20)), CAST($14 AS VARCHAR(20)), CAST($15 AS VARCHAR(255)),
 CAST($16 AS VARCHAR(8)), CAST($17 AS VARCHAR(255)), CAST($18 AS VARCHAR(4)),
 'PEND', TIMESTAMP '9999-12-31 00:00:00'
 FROM event_sequence WHERE id = 1`

	sqlSelectEvents = `SELECT seq, event_id, type_code, occurred_at, qr_code_id, qr_code_revision,
 location_id, amount, tip_amount, payer_info, currency, network, transaction_id, invoice_number,
 order_number, reason, schema_version, deviations, received_in_status_code, read_status_code, read_at
 FROM payment_event WHERE seq > $1 ORDER BY seq FETCH FIRST $2 ROWS ONLY`

	sqlSelectLastSeq = `SELECT last_seq FROM event_sequence WHERE id = 1`

	// The translation of a Java build's ULID cursor (ADR-0026), in three steps: the cursor's own
	// row; else the first migrated event whose cursor is greater; else after the last migrated
	// event. A ULID is upper-case Crockford base32 (digits, then upper-case letters), which sorts
	// the same way in every collation, so the comparison needs no COLLATE.
	sqlSelectLegacyCursor     = `SELECT seq FROM payment_event_legacy_cursor WHERE legacy_cursor = $1`
	sqlSelectNextLegacyCursor = `SELECT seq FROM payment_event_legacy_cursor WHERE legacy_cursor > $1
 ORDER BY legacy_cursor FETCH FIRST 1 ROWS ONLY`
	sqlSelectLastLegacySeq = `SELECT COALESCE(MAX(seq), 0) FROM payment_event_legacy_cursor`
)

// sqlStatementTimeout sets StatementTimeout for the current transaction only (is_local true).
const sqlStatementTimeout = `SELECT set_config('statement_timeout', '` + StatementTimeout + `', true)`

func setStatementTimeout(ctx context.Context, tx pgx.Tx) error {
	if _, err := tx.Exec(ctx, sqlStatementTimeout); err != nil {
		return fmt.Errorf("setting the statement timeout: %w", err)
	}
	return nil
}

// lockTimeoutSQL is ADR-0025's guard on an event transaction. SET takes no parameter; timeout is
// a constant of this package.
func lockTimeoutSQL(timeout string) string {
	return "SET LOCAL lock_timeout = '" + timeout + "'"
}

// The two forms of the conditional UPDATE: the lifecycle columns, and those plus what a PATCH or a
// rebind changes. location_id and emv_content are written together, and only when the location
// moves (ADR-0024).
const (
	setLifecycle = `status_code = $3, reserved_until = $4, reserved_by_payer_info = $5,
 reserved_by_signer_subject = $6, hash_registered = $7, end_to_end_id = $8, payment_network = $9,
 revision = $10, revised_at = $11, lock_version = lock_version + 1`
	setContent  = `, request_json = $12, valid_until = $13`
	setLocation = `, location_id = $14, emv_content = $15`
)

func updateSQL(content, location bool) string {
	var b strings.Builder
	b.WriteString("UPDATE qr_code SET ")
	b.WriteString(setLifecycle)
	switch {
	case location:
		// The location's placeholders come after the content's, so both are always bound.
		b.WriteString(setContent)
		b.WriteString(setLocation)
	case content:
		b.WriteString(setContent)
	}
	b.WriteString(" WHERE id = $1 AND lock_version = $2")
	return b.String()
}

// errStale is a conditional UPDATE that matched no row: someone changed the row since it was read.
var errStale = errors.New("the row changed since it was read")

// writeRow applies a decided row to the row as read, guarded by its lock_version. emv is the EMV
// content of a new location, "" when the location does not move.
func writeRow(ctx context.Context, tx pgx.Tx, before QRCode, row domain.Row, emv string) error {
	content := !jsonEqual(before.RequestJSON, row.RequestJSON) || !before.ValidUntil.Equal(row.ValidUntil)
	location := emv != ""
	args := []any{
		row.ID, before.LockVersion,
		row.Status.Code(), row.ReservedUntil.UTC(), row.ReservedBy.PayerInfo, row.ReservedBy.SignerSubject,
		row.HashRegistered, row.EndToEndID, row.PaymentNetwork, row.Revision, row.RevisedAt.UTC(),
	}
	if content || location {
		args = append(args, []byte(row.RequestJSON), row.ValidUntil.UTC())
	}
	if location {
		args = append(args, row.LocationID, emv)
	}
	tag, err := tx.Exec(ctx, updateSQL(content, location), args...)
	if err != nil {
		return fmt.Errorf("updating QR Code %s: %w", row.ID, err)
	}
	if tag.RowsAffected() == 0 {
		return errStale
	}
	return nil
}

func insertRow(ctx context.Context, tx pgx.Tx, row domain.Row, emv string) error {
	_, err := tx.Exec(ctx, sqlInsertQRCode,
		row.ID, row.LocationID, emv, row.ValidUntil.UTC(), row.Status.Code(), row.Revision, row.LockVersion,
		row.ReservedUntil.UTC(), row.ReservedBy.PayerInfo, row.ReservedBy.SignerSubject, row.HashRegistered,
		row.EndToEndID, row.PaymentNetwork, []byte(row.RequestJSON), row.CreatedAt.UTC(), row.RevisedAt.UTC())
	if err != nil {
		return fmt.Errorf("inserting QR Code %s: %w", row.ID, err)
	}
	return nil
}

func jsonEqual(a, b json.RawMessage) bool {
	return string(a) == string(b)
}

func scanQRCode(row pgx.Row) (QRCode, error) {
	var (
		q      QRCode
		status string
		raw    []byte
	)
	err := row.Scan(&q.ID, &q.LocationID, &q.EMVContent, &q.ValidUntil, &status, &q.Revision, &q.LockVersion,
		&q.ReservedUntil, &q.ReservedBy.PayerInfo, &q.ReservedBy.SignerSubject, &q.HashRegistered, &q.EndToEndID,
		&q.PaymentNetwork, &raw, &q.CreatedAt, &q.RevisedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return QRCode{}, ErrNotFound
	}
	if err != nil {
		return QRCode{}, fmt.Errorf("reading a QR Code: %w", err)
	}
	if q.Status, err = domain.StatusFromCode(status); err != nil {
		return QRCode{}, err
	}
	q.RequestJSON = json.RawMessage(raw)
	q.ValidUntil, q.ReservedUntil = q.ValidUntil.UTC(), q.ReservedUntil.UTC()
	q.CreatedAt, q.RevisedAt = q.CreatedAt.UTC(), q.RevisedAt.UTC()
	return q, nil
}

func readNotifications(ctx context.Context, tx pgx.Tx, id uuid.UUID) ([]domain.NotificationRecord, error) {
	rows, err := tx.Query(ctx, sqlSelectNotifications, id)
	if err != nil {
		return nil, fmt.Errorf("reading the notifications of QR Code %s: %w", id, err)
	}
	defer rows.Close()
	var out []domain.NotificationRecord
	for rows.Next() {
		var (
			r                     domain.NotificationRecord
			receivedIn, phase     string
			deviations, payerInfo string
			tip                   int64
		)
		n := &r.Notification
		if err := rows.Scan(&r.ReceivedAt, &receivedIn, &deviations, &phase, &n.Network, &n.Currency,
			&n.Amount, &tip, &n.TransactionID, &payerInfo, &n.SignerSubject); err != nil {
			return nil, fmt.Errorf("reading a notification of QR Code %s: %w", id, err)
		}
		r.ReceivedAt = r.ReceivedAt.UTC()
		r.Phase = domain.Phase(phase)
		if r.Receipt.ReceivedIn, err = domain.StatusFromCode(receivedIn); err != nil {
			return nil, err
		}
		if r.Receipt.Deviations, err = domain.ParseDeviations(deviations); err != nil {
			return nil, err
		}
		if tip != absentAmount {
			n.TipAmount = &tip
		}
		if payerInfo != "" {
			n.PayerInfo = &payerInfo
		}
		out = append(out, r)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("reading the notifications of QR Code %s: %w", id, err)
	}
	return out, nil
}

// absentAmount is the sentinel of an amount column whose value is absent.
const absentAmount = -1

func insertNotification(ctx context.Context, tx pgx.Tx, qrCodeID uuid.UUID, r *domain.NotificationRecord, ev *Evidence) error {
	id, err := uuid.NewV7()
	if err != nil {
		return fmt.Errorf("minting a notification id: %w", err)
	}
	n := r.Notification
	tip := int64(absentAmount)
	if n.TipAmount != nil {
		tip = *n.TipAmount
	}
	payerInfo := ""
	if n.PayerInfo != nil {
		payerInfo = *n.PayerInfo
	}
	_, err = tx.Exec(ctx, sqlInsertNotification,
		id, qrCodeID, r.ReceivedAt.UTC(), r.Receipt.ReceivedIn.Code(), domain.FormatDeviations(r.Receipt.Deviations),
		string(r.Phase), n.Network, n.Currency, n.Amount, tip, n.TransactionID, payerInfo, n.SignerSubject,
		[]byte(ev.JSON), ev.JWSCompact)
	if err != nil {
		return fmt.Errorf("storing the notification of QR Code %s: %w", qrCodeID, err)
	}
	return nil
}

func insertAudit(ctx context.Context, tx pgx.Tx, before QRCode, a *Audit, now time.Time) error {
	_, err := tx.Exec(ctx, sqlInsertAudit,
		before.ID, before.Revision, now.UTC(), strings.Join(a.ChangedFields, ","), []byte(a.PreviousValues))
	if err != nil {
		return fmt.Errorf("writing the audit row of QR Code %s revision %d: %w", before.ID, before.Revision, err)
	}
	return nil
}

// eventArgs binds an event's columns, sentinels for what is absent.
func eventArgs(id uuid.UUID, e domain.Event) []any {
	received := ""
	if e.ReceivedInStatus != nil {
		received = e.ReceivedInStatus.Code()
	}
	return []any{
		id, e.Type.Code(), e.OccurredAt.UTC(), e.QRCodeID, e.QRCodeRevision, e.LocationID,
		amountOrAbsent(e.Amount), amountOrAbsent(e.TipAmount), textOrEmpty(e.PayerInfo), textOrEmpty(e.Currency),
		textOrEmpty(e.Network), textOrEmpty(e.TransactionID), textOrEmpty(e.InvoiceNumber),
		textOrEmpty(e.OrderNumber), textOrEmpty(e.Reason), e.SchemaVersion, domain.FormatDeviations(e.Deviations),
		received,
	}
}

func amountOrAbsent(p *int64) int64 {
	if p == nil {
		return absentAmount
	}
	return *p
}

func textOrEmpty(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}
