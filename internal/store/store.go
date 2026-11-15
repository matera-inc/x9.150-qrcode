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
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/matera-inc/x9.150-qrcode/internal/domain"
)

// Attempts is how many times a transition's script runs before the answer is "busy" (ADR-0025).
const Attempts = 3

// EventLockTimeout bounds how long an event transaction waits for a lock (ADR-0025).
const EventLockTimeout = "2s"

// What a transition can fail with, besides a refusal of the domain (which is an answer, carried in
// Result.Decision.Refusal, not an error).
var (
	// ErrNotFound: no QR Code has that id or location.
	ErrNotFound = errors.New("QR Code not found")
	// ErrBusy: the row kept changing under every attempt. A 409 with the current status; the
	// error also unwraps to a *domain.Refusal of KindConflict that carries it.
	ErrBusy = errors.New("the QR Code is busy")
	// ErrUnavailable: a statement ran out of time (SQLSTATE 57014). A 503; never re-run.
	ErrUnavailable = errors.New("the database did not answer in time")
	// ErrDuplicate: a unique constraint refused the write (SQLSTATE 23505); see DuplicateError.
	ErrDuplicate = errors.New("a unique constraint refused the write")
	// ErrInvalidChange: a script returned a Change this store must not apply. A programming error.
	ErrInvalidChange = errors.New("invalid change")
)

// DuplicateError is a 23505, never re-run (ADR-0023). The caller answers it as its rule says: on
// location_id it is a refused rebind, a 400 (ADR-0024).
type DuplicateError struct {
	// Constraint is the constraint's name, e.g. ux_qr_code_location_id.
	Constraint string
}

func (e *DuplicateError) Error() string { return ErrDuplicate.Error() + ": " + e.Constraint }

// Is matches ErrDuplicate.
func (e *DuplicateError) Is(target error) bool { return target == ErrDuplicate }

// busyError is ErrBusy carrying the 409 the caller answers with.
type busyError struct{ refusal *domain.Refusal }

func (e *busyError) Error() string        { return e.refusal.Message }
func (e *busyError) Is(target error) bool { return target == ErrBusy }
func (e *busyError) Unwrap() error        { return e.refusal }

func busy(current domain.Status) error {
	return &busyError{refusal: &domain.Refusal{
		Kind:          domain.KindConflict,
		Message:       "The QR Code is being changed by another request. Try again.",
		CurrentStatus: current,
	}}
}

// Clock is the business clock (internal/clock): one "now" per attempt.
type Clock interface {
	Now() time.Time
}

// QRCode is a qr_code row: the domain's row and the EMV content issued for its location.
type QRCode struct {
	domain.Row
	EMVContent string
}

// Evidence is what qr_code_notification keeps of an accepted notification besides the columns
// the domain decides on: the parsed notification exactly as accepted, and the compact JWS.
type Evidence struct {
	JSON       json.RawMessage
	JWSCompact string
}

// Audit is a PATCH's audit row (ADR-0024). Its revision is the row's revision as read, before the
// PATCH moved it; its time is the transition's "now".
type Audit struct {
	// ChangedFields are the top-level tags the PATCH changed.
	ChangedFields []string
	// PreviousValues is a JSON object of those tags with their previous values.
	PreviousValues json.RawMessage
}

// Release is a location holder giving its location up to the transition's row (X9-MAP-050).
type Release struct {
	// Holder is the holder as read.
	Holder QRCode
	// Decision is domain.ReleaseLocation's, accepted: the holder at its fresh location.
	Decision domain.Decision
	// EMVContent is the EMV string of the holder's fresh location.
	EMVContent string
}

// Change is what one attempt of a transition writes. Before is nil for a create.
type Change struct {
	Release  *Release
	Before   *QRCode
	Decision domain.Decision
	// EMVContent is the EMV string of Decision.Row's location when the location is new (a create)
	// or moves; "" otherwise. It is never recomputed for a location that does not move.
	EMVContent string
	Audit      *Audit
	// Evidence goes with Decision.Notification, and is required when there is one.
	Evidence *Evidence
}

// Event is a committed payment event and the id it was given.
type Event struct {
	ID uuid.UUID
	domain.Event
}

// Result is what a transition committed.
type Result struct {
	// Decision is the domain's decision, its refusal included.
	Decision domain.Decision
	// QRCode is the row as committed, or as read when nothing was written; zero when the script
	// read none.
	QRCode QRCode
	// Events are the events inserted, in seq order.
	Events []Event
	// Attempts is how many times the script ran.
	Attempts int
}

// Script is one attempt of a transition: it reads through r and decides at now. Returning an
// error rolls the attempt back; ErrNotFound is passed on as it is.
type Script func(ctx context.Context, r *Reader, now time.Time) (Change, error)

// Store runs transitions on the write pool and answers reads on the read pool (ADR-0025).
type Store struct {
	write, read *pgxpool.Pool
	clock       Clock
	signal      *signal
	// gate is held from minting an event id until the event batch has taken the sequence row's
	// lock, so ids are minted in lock order.
	gate        chan struct{}
	lockTimeout string

	// Test hooks, nil in production: beforeWrite runs after the script and before the first
	// write of each attempt; afterEvents runs after the event batch, and an error from it rolls
	// the attempt back.
	beforeWrite func(ctx context.Context, attempt int, change Change) error
	afterEvents func(ctx context.Context) error
}

// New returns a store over the two pools of internal/database.
func New(write, read *pgxpool.Pool, clk Clock) *Store {
	return &Store{
		write: write, read: read, clock: clk,
		signal:      newSignal(),
		gate:        make(chan struct{}, 1),
		lockTimeout: EventLockTimeout,
	}
}

// EventCommitted returns a channel closed by the next commit that inserted an event. A parked
// event reader waits on it (Story 10.1); a rollback never closes it. Take the channel before
// reading the events, so that a commit between the read and the wait is not missed.
func (s *Store) EventCommitted() <-chan struct{} { return s.signal.wait() }

// Transition runs the common script: read the QR Code and its stored notifications, decide with
// decide, and write what it decided. last is the notification an event body is built from, chosen
// by domain.EventBodySource. evidence is stored with an accepted notification; nil when the
// transition has none (the status-update API).
func (s *Store) Transition(ctx context.Context, id uuid.UUID, evidence *Evidence,
	decide func(row domain.Row, last *domain.NotificationRecord, now time.Time) (domain.Decision, error),
) (Result, error) {
	return s.Run(ctx, func(ctx context.Context, r *Reader, now time.Time) (Change, error) {
		before, err := r.QRCode(ctx, id)
		if err != nil {
			return Change{}, err
		}
		stored, err := r.Notifications(ctx, id)
		if err != nil {
			return Change{}, err
		}
		d, err := decide(before.Row, domain.EventBodySource(stored), now)
		if err != nil {
			return Change{}, err
		}
		return Change{Before: &before, Decision: d, Evidence: evidence}, nil
	})
}

// Run runs script as one transition (ADR-0025): up to Attempts times, each from its reads, until
// one commits. A refusal is an answer, in Result.Decision.Refusal; what was decided with it (a
// holder's refused retry releases the QR Code) is committed.
func (s *Store) Run(ctx context.Context, script Script) (Result, error) {
	var current domain.Status
	for attempt := 1; attempt <= Attempts; attempt++ {
		res, seen, err := s.attempt(ctx, attempt, script)
		if seen != "" {
			current = seen
		}
		if err == nil {
			res.Attempts = attempt
			return res, nil
		}
		if !rerun(err) {
			return Result{}, answer(err)
		}
	}
	return Result{}, busy(current)
}

// attempt runs the script once, in one READ COMMITTED transaction. seen is the effective status of
// the row the script decided on, for the busy answer.
func (s *Store) attempt(ctx context.Context, n int, script Script) (res Result, seen domain.Status, err error) {
	err = pgx.BeginTxFunc(ctx, s.write, pgx.TxOptions{IsoLevel: pgx.ReadCommitted}, func(tx pgx.Tx) error {
		now := s.clock.Now().UTC()
		change, err := script(ctx, &Reader{tx: tx}, now)
		if err != nil {
			return err
		}
		if change.Before != nil {
			seen = domain.EffectiveStatus(change.Before.Row, now)
		}
		if err := change.validate(); err != nil {
			return err
		}
		if s.beforeWrite != nil {
			if err := s.beforeWrite(ctx, n, change); err != nil {
				return err
			}
		}
		res, err = s.apply(ctx, tx, change, now)
		return err
	})
	if err != nil {
		return Result{}, seen, err
	}
	if len(res.Events) > 0 {
		s.signal.notify() // after the commit, never on a rollback (S9.5-AC7)
	}
	return res, seen, nil
}

// apply writes a change in ADR-0025's order: the holder's release, the row, the audit row, the
// notification, and last the events.
func (s *Store) apply(ctx context.Context, tx pgx.Tx, c Change, now time.Time) (Result, error) {
	d := c.Decision
	res := Result{Decision: d}
	if c.Before != nil {
		res.QRCode = *c.Before
	}

	if len(d.Events) > 0 {
		if _, err := tx.Exec(ctx, lockTimeoutSQL(s.lockTimeout)); err != nil {
			return Result{}, fmt.Errorf("setting the lock timeout: %w", err)
		}
	}

	if r := c.Release; r != nil {
		if err := writeRow(ctx, tx, r.Holder, r.Decision.Row, r.EMVContent); err != nil {
			return Result{}, err
		}
	}

	if d.Write {
		row := d.Row
		if c.Before == nil {
			if err := insertRow(ctx, tx, row, c.EMVContent); err != nil {
				return Result{}, err
			}
			res.QRCode = QRCode{Row: row, EMVContent: c.EMVContent}
		} else {
			if err := writeRow(ctx, tx, *c.Before, row, c.EMVContent); err != nil {
				return Result{}, err
			}
			row.LockVersion = c.Before.LockVersion + 1
			emv := c.Before.EMVContent
			if c.EMVContent != "" {
				emv = c.EMVContent
			}
			res.QRCode = QRCode{Row: row, EMVContent: emv}
		}
	}

	if c.Audit != nil {
		if err := insertAudit(ctx, tx, *c.Before, c.Audit, now); err != nil {
			return Result{}, err
		}
	}
	if d.Notification != nil {
		if err := insertNotification(ctx, tx, d.Row.ID, d.Notification, c.Evidence); err != nil {
			return Result{}, err
		}
	}

	if len(d.Events) > 0 {
		events, err := s.insertEvents(ctx, tx, d.Events)
		if err != nil {
			return Result{}, err
		}
		res.Events = events
		if s.afterEvents != nil {
			if err := s.afterEvents(ctx); err != nil {
				return Result{}, err
			}
		}
	}
	return res, nil
}

// insertEvents sends the event batch: for each event, the sequence UPDATE and the INSERT … SELECT
// last_seq. The ids are minted under the gate, which is released only once the batch has run, so
// the sequence row's lock is taken in the order the ids were minted (ADR-0037).
func (s *Store) insertEvents(ctx context.Context, tx pgx.Tx, events []domain.Event) ([]Event, error) {
	select {
	case s.gate <- struct{}{}:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	defer func() { <-s.gate }()

	out := make([]Event, len(events))
	b := &pgx.Batch{}
	for i, e := range events {
		id, err := newEventID()
		if err != nil {
			return nil, err
		}
		out[i] = Event{ID: id, Event: e}
		b.Queue(sqlNextSeq)
		b.Queue(sqlInsertEvent, eventArgs(id, e)...)
	}
	br := tx.SendBatch(ctx, b)
	for range b.Len() {
		if _, err := br.Exec(); err != nil {
			_ = br.Close()
			return nil, fmt.Errorf("inserting the events: %w", err)
		}
	}
	if err := br.Close(); err != nil {
		return nil, fmt.Errorf("inserting the events: %w", err)
	}
	return out, nil
}

// validate refuses a change this store must not apply: a programming error, never an answer.
func (c Change) validate() error {
	d := c.Decision
	invalid := func(why string) error { return fmt.Errorf("%w: %s", ErrInvalidChange, why) }
	if d.Refusal != nil && (c.Release != nil || c.Audit != nil) {
		return invalid("a refused transition releases no location and writes no audit row")
	}
	if r := c.Release; r != nil {
		if r.Decision.Refusal != nil || !r.Decision.Write {
			return invalid("a refused location release is not written")
		}
		if r.Decision.Row.ID != r.Holder.ID || r.Decision.Row.LocationID == r.Holder.LocationID || r.EMVContent == "" {
			return invalid("a release moves the holder to a fresh location, with its EMV content")
		}
	}
	if d.Write && c.Before == nil {
		if c.EMVContent == "" {
			return invalid("a new QR Code is written with its EMV content")
		}
	}
	if d.Write && c.Before != nil {
		if d.Row.ID != c.Before.ID {
			return invalid("the decided row is not the row read")
		}
		moved := d.Row.LocationID != c.Before.LocationID
		if moved != (c.EMVContent != "") {
			return invalid("emv_content is written with location_id, and only when the location moves")
		}
	}
	if !d.Write && c.EMVContent != "" {
		return invalid("EMV content without a write")
	}
	if c.Audit != nil && (c.Before == nil || !d.Write) {
		return invalid("an audit row goes with a written update")
	}
	if d.Notification != nil && (c.Evidence == nil || len(c.Evidence.JSON) == 0) {
		return invalid("an accepted notification is stored with its evidence")
	}
	return nil
}

// rerun reports whether an attempt's failure re-runs the script: a row changed since it was read,
// a serialization failure, a deadlock or a lock timeout (ADR-0023, ADR-0025).
func rerun(err error) bool {
	if errors.Is(err, errStale) {
		return true
	}
	if pgErr, ok := errors.AsType[*pgconn.PgError](err); ok {
		switch pgErr.Code {
		case "40001", "40P01", "55P03":
			return true
		}
	}
	return false
}

// answer turns a failure that is not re-run into what the caller answers.
func answer(err error) error {
	if pgErr, ok := errors.AsType[*pgconn.PgError](err); ok {
		switch pgErr.Code {
		case "57014":
			return fmt.Errorf("%w: %w", ErrUnavailable, err)
		case "23505":
			return &DuplicateError{Constraint: pgErr.ConstraintName}
		}
	}
	return err
}
