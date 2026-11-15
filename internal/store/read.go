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
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/matera-inc/x9.150-qrcode/internal/domain"
)

// Reader is what a script reads, inside its transition's transaction.
type Reader struct {
	tx pgx.Tx
}

// QRCode reads a QR Code by id, or ErrNotFound.
func (r *Reader) QRCode(ctx context.Context, id uuid.UUID) (QRCode, error) {
	return scanQRCode(r.tx.QueryRow(ctx, sqlSelectQRCode, id))
}

// QRCodeByLocation reads the QR Code holding a location, or ErrNotFound.
func (r *Reader) QRCodeByLocation(ctx context.Context, location uuid.UUID) (QRCode, error) {
	return scanQRCode(r.tx.QueryRow(ctx, sqlSelectQRCodeByLocation, location))
}

// Notifications reads every notification stored for a QR Code, in arrival order. Pass them to
// domain.EventBodySource for the one an event body is built from.
func (r *Reader) Notifications(ctx context.Context, id uuid.UUID) ([]domain.NotificationRecord, error) {
	return readNotifications(ctx, r.tx, id)
}

// LatestNotification reads the latest notification the QR Code accepted, as it was stored
// (notification_json): what GET shows as paymentNotification.data; nil when none was accepted.
func (r *Reader) LatestNotification(ctx context.Context, id uuid.UUID) (json.RawMessage, error) {
	return latestNotification(ctx, r.tx, id)
}

func latestNotification(ctx context.Context, tx pgx.Tx, id uuid.UUID) (json.RawMessage, error) {
	var notification []byte
	err := tx.QueryRow(ctx, sqlSelectLatestNotification, id).Scan(&notification)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("reading the latest notification of QR Code %s: %w", id, err)
	}
	return notification, nil
}

// ------------------------------------------------------------------ the read pool

// readOnly runs fn in a READ COMMITTED, read-only transaction on the read pool: isolation is set
// explicitly on every transaction (ADR-0023), and reads never queue behind writers (ADR-0025).
func (s *Store) readOnly(ctx context.Context, fn func(tx pgx.Tx) error) error {
	return pgx.BeginTxFunc(ctx, s.read, pgx.TxOptions{IsoLevel: pgx.ReadCommitted, AccessMode: pgx.ReadOnly},
		func(tx pgx.Tx) error {
			if err := setStatementTimeout(ctx, tx); err != nil {
				return err
			}
			return fn(tx)
		})
}

// Get reads a QR Code by id, on the read pool: one row. A statement cancelled after
// StatementTimeout is ErrUnavailable.
func (s *Store) Get(ctx context.Context, id uuid.UUID) (QRCode, error) {
	var q QRCode
	err := s.readOnly(ctx, func(tx pgx.Tx) error {
		var err error
		q, err = scanQRCode(tx.QueryRow(ctx, sqlSelectQRCode, id))
		return err
	})
	if err != nil {
		return QRCode{}, answer(err)
	}
	return q, nil
}

// GetWithNotification reads a QR Code by id for GET, on the read pool, and in the same transaction
// the latest notification it accepted, as it was stored (notification_json): what GET shows as
// paymentNotification.data; nil when none was accepted.
func (s *Store) GetWithNotification(ctx context.Context, id uuid.UUID) (QRCode, json.RawMessage, error) {
	var (
		q            QRCode
		notification json.RawMessage
	)
	err := s.readOnly(ctx, func(tx pgx.Tx) error {
		var err error
		if q, err = scanQRCode(tx.QueryRow(ctx, sqlSelectQRCode, id)); err != nil {
			return err
		}
		notification, err = latestNotification(ctx, tx, id)
		return err
	})
	if err != nil {
		return QRCode{}, nil, answer(err)
	}
	return q, notification, nil
}

// GetByLocation reads the QR Code holding a location, for the payload, on the read pool.
func (s *Store) GetByLocation(ctx context.Context, location uuid.UUID) (QRCode, error) {
	var q QRCode
	err := s.readOnly(ctx, func(tx pgx.Tx) error {
		var err error
		q, err = scanQRCode(tx.QueryRow(ctx, sqlSelectQRCodeByLocation, location))
		return err
	})
	if err != nil {
		return QRCode{}, answer(err)
	}
	return q, nil
}

// EventRow is a payment_event row as stored, column for column (docs/EVENTS-TABLE.md): codes and
// sentinels as they are, for the event stream to put on the wire.
type EventRow struct {
	Seq                  int64
	EventID              uuid.UUID
	TypeCode             string
	OccurredAt           time.Time
	QRCodeID             uuid.UUID
	QRCodeRevision       int
	LocationID           uuid.UUID
	Amount               int64
	TipAmount            int64
	PayerInfo            string
	Currency             string
	Network              string
	TransactionID        string
	InvoiceNumber        string
	OrderNumber          string
	Reason               string
	SchemaVersion        string
	Deviations           string
	ReceivedInStatusCode string
	ReadStatusCode       string
	ReadAt               time.Time
}

// Events reads up to limit events after seq, in seq order, on the read pool.
func (s *Store) Events(ctx context.Context, after int64, limit int) ([]EventRow, error) {
	var out []EventRow
	err := s.readOnly(ctx, func(tx pgx.Tx) error {
		rows, err := tx.Query(ctx, sqlSelectEvents, after, limit)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var e EventRow
			if err := rows.Scan(&e.Seq, &e.EventID, &e.TypeCode, &e.OccurredAt, &e.QRCodeID, &e.QRCodeRevision,
				&e.LocationID, &e.Amount, &e.TipAmount, &e.PayerInfo, &e.Currency, &e.Network, &e.TransactionID,
				&e.InvoiceNumber, &e.OrderNumber, &e.Reason, &e.SchemaVersion, &e.Deviations,
				&e.ReceivedInStatusCode, &e.ReadStatusCode, &e.ReadAt); err != nil {
				return err
			}
			e.OccurredAt, e.ReadAt = e.OccurredAt.UTC(), e.ReadAt.UTC()
			out = append(out, e)
		}
		return rows.Err()
	})
	if err != nil {
		return nil, fmt.Errorf("reading events after %d: %w", after, err)
	}
	return out, nil
}

// LegacyCursor translates a cursor the Java build handed out, a ULID in upper case, to the seq a
// read resumes strictly after (ADR-0026), on the read pool:
//
//   - the cursor of a migrated event resumes after that event;
//   - a cursor that matches no row resumes at the first migrated event whose cursor is greater, so
//     that event is the next one read;
//   - a cursor greater than every migrated event's resumes after the last migrated event, which is
//     the beginning of retained history when nothing was migrated.
func (s *Store) LegacyCursor(ctx context.Context, cursor string) (int64, error) {
	var seq int64
	err := s.readOnly(ctx, func(tx pgx.Tx) error {
		err := tx.QueryRow(ctx, sqlSelectLegacyCursor, cursor).Scan(&seq)
		if !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		err = tx.QueryRow(ctx, sqlSelectNextLegacyCursor, cursor).Scan(&seq)
		if err == nil {
			seq--
			return nil
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return err
		}
		return tx.QueryRow(ctx, sqlSelectLastLegacySeq).Scan(&seq)
	})
	if err != nil {
		return 0, fmt.Errorf("translating the cursor %s: %w", cursor, err)
	}
	return seq, nil
}

// LastSeq is the highest seq handed out, 0 before the first event, on the read pool.
func (s *Store) LastSeq(ctx context.Context) (int64, error) {
	var seq int64
	err := s.readOnly(ctx, func(tx pgx.Tx) error {
		return tx.QueryRow(ctx, sqlSelectLastSeq).Scan(&seq)
	})
	if err != nil {
		return 0, fmt.Errorf("reading the last event sequence number: %w", err)
	}
	return seq, nil
}
