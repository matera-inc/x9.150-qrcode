# The X9.150 database, for a DBA

This directory is the PostgreSQL schema of the Go build of X9.150. That build is planned, not
shipped ([ADR-0022](../docs/adr/0022-rewrite-in-go-as-a-standalone-product.md)); the Java build
still runs on MongoDB. The schema is written so that a bank can host it **in one schema of a
PostgreSQL it already runs** ([ADR-0032](../docs/adr/0032-x9150-can-live-in-the-banks-own-database.md)).
You can review all of it, and run it yourself, before anything connects.

| Path | What it is |
|---|---|
| `changelog/` | The Liquibase changelog: `db.changelog-master.yaml` includes `changes/0001-…`, `0002-…`, in order |
| `local/bootstrap.sql` | The one-time DBA step (owner role and schema), for a throwaway database |
| `docker-compose.yml` | PostgreSQL 16 plus a Liquibase 4.33.0 one-shot, for trying it locally |
| `ci/apply-and-check.sh` | What CI runs: apply, apply again, `update-sql`, smoke query, role tests |
| `test/` | The smoke query and the role tests |

## What is created

Everything lives in **one schema**, `x9_qrcode` by default (the `x9.schema` property). Liquibase's
own tables (`databasechangelog`, `databasechangeloglock`) go into that schema too. Nothing is
written to `public`, and no extension is installed.

| Table | Holds | Written |
|---|---|---|
| `qr_code` | One row per QR Code: its current state | conditional `UPDATE` only |
| `qr_code_audit_log` | One row per PATCH: the changed fields' previous values | append-only |
| `qr_code_notification` | Every accepted payment notification, with the raw JWS | append-only |
| `payment_event` | The event log, which is also the outbox the payment system reads ([EVENTS-TABLE.md](../docs/EVENTS-TABLE.md)) | append-only; only its two read-status columns are ever updated, by the event reader |
| `event_sequence` | One row: the last event number handed out | `UPDATE` of its one row |
| `payment_event_legacy_cursor` | Cursors of events migrated from the Java build | append-only |
| `qr_code_status`, `event_type`, `event_read_status` | Lookups, seeded by the changelog | never at runtime |

Every table and column has a `COMMENT`, so `\d+` in psql, or any catalog browser, explains it. The
conventions ([ADR-0023](../docs/adr/0023-postgresql-one-schema-portable-sql.md)) are portable SQL:

- no `JSONB`, `ENUM`, arrays, `TIMESTAMPTZ`, unbounded `TEXT`, `SERIAL`, triggers or procedures;
- no key repeated within one YAML mapping (YAML keeps the last one, and Liquibase only warns);
- times are `TIMESTAMP` holding UTC, and money is `BIGINT` in minor units;
- "none" is a sentinel, never `NULL`.

An applied changeset is never edited, and a change is a new numbered file. "Applied" means "on
`main`": CI compares every pull request with its base branch. `db/check/check_changelog.py`
enforces all of this on every pull request that touches `db/`.

## Roles

There are four roles: the three of ADR-0032 (owner, application, read-only) and the event reader
of [ADR-0037](../docs/adr/0037-the-event-table-is-the-outbox-read-by-cdc-or-plain-sql.md). You
create the owner once (below); the changelog runs as the owner and creates the three runtime roles,
or uses them if you created them. Their names are changelog properties; use lowercase identifiers.

| Role (property, default) | For | May |
|---|---|---|
| owner (`x9.role.owner`, `x9_qrcode_owner`) | migrations only | owns the schema and every object in it. Liquibase connects as this role; the service never does |
| application (`x9.role.app`, `x9_qrcode_app`) | the X9.150 service | `SELECT`, `INSERT` and, where a transition needs it, `UPDATE` on its tables. `DELETE` only on the tables the retention purge empties ([ADR-0029](../docs/adr/0029-time-is-applied-on-read-and-retention-is-the-only-job.md)). No `CREATE`, `ALTER`, `DROP` or `TRUNCATE`, and no `UPDATE` of an append-only table |
| read-only (`x9.role.readonly`, `x9_qrcode_readonly`) | auditors, the black-box suite | `SELECT` on every table, nothing else |
| event reader (`x9.role.event_reader`, `x9_qrcode_event_reader`) | the payment system, reading events with plain SQL | `SELECT` on `payment_event` and its lookups, and `UPDATE` of `read_status_code` and `read_at` only |

- **Each runtime role's own `search_path` selects the schema** (`ALTER ROLE … SET search_path`), so
  the service's SQL is never schema-qualified and no connection parameter is needed.
- **`ALTER DEFAULT PRIVILEGES`** gives a table added by a later changeset `SELECT, INSERT` for the
  application and `SELECT` for the read-only role. Anything more is granted explicitly, in the
  changeset that adds the table.
- **No password is written anywhere.** The runtime roles are created `LOGIN` without one; you set
  them, or you pre-create the roles yourself.

## Installing it in your database

Once, as a DBA:

```sql
CREATE ROLE x9_qrcode_owner LOGIN PASSWORD '…';
CREATE SCHEMA x9_qrcode AUTHORIZATION x9_qrcode_owner;
-- Then either let the changelog create the three runtime roles:
ALTER ROLE x9_qrcode_owner CREATEROLE;   -- you may revoke it after the first run
-- or create them yourself (LOGIN, with your password policy) and set each one's search_path:
--   CREATE ROLE x9_qrcode_app LOGIN PASSWORD '…';  ALTER ROLE x9_qrcode_app SET search_path = x9_qrcode;
--   (the same for x9_qrcode_readonly and x9_qrcode_event_reader)
```

The changelog skips a role, or a `search_path`, that already exists. Then, connected **as the
owner** (the first changeset halts otherwise, so that every object has one owner):

```bash
liquibase update \
  --changelog-file=db/changelog/db.changelog-master.yaml \
  --url=jdbc:postgresql://<host>:5432/<database> --username=x9_qrcode_owner --password=… \
  --default-schema-name=x9_qrcode --liquibase-schema-name=x9_qrcode
# other names:  … update -Dx9.schema=… -Dx9.role.app=… (and x9.role.owner, x9.role.readonly, x9.role.event_reader)
```

Finally, set the runtime roles' passwords (`ALTER ROLE x9_qrcode_app PASSWORD '…'`), unless you
created the roles yourself.

### Reviewing the SQL first

If you will not hand the owner role to anyone, generate the SQL, review it, and run it yourself:

```bash
liquibase update-sql <the same options as above> > x9-qrcode.sql
psql -U x9_qrcode_owner -d <database> -v ON_ERROR_STOP=1 -f x9-qrcode.sql
```

Generate it against the database you will run it on. Liquibase checks what is already applied, and
which roles already exist, when it writes the file. The file also records the changesets in
`databasechangelog`, so a later `liquibase update` knows they ran. CI checks exactly this: the
generated file runs cleanly, and Liquibase then finds nothing left to apply. Every CI run publishes
the file it generated as the `update-sql-postgres-16` and `update-sql-postgres-newest` artifacts.
The newest major is pinned in `.github/workflows/db-schema.yml` (PostgreSQL 18 today) and moved
deliberately, so a new PostgreSQL release cannot change CI's result without a commit.

## What the service asks of your database

These describe the Go service as designed (ADR-0025, ADR-0032).

- **Connections: at most 12 per instance.** There are two pools: writes (8) and reads (4). Idle
  connections are released after 5 minutes, and connections carry `application_name=x9-qrcode`.
  X9.150 runs one active instance. Your event readers and auditors connect separately, with their
  own roles.
- **PgBouncer ≥ 1.21 in transaction mode works.** Every transaction sets `READ COMMITTED` and its
  own timeouts with `SET LOCAL`, and nothing relies on session state. Behind an older PgBouncer, or
  one with `max_prepared_statements=0`, set the driver's `default_query_exec_mode=exec`.
- **TLS:** `sslmode` and the root CA are configurable. Use `verify-full`. Credentials are read from
  a mounted file, so rotating them is a file change and a reconnect.
- **Short transactions.** No transaction spans an outbound HTTP call. The one intentionally
  contended lock is the single `event_sequence` row, held only for the final batch of a
  transition and its commit, with a 2 s `lock_timeout`.
- **No coupling to anything else in your database:** no foreign keys into, views over, or reads of
  any other schema. Your systems integrate over HTTP and the event table.
- **Backup:** your existing backup covers the schema. Restore `qr_code`, `qr_code_audit_log`,
  `qr_code_notification`, `payment_event` and `event_sequence` together, from the same point in
  time.

## Trying it locally

PostgreSQL 16 in a throwaway container, trusting every connection on `127.0.0.1:55432` (set
`X9_DB_PORT` to change it). For local use only:

```bash
docker compose -f db/docker-compose.yml up -d --build          # PostgreSQL, bootstrap, Liquibase
docker compose -f db/docker-compose.yml logs liquibase         # every changeset runs, exit 0
docker compose -f db/docker-compose.yml run --rm liquibase     # again: "Run: 0", exit 0
docker compose -f db/docker-compose.yml run --rm liquibase update-sql
psql -h 127.0.0.1 -p 55432 -U x9_qrcode_app postgres           # lands in schema x9_qrcode
docker compose -f db/docker-compose.yml down -v                # throw it away
```

The changelog is copied into the Liquibase image, not mounted, so rebuild (`--build`) after editing
it. The full CI check runs the same way on a laptop, on any image:

```bash
db/ci/apply-and-check.sh postgres:16 db-out
```

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
