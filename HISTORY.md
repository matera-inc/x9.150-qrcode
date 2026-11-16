# History: from Java on MongoDB to Go on PostgreSQL

This service was first written in Java 25 on Spring Boot, with MongoDB as its database. It has been
rewritten in Go, on PostgreSQL. This page says why, how the change was kept safe, and what is still
to come. The decision itself is
[ADR-0022](docs/adr/0022-rewrite-in-go-as-a-standalone-product.md); the plan is
[PLAN-GO-POSTGRES-REWRITE.md](PLAN-GO-POSTGRES-REWRITE.md).

## Why we changed technology

There were four reasons.

1. **No second database.** The Java build needs MongoDB, run as a replica set, because its
   transactions only work on one. MongoDB also brought its own version pin: MongoDB 8 refuses to
   start on newer Linux kernels, so the Java build is pinned to MongoDB 7.0, which is supported
   until 2027-08-31. A small bank already runs PostgreSQL. The Go build lives in one schema of that
   database, with its own roles and a bounded number of connections
   ([ADR-0032](docs/adr/0032-x9150-can-live-in-the-banks-own-database.md)). The bank does not have
   to patch, back up, monitor and review a second database engine for this one service.

2. **Less memory and a lighter image.** One small Go binary replaces a JVM. The container image
   went from about 393 MB (Java) to about 5.5 MB (Go), as measured by the build. Memory, start time
   and throughput are measured by the benchmark; see [BENCHMARK.md](BENCHMARK.md).

3. **Far less code.** About half of the Java main code converted data from one shape to another
   rather than doing anything: 47 application DTOs, 9 enum DTOs, 40 mappers, the MongoDB models, and
   93 more DTOs generated from OpenAPI at build time. The behaviour itself is about 5–6k lines. The
   Go build keeps one structure from the wire to the database
   ([ADR-0024](docs/adr/0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)).

4. **One stack.** Matera's other payment software is moving to Go and PostgreSQL.

These reasons do not depend on the benchmark. The rewrite was decided before any number was
measured, and it would stand even if Java were faster. The benchmark backs the "lighter" claim with
numbers. [BENCHMARK.md](BENCHMARK.md) publishes every number as measured, whichever way it falls.

## How the change was made safe

- **Same product.** The Go build lives in this repository, serves the same HTTP contract, and will
  ship under the same image name, `materainc/x9-qrcode`
  ([ADR-0022](docs/adr/0022-rewrite-in-go-as-a-standalone-product.md),
  [ADR-0031](docs/adr/0031-same-image-lighter-and-3-0-is-a-configuration-migration.md)). API
  consumers should notice nothing.
- **A clear order of authority.** The ANSI X9.150 standard comes first, then this repository's
  accepted ADRs, then the Java build's behaviour. When the port found a clear Java bug, the bug was
  fixed in Java, where the fix was small, rather than copied into Go. Some examples:
  - Amounts now carry at most 18 digits, as X9.150 requires. Java accepted 19, the most an int64
    holds ([ADR-0005](docs/adr/0005-amounts-stay-int64.md)).
  - A malformed QR Code (a bad CRC, a truncated string) is refused. Java used to read it as good and
    fetch a payload from the host it named.
  - A status update to `PAYMENT_INITIATED` on a lapsed reservation now reserves the QR Code. Java
    answered yes and reserved nothing ([known-bugs.md](known-bugs.md), BUG-002).
  - A conditional request on a QR Code whose reservation lapsed no longer fails with 412, because
    the ETag is built from the effective status
    ([ADR-0028](docs/adr/0028-the-http-contract-is-frozen-and-equivalence-is-proved-by-a-differential-run.md)).
  - A `jku` or `x5u` URL that only mentioned this deployment's host was fetched over plain HTTP.
    Now only the deployment's own host is read internally, and every other URL is fetched over
    HTTPS ([known-bugs.md](known-bugs.md), BUG-005).
- **A black-box suite defines "done".** The suite under [`others/blackbox/`](others/blackbox/README.md)
  tests the service only through its HTTP API and its database, so it does not care which language
  answers ([ADR-0030](docs/adr/0030-a-black-box-suite-defines-done-and-migration-writes-only-runtime-valid-rows.md)).
  It was written and made green against Java first. It now runs in full against both builds: about
  300 black-box cases, out of about 900 tests listed in [docs/TEST-INDEX.md](docs/TEST-INDEX.md).
- **Differences are written down.** Where the two builds still answer differently on purpose, the
  difference is listed in ADR-0028's "Known differences between the builds", with the reason.
- **One schema, one changelog.** The database is one PostgreSQL schema, built by a Liquibase
  changelog that runs before the binary
  ([ADR-0023](docs/adr/0023-postgresql-one-schema-portable-sql.md), [db/README.md](db/README.md)).
  A DBA can review the SQL and run it themselves.
- **The event table is the outbox.** Each status change inserts its event into `payment_event` in
  the same transaction. The payment system reads that table by HTTP, by change data capture or by
  plain SQL ([ADR-0037](docs/adr/0037-the-event-table-is-the-outbox-read-by-cdc-or-plain-sql.md)).

## Timeline

| Date | Milestone |
|---|---|
| 2026-07-19 | First commit: the Java service on Spring Boot and MongoDB |
| 2026-09-30 | First black-box tests, written in Python against the running service |
| 2026-11-13 | The rewrite is proposed, and ADRs 0022–0035 and the plan are accepted |
| 2026-11-14 | The epics and stories are published; the last behaviour fixes land in Java (Epics 1–2) |
| 2026-11-14 | ADR-0036 (pre-commit on every rail) and ADR-0037 (the event table is the outbox) are accepted |
| 2026-11-14 | The black-box suite runs in CI against Java, and every rule is pinned by a test (Epics 3–4) |
| 2026-11-14 | The PostgreSQL schema, as a Liquibase changelog with four roles (Epic 6) |
| 2026-11-14 | The first Go code: configuration, connection pools, health, shutdown and the image (Epic 7) |
| 2026-11-15 | Go signs, verifies and encodes exactly as Java does (Epic 8); the benchmark harness (Epic 5) |
| 2026-11-15 | Billers create, read, edit and settle QR Codes on Go (Epic 9) |
| 2026-11-15 | Payers fetch, notify and are evented on Go; the whole suite is required against Go (Epic 10) |
| 2026-11-16 | ADR-0038: no data migration from the Java build; a written configuration mapping instead (Epic 13) |

## What's next

The remaining epics are listed in
[docs/rewrite/EPICS-AND-STORIES.md](docs/rewrite/EPICS-AND-STORIES.md):

- **Epic 11:** prove the builds equivalent with a differential run, Java against Go.
- **Epic 12:** retention and housekeeping of old rows.
- **Epic 13:** no data migration: the Java build has no production users, so the Go build starts
  on an empty database ([ADR-0038](docs/adr/0038-no-data-migration-from-the-java-build-a-written-mapping-instead.md)).
  Every Java setting is mapped to its Go key in
  [docs/CONFIGURATION-FROM-JAVA.md](docs/CONFIGURATION-FROM-JAVA.md); no migration tool is built.
- **Epic 14:** the lighter image ships.
- **Epic 15:** [BENCHMARK.md](BENCHMARK.md) is re-run for the release.
- **Epic 16:** the cutover.
- **Epic 17:** the Java build is frozen, unchanged, in `legacy/java/`. Nothing is deleted.

The Go build will be released as **3.0.0**, once its tests and benchmarks are done
([ADR-0031](docs/adr/0031-same-image-lighter-and-3-0-is-a-configuration-migration.md)). It changes
the database and the configuration, so it is a new major version. The last Java build will be
tagged **`java-final`**, and stays available for anyone not ready to move.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
