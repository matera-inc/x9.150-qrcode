# 25. A transition is one transaction, and its event is in it

Date: 2026-11-13

## Status

Proposed. Part of [0022](0022-rewrite-in-go-as-a-standalone-product.md).
Once accepted, supersedes [ADR-0002](0002-atomic-transitions-with-embedded-outbox.md), and the
drain mechanics (outbox → drain → event log, single drainer) of
[ADR-0001](0001-events-leave-by-pull-not-push.md) and [ADR-0014](0014-we-transport-and-sequence-the-consumer-reconciles.md).
The pull API and the reconciliation promise stand.

## Context

On MongoDB the event and the state change could not share one atomic step, so Java has:

- an embedded outbox array;
- a drain that copies it once a second into `payment_events`;
- a lease, because two drainers would skip events;
- a merge-before-save, so a whole-document replace does not erase the outbox.

On PostgreSQL they can be one transaction. What remains is **the cursor**. A consumer pages by a
sequence, and if sequence order differs from visibility order, it can skip an event for good. A
plain `SEQUENCE`, `IDENTITY`, UUIDv7 or ULID all have that gap.

Java also retries a whole use case on a write conflict. So when the same payer's retry races
itself, the payer gets a 200, which [ADR-0021](0021-a-reservation-knows-who-holds-it.md) promises.

## Decision

**Every transition is one `pgx.BeginFunc` transaction, `READ COMMITTED` set explicitly:**

1. **Read the row, and decide in the domain** with one `now`: `effectiveStatus`, the holder, the
   rules.
2. **Write it conditionally:** `UPDATE qr_code SET … , lock_version = lock_version + 1 WHERE id = ?
   AND lock_version = ?`.
   - **0 rows means somebody changed the row since step 1.** Roll back and **re-run the whole
     script from step 1**, up to 3 times.
   - **The re-run decides the outcome,** so the same payer retrying gets ADR-0021's 200, while a
     different payer gets the domain's 409.
   - **409 for "busy" comes only after the re-runs are exhausted.**
3. **Insert any audit row** ([0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)),
   and any accepted notification row. The notification goes **before** the event, so the event is
   built from it ([0035](0035-one-event-builder-and-a-written-transition-map.md)).
4. **If the transition emits an event,** finish with two statements, sent together as one pgx
   batch:
   - `UPDATE event_sequence SET last_seq = last_seq + 1 WHERE id = 1`;
   - `INSERT INTO payment_event (seq, …) SELECT last_seq, … FROM event_sequence WHERE id = 1`.

   The transaction sees its own update and already holds the row lock. This is plain, portable
   SQL, with no `RETURNING`.

**Errors and retries** ([0023](0023-postgresql-one-schema-portable-sql.md)):

- **Retried, from step 1, up to 3 attempts in all:** `40001`, `40P01` and `55P03` (lock timeout).
- **Never retried:** `57014` (statement timeout) → 503; `23505` → as the domain maps it.
- **Exhausted retries** → 409 *busy* with the current status.

There is no outbox, no drain and no lease.

**Why this orders the cursor.** The sequence row's lock is held until commit. A second
event-producing transaction blocks on it, re-reads the committed value, and takes the next number.
A rollback rolls the counter back too. So cursor order equals visibility order, with no gaps.

**Its cost, stated:**

- Event-producing commits are serialised **including their WAL flush**.
- The lock is held only for the final batch and the commit.
- On a shared database with a synchronous standby, expect roughly 50–200 event transactions per
  second. That is orders of magnitude above the licensed ceiling (100k paid QR Codes a month).

**Guards:**

- `lock_timeout` of 2 s on event transactions, set with `SET LOCAL` inside the transaction;
- the event batch is always last;
- reads (payload, GET, events) use a separate small pool, so writers queued on the lock cannot
  starve them. **Both pools connect to the primary;** reading from a standby would break
  read-your-writes after a PATCH and is not supported;
- **a parked long-poll holds no connection.** It waits on the in-process signal, then borrows a
  connection only to query;
- a store test asserts the statement order.

**The cursor is opaque on the wire** ([0026](0026-identifiers-and-the-event-cursor.md)).
**Long-polling is in-process:** a commit that produced an event wakes parked readers, with a 1 s
poll as the backstop. This needs one active instance
([0031](0031-same-image-lighter-and-3-0-is-a-configuration-migration.md)).

## Consequences

- **The three subtlest rules of the Java build disappear:** one drainer only, cursors stamped
  before the write, and preserve the undrained outbox.
- **Events become visible at commit.**
- **A second instance would not corrupt the stream** (the lock serialises across processes). It
  would only miss long-poll wake-ups and fall back to the 1 s poll.
- **Change data capture remains open:** a consumer can still publish `payment_event` to a broker
  with CDC, without code here changing.

## Alternatives rejected

- **Outbox plus relay.** The relay exists only to restore order, and the lock does that inside the
  transaction.
- **A watermark over an identity column.** It needs non-portable snapshot functions.
- **UUIDv7 or ULID as the cursor.** The same visibility gap.
- **SERIALIZABLE.** It retries unrelated conflicts and still doesn't order the cursor.
- **"0 rows ⇒ 409" without a re-run.** It breaks ADR-0021's 200 for a payer retrying its own
  announcement.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
