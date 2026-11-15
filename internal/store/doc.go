// Copyright © 2026 Matera Systems, Inc.
// Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
// Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
// based on it — is governed by that license (see the "Derivative Work" definition and Annex A).

// Package store keeps QR Codes, their notifications and their payment events in PostgreSQL, and
// runs every transition as ADR-0025 writes it: one transaction, and its event in it.
//
// A transition is a [Script]: it reads what it needs through a [Reader], decides in
// internal/domain with one "now", and returns a [Change]. [Store.Run] runs it in one
// READ COMMITTED transaction on the write pool and applies the change in a fixed order:
//
//  1. a location holder's release (ADR-0024), conditional on its lock_version;
//  2. the row: an INSERT for a create, otherwise UPDATE … WHERE id = ? AND lock_version = ?;
//  3. the audit row, then the accepted notification;
//  4. last, the events, as one batch of UPDATE event_sequence and INSERT INTO payment_event …
//     SELECT last_seq, under SET LOCAL lock_timeout = '2s'.
//
// A conditional UPDATE that matches no row, and SQLSTATE 40001, 40P01 and 55P03, roll back and re-run
// the whole script from its reads, three attempts in all: the re-run decides, so a payer retrying
// its own pre-commit gets the domain's 200 and another payer its 409. Exhausted re-runs are
// [ErrBusy], a 409 with the current status; 57014 is [ErrUnavailable], a 503; 23505 is a
// [*DuplicateError], answered as the caller's rule says (a refused rebind, for a location).
//
// The sequence row's lock is held until commit, so a second event-producing transaction takes the
// next number only once the first has committed or rolled back: seq is gapless and in commit order
// (ADR-0025). Each event_id is a UUIDv7 from one monotonic generator, minted under an in-process
// gate that is held until the batch has taken that lock, so within this process ids are minted in
// seq order. That is tidiness, not a promise: another instance on the same database mints from its
// own clock, so across instances event_id order is not seq order, and seq is the only order
// (ADR-0037, amended 2026-11-15). A commit that inserted an event closes the channel [Store.EventCommitted] returned,
// waking parked event readers; a rollback never does.
//
// Reads that answer a request (GET, the payload, the events) run on the read pool, each in its own
// READ COMMITTED transaction. SQL is portable (ADR-0023) and never schema-qualified: the
// connection's search_path selects the schema (ADR-0032).
package store
