# 32. X9.150 can live in the bank's own database

Date: 2026-11-13

## Status

Accepted 2026-11-13. Part of [0022](0022-rewrite-in-go-as-a-standalone-product.md).
Sharpens [0023](0023-postgresql-one-schema-portable-sql.md).

## Context

For a small bank, a second database engine is a real cost: patching, backup, monitoring, security
review, and DBAs who know it. A large bank will give X9.150 its own database anyway. A small one
would rather give it a schema in the PostgreSQL it already runs.

The owner's position: the Go build is worth having **even if Java + MongoDB benchmarks faster**,
because of memory and because it avoids a second database.

As a guest in somebody else's production database, X9.150 must not:

- take an unbounded share of connections;
- hold long transactions;
- need privileges a DBA refuses;
- install extensions;
- assume session state or isolation defaults that the host may not provide.

## Decision

**The Go build runs in one schema of the bank's existing PostgreSQL (≥ 16), as a well-mannered
guest.** A dedicated database stays fully supported: the same code with a different connection
string.

| Obligation | Rule |
|---|---|
| Namespace | one schema, default `x9_qrcode`, name configurable. SQL is unqualified. The schema is selected by **the application role's own default** (`ALTER ROLE … SET search_path`, written by the changelog), not by a connection parameter that a pooler may drop. At startup the service **checks `current_schema()`** equals the configured schema, and that the changelog's latest applied id is the one the binary expects; otherwise it refuses to start. Nothing outside the schema, nothing in `public` |
| Roles | **owner** (migrations only, through Liquibase), **application** (DML on its own tables, nothing else), **read-only** (`SELECT`, for auditors and the black-box suite's below-HTTP checks). The changelog grants privileges explicitly and sets `ALTER DEFAULT PRIVILEGES`, so new tables reach both runtime roles |
| DBA-run migrations | for banks that will not hand over the owner role, `liquibase update-sql` produces the SQL for the DBA to review and run. The application never needs DDL rights |
| Connections | two small pools: writes (default 8) and reads (default 4) ([0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md)); idle connections released after 5 min; `application_name=x9-qrcode` |
| Session independence | every transaction sets `READ COMMITTED` itself, and its timeouts with `SET LOCAL` / `set_config(…, true)` (`statement_timeout` 30 s; `lock_timeout` 2 s on event transactions). Reads run in short explicit transactions for the same reason. Nothing relies on session state, so **PgBouncer in transaction mode (≥ 1.21)** works. Behind an older PgBouncer, or one with `max_prepared_statements=0`, set pgx's `default_query_exec_mode=exec` |
| TLS | `sslmode` and the root CA are configurable; `verify-full` is recommended and documented. Credentials come from a mounted file, so rotation is a file change and a reconnect |
| Transactions | no transaction spans an outbound HTTP call (payee delivery, certificate or CRL fetch) |
| Locks | the only intentionally contended lock is the one-row `event_sequence`, held for the final batch of an event commit ([0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md)) |
| Housekeeping | retention deletes in paced batches ([0029](0029-time-is-applied-on-read-and-retention-is-the-only-job.md)) |
| No coupling | no foreign keys into, views over, or reads of any other schema. The bank integrates over HTTP and the event stream |
| Footprint stated | the README states disk per thousand QR Codes, the connection ceiling, and the measured lock throughput, so a DBA can say yes with numbers |

**Engines:** PostgreSQL only, tested in CI on 16 and the newest major. The portable dialect keeps
other engines possible, but supported only once the suite is green on them.

**Backup and restore** belong to whoever runs the database: the bank's existing backup covers the
schema. The README states that `qr_code`, `qr_code_audit_log`, `qr_code_notification`,
`payment_event` and `event_sequence` must be restored together, from the same point in time.

## Consequences

- A small bank runs one small container next to the database it already operates.
- The DBA reviews one schema, its comments, three roles and a connection ceiling, and can run the
  DDL themselves.
- Every statement stays polite: a store test asserts that no transaction calls out over HTTP.

## Alternatives rejected

- **Require a dedicated database.** It keeps the cost the rewrite exists to remove.
- **Use the bank's tables directly.** It couples X9.150 to every bank's schema, and contradicts
  ADR-0009 and ADR-0014.
- **Migrate at application start-up.** It needs DDL rights for the runtime role.
- **Rely on session settings** (`SET` once per connection). They break under PgBouncer in
  transaction mode.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
