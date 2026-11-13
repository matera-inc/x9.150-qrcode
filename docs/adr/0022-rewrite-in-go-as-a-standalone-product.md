# 22. Rewrite in Go, as a standalone product built to Matera's engineering standards

Date: 2026-11-13

## Status

Accepted 2026-11-13. Frames [0023](0023-postgresql-one-schema-portable-sql.md)–[0035](0035-one-event-builder-and-a-written-transition-map.md).
The execution plan is [`PLAN-GO-POSTGRES-REWRITE.md`](../../PLAN-GO-POSTGRES-REWRITE.md).

## Context

The service is about 20k lines of Java in 290 files, plus 11.5k lines of tests. About half of the
main code converts shapes rather than doing anything:

- 47 application DTOs, 12 of them `*UpdateDTO`
- 9 enum DTOs mirroring 7 domain enums
- 40 mappers
- Mongo models
- 93 more DTOs generated from OpenAPI at build time

The behaviour itself is about 5–6k lines: the entity and its validator, the acceptance and currency
policies, the formula, JWS, and events.

Running the service costs a second database engine: MongoDB as a replica set, with its own version
pin. A small bank would rather give it a schema in the PostgreSQL it already runs. Matera's other
payment software is moving to Go and PostgreSQL under internal engineering standards.

The owner's drivers are settled, and none of them depends on a benchmark:

- less memory;
- no second database;
- far less code.

## Decision

**Rewrite the service in Go on PostgreSQL. It stays a standalone product: same repository, same
Docker Hub image, same HTTP contract.**

**Precedence, in order:**

1. **The ANSI X9.150 standard.** It wins over any internal standard, automatically. The code cites
   the section it follows (for example `// X9.150 §2.1`).
2. **This product's published contract and accepted ADRs** (`openapi.yaml`, ADR-0015's 502, …).
3. **Matera's internal Go and PostgreSQL engineering standards,** followed wherever they do not
   contradict 1 or 2. Where neither the standard nor the contract forces a deviation, those
   standards are followed in full. Any other deviation needs a new ADR, and a preference is never a
   reason.

**We walk the map; we don't publish it.** The internal standards are not copied into this
repository. They show only through:

- the code written to them, reviewed by Matera;
- the configuration that enforces them: the Go lint configuration (including its banned-import
  list), the CI Go job (vulnerability, licence, race and leak checks, fuzz smoke) and the Liquibase
  changelog conventions.

A contributor learns a rule when CI refuses a change.

**This product's engineering choices,** stated as its own:

| Concern | Choice |
|---|---|
| Toolchain | one pinned Go release; cgo-free static binary; reproducible build |
| HTTP | standard library `net/http` with method-pattern routing; middleware is `func(http.Handler) http.Handler` |
| Wiring | constructors in `main`; no `init()`, no DI framework |
| Logging | `log/slog`, JSON |
| Validation | hand-written, returning problem+json |
| Database | `pgx/v5` used directly, hand-written SQL, transactions through `pgx.BeginFunc`; **no ORM, no SQL code generator** |
| Migrations | Liquibase, run **before** the binary; never in-process |
| Time | `TIMESTAMP` holding UTC; `now` taken once per transaction ([0033](0033-the-clock-can-be-moved-in-test-deployments-only.md)) |
| Layout | `cmd/<binary>/main.go` + `internal/<domain>/` (handler, service, store) |
| Tests | standard `testing`, table-driven; `testcontainers-go` for PostgreSQL; leak checks; fuzz targets for every external parser |

**Standalone, so:**

- configuration is one file plus environment overrides ([0031](0031-same-image-lighter-and-3-0-is-a-configuration-migration.md));
- ids are minted by the server ([0026](0026-identifiers-and-the-event-cursor.md));
- there is no tenant model ([ADR-0009](0009-tenant-agnostic-single-system.md));
- the API stays open and is protected at the edge.

**Same repository, Java kept for history.** The Java modules move unchanged to `legacy/java/` with
`git mv`. They are frozen, excluded from CI, and nothing is deleted.

**Behaviour as built, not as planned.** ADRs 0003, 0006, 0007 and 0011 were accepted but never
implemented, and are not ported. The table below ratifies ADRs 0001–0021.

| ADR | Under the rewrite |
|---|---|
| 0001 events by pull | stands; mechanism superseded by [0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md) |
| 0002 embedded outbox | superseded by 0025 |
| 0003 two-phase approval | unbuilt; not ported |
| 0004 phase from transaction id | stands |
| 0005 int64 amounts | stands |
| 0006 notification `mode` | unbuilt; not ported |
| 0007 CA allowlist | unbuilt; not ported |
| 0008 status-update as ISO 20022 entry | stands |
| 0009 tenant-agnostic | stands |
| 0010 network authority | stands |
| 0011 stored quote | unbuilt; not ported |
| 0012 refuse what we cannot honour | stands |
| 0013 MongoDB transient retry | superseded by [0023](0023-postgresql-one-schema-portable-sql.md) |
| 0014 consumer reconciles | stands; single-drainer mechanics superseded by 0025 |
| 0015 payer-side signing | stands |
| 0016 revision vs status | stands |
| 0017 tip on top | stands |
| 0018 PATCH names every currency | stands |
| 0019 reservation lapses on read | stands |
| 0020 payee states the outcome | stands |
| 0021 reservation holder | stands |

**The licence does not change.** Matera owns the work. The Go code is a new version of the same
Licensed Work, under the Matera Source License.

## Consequences

- One stack across Matera's payment software. MongoDB, the replica set and its version pin go away.
- Java's tests do not port. Behaviour is pinned first by the black-box suite
  ([0030](0030-a-black-box-suite-defines-done-and-migration-writes-only-runtime-valid-rows.md),
  [0034](0034-the-black-box-suite-is-python-in-this-repository-under-its-licence.md)).
- Some of Matera's internal shared Go packages would be useful here (HTTP errors, health, leak
  checks, JWS). They are proprietary today. Using them requires relicensing the ones used under the
  Matera Source License (plan D4). Until then, they are reimplemented.

## Alternatives rejected

- **Java + PostgreSQL via JDBC.** Smaller, and it keeps the tests. Rejected because the mapping
  layers and the second stack remain. It stays the fallback (plan §8).
- **Go + MongoDB.** It keeps the largest operational cost.
- **Make it an internal platform service.** That contradicts ADR-0009 and the standalone release.
- **Publish the internal standards here.** The owner's call: walk the map, don't publish it.
- **A line-by-line port** of the hexagonal layering. It would carry over the mapping the rewrite
  exists to remove.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
