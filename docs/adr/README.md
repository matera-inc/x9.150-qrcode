# Architecture Decision Records

An ADR records **one decision, why it was made, and what was rejected** — so the reasoning survives
the people and the pull requests. ADRs are written in **English**, like every other document in this
repository.

## Why ADRs here, alongside the other docs

The three document types in this repo have different lifespans, and mixing them is what makes
documentation rot:

| Document | Lifespan | Changes by |
|---|---|---|
| **ADR** (`docs/adr/NNNN-*.md`) | Forever | Being **superseded** by a later ADR — never edited after acceptance |
| **Reference docs** (`STATE-MACHINE.md`, `ENDPOINTS.md`, `EVENTS.md`, `openapi.yaml`) | As long as the feature | Edited to match the code |
| **Plan** (`PLAN-*.md`) | Until the work ships | Deleted or archived when done |

So a decision lives in an ADR, how the system behaves today lives in the reference docs, and what we
are about to build lives in the plan. A reversal is a new ADR marked *Supersedes NNNN*, which keeps
the history of why we changed our minds — the part that is usually lost.

## Index

| # | Decision | Status |
|---|---|---|
| [0001](0001-events-leave-by-pull-not-push.md) | Events leave X9.150 by **pull, not push** — embedded outbox → event log → long-polling API. No broker client in the service | Accepted — drain mechanics superseded by [0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md) |
| [0002](0002-atomic-transitions-with-embedded-outbox.md) | **Atomic single-document transitions** with an embedded outbox — exactly-once initiation, state and event in one update | Superseded by [0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md) |
| [0003](0003-two-phase-payment-notification-approval.md) | **Two-phase approval** — the biller may refuse a pre-funds notification; X9.150 coordinates; fail closed | Accepted |
| [0004](0004-phase-inferred-from-transaction-id.md) | Payment phase is **inferred from the absence of a transaction id**, because the standard has no phase marker | Accepted |
| [0005](0005-amounts-stay-int64.md) | Monetary amounts stay **`int64` minor units** — mandated by the standard, not our choice | Accepted |
| [0006](0006-notification-opt-in-is-additive.md) | Notification opt-in is an **additive optional field**, not a new API version | Accepted |
| [0007](0007-configurable-ca-allowlist.md) | **Configurable CA allowlist** for notification signatures, with a self-signed escape for dev | Accepted |
| [0008](0008-status-update-is-the-iso20022-entry-point.md) | **Status-update is the rail-agnostic entry point** (pacs.008); X9.150 does not parse ISO 20022 | Accepted |
| [0009](0009-tenant-agnostic-single-system.md) | **X9.150 is tenant-agnostic** — a QR Code exists on its own; no `tenantId`, no filter, one system per deployment | Accepted |
| [0010](0010-networks-are-interpreted-only-once-their-authority-publishes.md) | **A network is interpreted only once its authority publishes** how it embeds in X9.150 | Accepted — *carried verbatim* consequence superseded by [0012](0012-refuse-what-this-deployment-cannot-honour.md) |
| [0011](0011-store-the-quote-rather-than-recalculate-it.md) | **Store the quote we presented**, uniformly, rather than recalculating it — FX forces a store, so one rule beats two | Accepted |
| [0012](0012-refuse-what-this-deployment-cannot-honour.md) | **Refuse at creation what this deployment cannot honour** — unsupported networks and currencies are rejected by name, not carried verbatim | Accepted |
| [0013](0013-retry-what-mongodb-says-to-retry.md) | **Retry what MongoDB says to retry** — on the `TransientTransactionError` label only, outside the transaction; a duplicate key still fails at once | Superseded by [0023](0023-postgresql-one-schema-portable-sql.md) |
| [0014](0014-we-transport-and-sequence-the-consumer-reconciles.md) | **We transport and sequence; the consumer reconciles** — a post-commit that differs from its pre-commit is forwarded, not judged | Accepted — drain mechanics superseded by [0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md) |
| [0015](0015-x9150-signs-the-payers-notification-too.md) | **X9.150 signs the payer's notification too** — a plain REST API for pre/post-payment, so a PSP never builds a JWS | Accepted |
| [0016](0016-a-revision-is-a-version-of-the-request-not-of-its-status.md) | **A revision is a version of the request, not of its status** — `revision` counts data changes only; the lock token is separate; conditional requests use an `ETag` | Accepted |
| [0017](0017-a-tip-is-money-on-top-of-the-bill.md) | **A tip is money on top of the bill** — `payment.amount` is the total, tip included; the merchant's share is `amount - tipAmount` | Accepted |
| [0018](0018-a-patch-names-every-currency-or-none.md) | **A patch names every currency, or none** — no currency may be added by a patch, and none may be left out; refused with 400, nothing written | Accepted |
| [0019](0019-a-reservation-expires-and-nothing-sweeps.md) | **A reservation expires, and nothing sweeps** — `PAYMENT_INITIATED` carries the instant it stops counting; the lapse is applied on read, never by a background job | Accepted |
| [0020](0020-the-payee-states-the-outcome-of-its-own-receivable.md) | **The payee states the outcome of its own receivable** — marking a bill paid is an order, not a claim; payers stay strict, `CANCELLED` stays final | Accepted |
| [0021](0021-a-reservation-knows-who-holds-it.md) | **A reservation knows who holds it** — `payer.info` plus the signing certificate's subject; one payer may re-announce or release, a second is refused 409 | Accepted |
| [0022](0022-rewrite-in-go-as-a-standalone-product.md) | **Rewrite in Go, as a standalone product** — same repo, image and contract; X9.150 standard > published contract > Matera's internal standards; ratifies 0001–0021 | Accepted |
| [0023](0023-postgresql-one-schema-portable-sql.md) | **PostgreSQL ≥ 16, one schema, portable SQL** — explicit READ COMMITTED; retry only on 40001/40P01 | Accepted — supersedes 0013 |
| [0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md) | **A QR Code is one row, and every PATCH is audit-logged** — one owner per column; UPDATE then audit insert; location rule | Accepted |
| [0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md) | **A transition is one transaction, and its event is in it** — re-run on conflict; a sequence row makes cursor order visibility order | Accepted — supersedes 0002 and the drain mechanics of 0001/0014 |
| [0026](0026-identifiers-and-the-event-cursor.md) | **Server-minted UUIDs**, 32-hex on the wire; decimal cursor; ULID cursors translated | Accepted |
| [0027](0027-crypto-in-go-x9150-jws-profile-pkix-with-crl-and-emv-by-hand.md) | **Crypto in Go** — own JWS verification path; CRL then OCSP, soft-fail; EMV by hand | Accepted |
| [0028](0028-the-http-contract-is-frozen-and-equivalence-is-proved-by-a-differential-run.md) | **The HTTP contract is frozen, and equivalence is proved by a differential run** against Java; ETag fixed in both builds | Accepted |
| [0029](0029-time-is-applied-on-read-and-retention-is-the-only-job.md) | **Time is applied on read; retention is the only job** — one timer; expiry is not deletion | Accepted |
| [0030](0030-a-black-box-suite-defines-done-and-migration-writes-only-runtime-valid-rows.md) | **A black-box suite defines "done"; the cutover leaves only rows the runtime could have written** | Accepted |
| [0031](0031-same-image-lighter-and-3-0-is-a-configuration-migration.md) | **Same Docker image, lighter; 3.0.0 is a configuration migration** with a converter; one instance; `java-final` | Accepted |
| [0032](0032-x9150-can-live-in-the-banks-own-database.md) | **X9.150 can live in the bank's own database** — configurable schema, three roles, PgBouncer-safe, TLS, DBA-run SQL | Accepted |
| [0033](0033-the-clock-can-be-moved-in-test-deployments-only.md) | **The clock can be moved in test deployments, and only there** — loopback listener, compiled-in fingerprint guard | Accepted |
| [0034](0034-the-black-box-suite-is-python-in-this-repository-under-its-licence.md) | **The black-box suite is Python, in this repo, under its licence** | Accepted |
| [0035](0035-one-event-builder-and-a-written-transition-map.md) | **One event builder, and a written transition map** | Accepted |
| [0037](0037-the-event-table-is-the-outbox-read-by-cdc-or-plain-sql.md) | **The event table is the outbox, read by CDC or by plain SQL** — X9.150 only inserts; Debezium, a read-status column for SQL readers, or the HTTP API; the columns become a public contract | Proposed |

## Writing a new one

1. Copy the shape of an existing ADR: **Context → Decision → Consequences → Alternatives rejected**.
2. Number it sequentially; never renumber an existing one.
3. Keep it to roughly one page. If it needs more, the extra belongs in a reference doc.
4. **Name what was rejected and why.** An ADR without rejected alternatives is a description, not a
   decision, and it is the rejected options that a future reader most needs.
5. To **reverse** a decision, write a **new** ADR marked *Supersedes NNNN*, and mark the old one
   *Superseded by NNNN*. Do not edit the original — the point is that the change of mind is visible.
6. To **complete** one — a clarification that forecloses a case the original left open, without
   changing what was decided — add a dated `### Amendment, YYYY-MM-DD` block to the existing ADR,
   placed where a reader meets it before the text it affects. Say plainly what was open and what is
   now closed. Reversals get a new number; completions do not, because there is no change of mind to
   record. If you cannot tell which you are doing, it is a reversal.

## Grounding rule

ANSI X9.150 is copyrighted and is **not** in this repository. ADRs **may quote a short normative
fragment — one or two lines — with its section attributed**, and should, when a decision turns on the
exact wording: a verbatim `SHALL` is evidence, a paraphrase is a claim. Whole sections, field tables
and figures stay out, and the standard itself is never committed. See the quoting rule in `AGENTS.md`,
and [`official-spec/README.md`](../../official-spec/README.md) for where to buy it.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
