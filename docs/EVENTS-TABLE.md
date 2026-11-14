# The event table, for payment-system integrators

> **Applies to the Go/PostgreSQL build**, which is planned, not shipped
> ([ADR-0022](adr/0022-rewrite-in-go-as-a-standalone-product.md)). Its schema is in
> [`db/`](../db/README.md). The Java build serves the same events over HTTP only
> ([ENDPOINTS.md](../ENDPOINTS.md#consuming-the-payment-event-stream)).

X9.150 tells your payment system what happened to a QR Code: a payer reserved it, a payer reported a
transaction hash, it was marked paid, it was cancelled. This page is everything you need to read
those events straight from the database, without asking us.

## X9.150 only inserts rows

**X9.150 communicates with the payment system only by inserting rows into `payment_event`**
([ADR-0037](adr/0037-the-event-table-is-the-outbox-read-by-cdc-or-plain-sql.md)).

- **The insert is part of the transition's own transaction** ([ADR-0025](adr/0025-a-transition-is-one-transaction-and-its-event-is-in-it.md)).
  An event exists if and only if the change it reports was committed. You never see an event for a
  change that rolled back, and a committed change never lacks its event.
- **X9.150 never pushes.** It does not call your systems, publish to a broker, or run a drain. The
  table is the outbox, and you read it in one of three ways (below).
- **X9.150 never updates or deletes an event,** with two exceptions. Your SQL reader marks rows it
  has processed (`read_status_code`, `read_at`), and the retention purge deletes old rows
  (see [Retention](#retention-read-or-not-events-are-purged)).

## The columns

One row per event. "None" is a sentinel, never `NULL`. The same text is
on each column as a database `COMMENT`; `\d+ payment_event` in psql shows it.

| Column | Type | Meaning | None / sentinel |
|---|---|---|---|
| `seq` | `BIGINT`, primary key | The event's position: **the exact order** of events, gapless, equal to commit order. On the HTTP API it is the cursor, as a decimal string | — |
| `event_id` | `UUID`, unique | `eventId` on the wire. A UUIDv7 minted in `seq` order for new events; the original id for events migrated from the Java build. **Your dedupe key** | — |
| `type_code` | `VARCHAR(4)` → `event_type.code` | What happened. See [Event types](#event-types) | — |
| `occurred_at` | `TIMESTAMP` (UTC) | When the transition that emitted the event happened, on X9.150's clock | — |
| `qr_code_id` | `UUID` | The QR Code the event is about. **Not a foreign key:** the QR Code may be purged before its events | — |
| `qr_code_revision` | `INT` | The QR Code's revision when the event was emitted | — |
| `location_id` | `UUID` | The QR Code's location (the id printed in its EMV string) when the event was emitted | — |
| `amount` | `BIGINT` | Amount in minor units (cents for USD), tip included | `-1` = absent |
| `tip_amount` | `BIGINT` | Tip in minor units | `-1` = absent |
| `payer_info` | `VARCHAR(254)` | The payer's `payer.info`, as sent | `''` = absent |
| `currency` | `VARCHAR(32)` | Currency code (`USD`, `USDC`, `FRNT`) | `''` = absent |
| `network` | `VARCHAR(64)` | The rail, as received (`fednow`, `rtp`, `ach`, `solana`) | `''` = absent |
| `transaction_id` | `VARCHAR(255)` | The rail's reference: End-to-End ID, Trace Number, or the transaction hash on a chain | `''` = absent |
| `invoice_number` | `VARCHAR(20)` | The biller's invoice number, for your correlation | `''` = absent |
| `order_number` | `VARCHAR(20)` | The biller's order number, for your correlation | `''` = absent |
| `reason` | `VARCHAR(255)` | Why, for `payment.failed` | `''` = absent |
| `schema_version` | `VARCHAR(8)` | Version of the event body, e.g. `1.1`. See [What can change](#what-can-change) | — |
| `deviations` | `VARCHAR(255)` | How the payer broke the protocol: comma-separated codes, see [Deviations](#deviations-and-receivedinstatus) | `''` = the payer followed the rules |
| `received_in_status_code` | `VARCHAR(4)` → `qr_code_status.code` (no foreign key) | `receivedInStatus` on the wire: the QR Code's status when the notification behind this event arrived | `''` = the event was not caused by a notification |
| `read_status_code` | `VARCHAR(4)` → `event_read_status.code` | For SQL readers only: `PEND` when inserted, `DONE` once your reader processed it | `PEND` |
| `read_at` | `TIMESTAMP` (UTC) | When your reader marked the row `DONE` | `9999-12-31 00:00:00` = not read yet |

Times are `TIMESTAMP` without a time zone, and they always hold UTC.

### Event types

`event_type` maps each code to the name the HTTP API uses:

| `type_code` | Name | Means |
|---|---|---|
| `INIT` | `payment.initiated` | A payer took the reservation: an accepted pre-commit, a post-commit on an `ACTIVE` QR Code, or `PAYMENT_INITIATED` set through the status-update API |
| `SENT` | `payment.sent` | A committed on-chain transaction hash was registered for this QR Code (Solana post-commit). **It is not "paid".** It tells you: *if this hash arrives, it is for this QR Code* |
| `FAIL` | `payment.failed` | The reservation was given back (`NOT_SENT`, or a refused retry from the holder) |
| `CLRD` | `payment.cleared` | The QR Code was marked `PAID` through the status-update API |
| `CANC` | `payment.cancelled` | The QR Code was cancelled |

`qr_code_status` maps the status codes found in `received_in_status_code`: `ACTV` = `ACTIVE`,
`INIT` = `PAYMENT_INITIATED`, `PAID` = `PAID`, `CANC` = `CANCELLED`.

**X9.150 never marks a QR Code `PAID` on its own.** Only your payment system does, after it has
matched the funds it received, through `PUT /api/v1/payment-request/{id}/status-update`
([ADR-0036](adr/0036-pre-commit-on-every-rail-and-every-transaction-hash-is-recorded.md)).

## Order and identity

- **`seq` is the exact order.** It is gapless and equals commit order: a transition takes the next
  number under a row lock on `event_sequence` and commits with it, so a smaller `seq` was always
  visible first. Use `seq` whenever exactness matters.
- **`event_id` sorts the same way** for every event the Go build writes. It is a UUIDv7, minted
  after the sequence lock is taken, from a monotonic generator. Use it when only a UUID is at hand,
  for example a CDC message.
- **`event_id` is the dedupe key.** Store it with whatever you do with the event, and ignore an
  event whose id you already have. Every way of reading below can, after a crash, hand you an event
  twice.
- **Migrated events keep their original ids.** Events copied from the Java build keep their Java
  `eventId`, which is a random UUID (version 4). They come before every new event in `seq` order,
  but **their `event_id` does not sort with `seq`.** Order them by `seq`.

## Three ways to read it

Pick one per consumer.

| Reader | How | Suits |
|---|---|---|
| **CDC** (Debezium, expected) | Stream the inserts on `payment_event` | You already run Kafka Connect, or want events on a bus |
| **Plain SQL** | Claim pending rows, process them, mark them `DONE` | One payment system reading with its own code |
| **HTTP** | `GET /pub/api/v1/events?after=…` | No database access, or code written for the Java build |

### 1. CDC with Debezium: take inserts only

Your readers' `UPDATE`s of the read-status columns, and the retention purge's `DELETE`s, also reach
the write-ahead log. **They are not events. Take inserts only,** and filter twice:

1. **at the source:** a publication that publishes inserts only, so updates and deletes never
   leave PostgreSQL;
2. **in the connector:** `skipped.operations`, in case the publication is ever replaced.

The DBA prepares the database (logical replication needs `wal_level = logical`):

```sql
-- A replication role for the connector: not one of X9.150's four roles.
CREATE ROLE x9_qrcode_cdc LOGIN REPLICATION PASSWORD '…';
GRANT USAGE ON SCHEMA x9_qrcode TO x9_qrcode_cdc;
GRANT SELECT ON x9_qrcode.payment_event TO x9_qrcode_cdc;   -- for the initial snapshot

-- Inserts only.
CREATE PUBLICATION x9_qrcode_events FOR TABLE x9_qrcode.payment_event WITH (publish = 'insert');
```

The connector (Kafka Connect, Debezium 2.x PostgreSQL connector):

```json
{
  "name": "x9-qrcode-events",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "plugin.name": "pgoutput",
    "database.hostname": "db.bank.example",
    "database.port": "5432",
    "database.dbname": "payments",
    "database.user": "x9_qrcode_cdc",
    "database.password": "${file:/opt/secrets/x9-qrcode-cdc.properties:password}",
    "database.sslmode": "verify-full",
    "topic.prefix": "x9",
    "table.include.list": "x9_qrcode.payment_event",
    "publication.name": "x9_qrcode_events",
    "publication.autocreate.mode": "disabled",
    "slot.name": "x9_qrcode_events",
    "skipped.operations": "u,d,t",
    "snapshot.mode": "initial",
    "time.precision.mode": "connect"
  }
}
```

- The topic is `x9.x9_qrcode.payment_event`, and the message key is `seq`.
- Messages with `op = "c"` are new events. With `snapshot.mode: initial`, the rows already in the
  table arrive first as `op = "r"`. Use `no_data` to start from now instead.
- `read_status_code` and `read_at` arrive with each insert as `PEND` and the sentinel. Ignore them:
  a CDC reader never marks rows.
- Timestamps carry UTC. With `time.precision.mode: connect` they arrive as Kafka Connect
  `Timestamp` (milliseconds); the default (`adaptive`) gives epoch microseconds.
- A replication slot retains WAL until the connector confirms it. A connector that stays down
  fills the disk, so watch the slot's lag.

This configuration is an example. Check it against the Debezium version you run.

### 2. Plain SQL with the event-reader role

Connect as the **event-reader role** (`x9_qrcode_event_reader` by default, [db/README.md](../db/README.md#roles)).
Its `search_path` already points at the schema. It may read `payment_event` and its lookups, and
update **only** `read_status_code` and `read_at`; any other write fails with a permission error.

```sql
BEGIN ISOLATION LEVEL READ COMMITTED;

-- 1. Claim a batch. Several workers can run this at once: each gets different rows.
SELECT e.seq, e.event_id, t.name AS type, e.occurred_at, e.qr_code_id, e.amount, e.currency,
       e.network, e.transaction_id, e.deviations, e.received_in_status_code
  FROM payment_event e
  JOIN event_type t ON t.code = e.type_code
 WHERE e.read_status_code = 'PEND'
 ORDER BY e.seq
 LIMIT 100
 FOR UPDATE OF e SKIP LOCKED;

-- 2. Process the rows (dedupe by event_id), then, in the same transaction:
UPDATE payment_event
   SET read_status_code = 'DONE', read_at = :now      -- your clock, in UTC
 WHERE seq IN (:claimed);

COMMIT;
```

- **A worker that dies before `COMMIT` loses nothing.** Its rows return to `PEND` and another
  worker claims them. Anything it did outside the transaction may then happen twice, which is why
  you dedupe by `event_id`.
- **`(read_status_code, seq)` is indexed,** so step 1 stays cheap however many rows are `DONE`.
- **One logical consumer.** The read status is one value per row: the first system to mark a row
  hides it from every other. A second, independent consumer keeps its own cursor instead
  (`SELECT … FROM payment_event WHERE seq > :last ORDER BY seq`, then stores the largest `seq` it
  processed), streams with CDC, or uses HTTP.
- Within one batch, process rows in `seq` order. Across workers, `SKIP LOCKED` hands out rows out of
  order; if you need strict global order, run one worker.

### 3. HTTP

`GET /pub/api/v1/events?after=<cursor>&limit=100&wait=25`, the long-polling API both builds serve
([ADR-0001](adr/0001-events-leave-by-pull-not-push.md)). The cursor is `seq` as a decimal string, and
a cursor the Java build handed out keeps working ([ADR-0026](adr/0026-identifiers-and-the-event-cursor.md)).
Its rules are in [ENDPOINTS.md](../ENDPOINTS.md#consuming-the-payment-event-stream): dedupe by
`eventId`, store the cursor only after the events, and ignore what you do not know.

## What can change

**The table is a public contract.**

- **Within a major `schema_version`, columns and codes are only added.** None is renamed, retyped
  or removed, and no meaning changes. A new major version is announced in the release notes.
- **Ignore what you do not know:** a column you do not select, an event type, a deviation code or
  a status code you have not seen. Read columns by name, never `SELECT *` by position, and never
  fail on an unknown code.
- Changes reach the database only through a new numbered changeset in `db/changelog/`; an applied
  changeset is never edited.

### Deviations and receivedInStatus

When a payer breaks the protocol, X9.150 still records the event: the money may have moved, and the
hash is your only link from the funds to the QR Code. The event says how it arrived instead
([ADR-0036](adr/0036-pre-commit-on-every-rail-and-every-transaction-hash-is-recorded.md)). The codes are a
closed list owned by ADR-0036; a new one is added there first.

| `deviations` code | When |
|---|---|
| `RECEIVED_IN_WRONG_STATUS` | A post-commit reached a QR Code that was not `PAYMENT_INITIATED` held by this payer. `received_in_status_code` says which status it found |
| `ACTION_CONTRADICTS_HASH` | The payer declared `blockchain.action = PAYMENT_INITIATED` (a request for permission) while sending a transaction hash (proof the money already moved) |

- Both codes may appear together (`RECEIVED_IN_WRONG_STATUS,ACTION_CONTRADICTS_HASH`), and on every
  event of the transition (both `payment.initiated` and `payment.sent` when a post-commit reserves an
  `ACTIVE` QR Code).
- `''` means the payer followed the rules. On the HTTP API the field is then absent, never an empty
  list.

| `received_in_status_code` | What the payer did, for a post-commit |
|---|---|
| `INIT`, held by the same payer | Nothing wrong: this is the expected status |
| `INIT`, held by another payer | It paid while someone else held the QR Code (flagged `RECEIVED_IN_WRONG_STATUS`) |
| `ACTV` | It skipped the pre-commit, or paid after its reservation lapsed |
| `PAID` | It paid a QR Code that was already paid |
| `CANC` | It paid a QR Code that had been cancelled |
| `''` | The event was not caused by a payer's notification (for example `payment.cleared`) |

More than one `payment.sent` for one QR Code is the signal of a duplicate payment. Keep every hash.

## Retention: read or not, events are purged

> ⚠️ **Events are deleted after `events.retention` (default `P30D`, 30 days after `occurred_at`),
> whether or not anyone read them** ([ADR-0029](adr/0029-time-is-applied-on-read-and-retention-is-the-only-job.md)).
>
> X9.150 cannot know whether a CDC reader, which never marks rows, has seen an event, so it does not
> wait. A reader that falls further behind than the retention period loses events, silently. Alert
> on your own lag: the age of the oldest `PEND` row, or your CDC slot's lag, against the retention
> period.

The purge deletes in small paced batches. CDC readers filtering to inserts never see those deletes.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
