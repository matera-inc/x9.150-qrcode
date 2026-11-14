# 37. The event table is the outbox, read by CDC or by plain SQL

Date: 2026-11-14

## Status

Proposed 2026-11-14. Completes [ADR-0001](0001-events-leave-by-pull-not-push.md),
[ADR-0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md) and
[ADR-0026](0026-identifiers-and-the-event-cursor.md). Answers the plan's question Q5 (change data
capture on `payment_event`).

## Context

X9.150 tells the payment system what happened to a QR Code: a reservation was taken, a hash was
reported, the QR Code was cleared. Under ADR-0025 the Go build writes each event as a row of
`payment_event`, in the same transaction as the transition that caused it. Consumers read events
through the long-polling HTTP API of ADR-0001, and ADR-0025 left change data capture (CDC) open.

The owner expects most deployments to stream that table with CDC, most likely Debezium. Others will
read it from their own code with plain SQL. Neither is documented today, so the table's columns are
not yet a promise to anyone.

## Decision

### Communicating is inserting a row

**X9.150 communicates with the payment system only by inserting rows into `payment_event`.** The
insert is part of the transition's transaction, so an event exists if and only if its transition
committed. X9.150 never pushes, never calls the payment system, and runs no drain.

`payment_event` **is the outbox**. ADR-0025's "there is no outbox" means there is no *separate*
outbox to drain; the event table plays that role itself.

### Three ways to read it

| Reader | How | Notes |
|---|---|---|
| **CDC** (Debezium, expected) | stream inserts on `payment_event` | Take inserts only. Updates come from SQL readers marking rows; deletes come from the retention purge. |
| **Plain SQL** | the read-status columns below | One logical consumer; any number of its workers. |
| **HTTP** | `GET /pub/api/v1/events?after=…` (ADR-0001) | Kept: it is the contract Docker Hub users have today. |

### Order: `seq`, and `event_id` agrees with it

- **`seq` is the exact order.** It is gapless and equals commit order (ADR-0025).
- **`event_id` is a UUIDv7** (ADR-0026). It is minted after the event-sequence lock is taken, from a
  monotonic generator in the single active instance, so sorting by `event_id` gives the same order
  as `seq`. Events migrated from Java keep their original ids and sort before every new one.
- Use `seq` when exactness matters; use `event_id` when only a UUID is at hand (a CDC message key,
  for example).

### Read status, for plain SQL readers

`payment_event` has two columns that only a reader writes:

| Column | Type | Meaning |
|---|---|---|
| `read_status_code` | `VARCHAR(4)`, lookup `event_read_status` | `PEND` when inserted; `DONE` once the reader has processed it |
| `read_at` | `TIMESTAMP` (UTC) | when the reader marked it `DONE`; `9999-12-31 00:00:00` while pending |

The pattern, in plain SQL (PostgreSQL shown; Oracle and MySQL 8 accept the same `SKIP LOCKED`):

```sql
-- 1. claim a batch (several workers can run this at once)
SELECT * FROM payment_event
 WHERE read_status_code = 'PEND'
 ORDER BY seq
 LIMIT 100
 FOR UPDATE SKIP LOCKED;

-- 2. process the rows, then, in the same transaction:
UPDATE payment_event
   SET read_status_code = 'DONE', read_at = :now
 WHERE seq IN (:claimed);
```

- **X9.150 never updates these columns after the insert.** Only the reader does.
- **The reader's role is granted `SELECT` on the table and `UPDATE` on those two columns only**
  (column-level grant, Story 6.2). It cannot change an event's content.
- **One logical consumer.** The status is a single value per row. A second, independent consumer
  keeps its own cursor (`WHERE seq > :last ORDER BY seq`), streams with CDC, or uses the HTTP API.
- An index on `(read_status_code, seq)` keeps step 1 cheap.

### Retention is unchanged

The purge of ADR-0029 deletes events older than `events.retention` (default `P30D`), **whether or not
they were read**. A reader that falls further behind than that loses events. That is documented,
not prevented: X9.150 cannot know whether a CDC reader, which never marks rows, has seen them.

### The table is a public contract

- **Its columns, types and codes are documented for integrators** in
  `docs/EVENTS-TABLE.md` (Story 6.5 of the rewrite breakdown): one row per event, every column, the sentinels, the
  read-status pattern, a Debezium configuration, and the retention warning.
- **Changes follow the event contract's rule:** within a major `schemaVersion`, columns and codes are
  only added, never renamed, retyped or removed. A reader ignores columns it does not know.
- The column comments in the database (Story 6.1) say the same thing as the document.

## Consequences

- A payment system integrates with one SQL connection or one Debezium connector. No HTTP client, no
  broker inside X9.150 (ADR-0001 holds).
- The column names of `payment_event` can no longer change freely. That cost is accepted: it is what
  a documented outbox is.
- The read-status update is the only write X9.150 lets another party make, and the grant limits it
  to two columns.
- CDC readers see `UPDATE`s from SQL readers and `DELETE`s from the purge; the document tells them
  to filter to inserts.

## Alternatives rejected

- **A separate outbox table, drained into the event log.** Two copies of every event, and a drain
  ADR-0025 removed on purpose.
- **Only a cursor, no read-status column.** It works for one reader that keeps its own state, but
  the owner wants a reader in plain code to need nothing more than "give me what is pending".
- **Purge only events marked `DONE`.** A CDC reader never marks rows, so its events would never be
  purged.
- **Push to the payment system** (webhook or broker). Rejected by ADR-0001.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
