# 23. PostgreSQL, one schema, portable SQL

Date: 2026-11-13

## Status

Accepted 2026-11-13. Part of [0022](0022-rewrite-in-go-as-a-standalone-product.md).
Supersedes [ADR-0013](0013-retry-what-mongodb-says-to-retry.md).

## Context

MongoDB was chosen when the payment request was treated as one JSON document. In practice:

- **We read it whole, by id or location.** Nothing queries inside it.
- **Create and patch write two documents** (the new code and the location's previous holder), so a
  replica set is mandatory even on a laptop.
- **We built our own retry for transient write conflicts** (ADR-0013).
- **A lease document exists** only to serialise the event drain.
- **The 30-second TTL index deletes a QR Code** half a minute after `validUntil`.

## Decision

**PostgreSQL 16 or newer, one schema, portable SQL.**

- **Engine:** PostgreSQL **≥ 16**. CI runs the suite on 16 and on the newest major.
- **One schema,** default `x9_qrcode`. The name is configurable, so **SQL is never
  schema-qualified**; the connection's `search_path` selects the schema
  ([0032](0032-x9150-can-live-in-the-banks-own-database.md)).
- **Liquibase changelog** (`db/changelog/`): an initial changelog, then numbered increments.
  Applied changesets are never edited, and every table and column carries a `COMMENT`. It runs
  before the binary.
- **Portable dialect.** The schema must stay movable to other relational engines, so the following
  are not used:
  - **Types:** `JSONB` and JSON operators, `ENUM`, arrays, `TIMESTAMPTZ`, unbounded `TEXT`,
    `SERIAL`.
  - **Server-side features:** stored procedures, triggers with logic, `LISTEN/NOTIFY`, advisory
    locks.
  - **DML:** `ON CONFLICT DO UPDATE`; database-side clocks in business logic.
  - **Instead:**
    - status-like values: lookup codes;
    - JSON stored and read whole: `JSON`;
    - times: `TIMESTAMP` holding UTC, computed in the application;
    - "not yet": a sentinel instead of `NULL`.
  - **Partial indexes** are allowed for queue-like scans, with the predicate repeated literally in
    the query.
- **Recorded exceptions.** A statement that needs a non-portable feature carries a comment naming
  the feature, the reason and this ADR. There are none today.
- **Isolation is set explicitly on every transaction:** `READ COMMITTED`. It is never inherited,
  because a bank's database may default to something stricter ([0032](0032-x9150-can-live-in-the-banks-own-database.md)).
  Correctness comes from conditional `UPDATE`s whose affected-row count is checked.
- **Retry:** an operation is retried, outside the transaction and a bounded number of times, only on:
  - SQLSTATE `40001` (serialization failure);
  - `40P01` (deadlock);
  - `55P03` (lock timeout).

  Statement timeouts (`57014`) and unique violations (`23505`) are answered, never retried
  ([0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md)).

## Consequences

- **The replica-set requirement disappears,** along with the MongoDB version pin and its setup
  container.
- **Expiry stops being a deletion.** Retention is an explicit decision
  ([0029](0029-time-is-applied-on-read-and-retention-is-the-only-job.md)).
- **The cost is a stricter dialect than plain PostgreSQL:** no JSON queries, and upserts written as
  update-then-insert. For a few small rows per payment, read by key, this is not felt.

## Alternatives rejected

- **JSONB holding the Mongo document.** Fastest to port, but nothing is enforceable and history is
  still lost.
- **Two schemas** (QR Codes and events). They would split one commit for no isolation gain.
- **SQLite.** No concurrent writers, and nothing Matera runs.
- **SERIALIZABLE everywhere.** It spends retries on conflicts that the conditional updates already
  resolve.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
