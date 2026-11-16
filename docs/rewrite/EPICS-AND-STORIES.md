# Go + PostgreSQL rewrite: epics and stories

This is the work breakdown for the rewrite planned in
[PLAN-GO-POSTGRES-REWRITE.md](../../PLAN-GO-POSTGRES-REWRITE.md) and decided in ADRs
[0022](../adr/0022-rewrite-in-go-as-a-standalone-product.md)–0035. It is published so that anyone
following the rewrite, or contributing to it, can see what each step must prove.

- **Every acceptance criterion has an ID** (`S1.3-AC2`). Tests cite the IDs they prove in their
  docstrings, and the rule-coverage table (Story 4.6) maps rules to them.
- **It is a working document.** It changes as stories are built, and it is deleted when the rewrite
  ships; the ADRs, the docs and the tests remain.

## Overview

This document provides the complete epic and story breakdown for x9.150-qrcode's Go + PostgreSQL
rewrite. There is no PRD: this is a rewrite, and its requirements are the accepted design (ADRs
0022–0035 and the plan) plus the behaviour the existing Java build already
has. That behaviour is documented in STATE-MACHINE, ENDPOINTS, INTERPRETATION and ADRs 0004–0021.

**Scope:**

- **Detailed stories:** plan phases 0, 1 and 1b (track A, pin behaviour on Java), 2 and 3
  (track B, Go/PostgreSQL foundations), and 4 to 6 (Epics 8 to 10, the Go build's crypto and
  endpoints).
- **Epic stubs only:** phases 7–12.

## Requirements Inventory

### Functional Requirements

**Track A: pin behaviour on Java (phases 0, 1, 1b)**

FR1: The reference docs agree with the Java code (and say where the code departs from the spec)
before tests are written against them:
- the status is `PAYMENT_INITIATED` everywhere, not `INITIATED`;
- Solana appears wherever the interpreted rails are listed;
- per rail, what links a payment to its QR Code (`QRCD` in pacs.008 / ACH addenda / Solana memo),
  that pre-commit is always used and post-commit carries the `transactionId`, with the current Java deviations
  recorded until Epic 2 closes them; ADR-0004 and ADR-0012 amended;
- the unsigned answer to a notification is recorded as a deviation until Story 2.3;
- no currency is ever implied: every payment method states its currency, and
  `supported-currencies.json` is an allow-list, not a default;
- both skill locations are named; known-bugs.md does not list fixed bugs as open; TODO.md drops the
  superseded "payment expected event" item and ends with its footer; CLAUDE.md points to the
  rewrite plan without describing Go as current.

FR2: Time is judged by the payee's clock, and the due date is on time (plan Q1, resolved by the
owner 2026-11-13). The due date is a timestamp; a payment at any instant up to and including it is
on time (full amount, no late fee), and late fees start strictly after it. Today Java throws
`IllegalCallerException` at exactly `dueDate`; Java is fixed, and the rule is recorded and pinned.
The payer's own `validUntil` check is advisory: under clock skew the payee's clock at receipt
decides.

FR3: Java gets the test clock of ADR-0033:
- **One offset clock** feeds all four time reads: `DateTimeUtils.nowUTC()`, the two
  `Clock.systemUTC()` reads in `JwsQRCodeSignatureService`, and `OffsetDateTime.now` in
  `RetrieveQRCodePayloadInput.java:24`.
- **The surface** is `PUT/GET/DELETE /test/clock` on a separate listener, at the address in
  `X9_TESTING_CLOCKLISTEN` (default `127.0.0.1:8099`).
- **It is enabled only by `X9_TESTING_CLOCKCONTROL=true`**, and refuses to start unless the signing
  certificate's SHA-256 fingerprint is a bundled demo or test-CA fingerprint.
- **It is visible:** a startup WARN, and an `X-Test-Clock-Offset` response header while the offset
  is non-zero.

FR4: Java's ETag is fixed: one function builds it from the **effective** status, and `If-Match` is
compared against that same function (ADR-0028). A conditional request on a QR Code whose
reservation has lapsed no longer answers 412.

FR5: `others/blackbox/x9client/` provides the protocol helpers for the suite (ADR-0034):
- **`api`:** HTTP on the standard library, with `ETag`/`If-Match` and problem+json parsing;
- **`jws`:** an independent payer signer using the test CA (ms `iat`/`ttl`, `correlationId`,
  `crit`, `x5c`), plus a tamperer producing every refusal case;
- **`emv`:** TLV and CRC16 decoding;
- **`clock`:** `move_to`, `now`, `reset`;
- **`events`:** a cursor reader with dedupe;
- **`peer`:** a test payee that answers this service's outbound notifications right and wrong;
- **`db`:** optional read-only inspectors for MongoDB now and PostgreSQL later.

FR6: `others/blackbox/contract.py` generates refusal cases for every `maxLength`, `minLength`,
`pattern` and `required` constraint in `openapi.yaml`.

FR7: The scenario catalogue (plan §4.1, groups A–I) exists as tests, each asserting the status, the
reason, and what did **not** happen. Groups:

| Group | Covers |
|---|---|
| A | create refusals |
| B | PATCH limits |
| B2 | ETag / `If-Match` |
| B3 | location rebind |
| C | payload retrieval |
| E | notifications |
| F | status-update |
| G | events |
| H | payer-side endpoints |
| I | one test per row of the ADR-0035 transition map |

FR8: The time edges T1–T12 (plan §4.2) exist as serial tests driven by `x9client.clock`:

- **T1/T3:** both discount edges (eligibility instant and advertised `validUntil`).
- **T11:** asserted per build.

Expected results marked ❓ are observed on Java and confirmed by the owner.

FR9: `others/acceptance/acceptance.py` is folded into `others/blackbox`, section by section, and
deleted once empty.

FR10: A rule-coverage table maps every rule in STATE-MACHINE, ENDPOINTS, INTERPRETATION and ADRs
0004–0021, and every row of the ADR-0035 transition map, to at least one test.

FR11: ADR-0035's transition table is verified against the Java code. Any row Java contradicts is
corrected in the ADR, as an amendment, before Go is built.

FR12: Golden corpora are exported from Java and committed:
- **JWS:** signed, tampered, expired, missing/unknown `crit`, wrong `typ`, revoked, untrusted, EC;
- **EMV:** strings for representative hosts and locations, with their expected decoding.

FR13: The black-box suite runs in CI against the Java build: compose, MongoDB replica set, the
clock flag on, and the test-CA keystore. It also stays the publish gate.

FR14: `others/benchmark/` holds:
- k6 scenarios S1 (create), S2 (full payment cycle) and S7 (footprint), using k6 v2.4.0 and
  WebCrypto signing;
- Kubernetes manifests with the §9.2 resource limits;
- `collect.sh`.

A Java + MongoDB baseline run is committed under `others/benchmark/results/`.

**Track B: Go/PostgreSQL foundations (phases 2, 3)**

FR15: A Liquibase changelog (`db/changelog/`) creates the schema of plan §2.1:
- **tables:** `qr_code`, `qr_code_audit_log`, `qr_code_notification`, `payment_event`,
  `event_sequence` (one row, `last_seq = 0`), `payment_event_legacy_cursor`;
- **lookups:** `qr_code_status` and `event_type`, with seed rows;
- **`COMMENT` on every table and column;**
- **indexes:** `location_id` unique across all rows, an index on every FK, and the purge index on
  `payment_event(occurred_at)`;
- **no FK** from `payment_event` to `qr_code`.

FR16: Four roles, the three of ADR-0032 and the event reader of ADR-0037:
- **owner:** migrations only. The DBA creates it once with the schema; the changelog runs as it
  and uses it, but does not create it (ADR-0032, amendment of 2026-11-14);
- **application:** DML on its own tables;
- **read-only:** `SELECT` only;
- **event reader:** `SELECT` on `payment_event`, `UPDATE` of its two read-status columns only.

The changelog creates the three runtime roles, or uses them when the DBA created them.

It also writes explicit grants and `ALTER DEFAULT PRIVILEGES`, and sets the application role's
`search_path` (`ALTER ROLE … SET search_path`). The schema name is a changelog parameter.

FR17: Compose runs PostgreSQL 16 plus a `liquibase/liquibase:4.33.0` one-shot. CI applies the
changelog on PostgreSQL 16 and on the newest major (pinned, PostgreSQL 18 today, and moved
deliberately). `liquibase update-sql` produces DBA-reviewable SQL.

FR18: A CI check enforces the changelog conventions:
- no edits to applied changesets;
- `COMMENT` present for every table and column;
- no banned non-portable constructs (`JSONB`, `ENUM`, arrays, `TIMESTAMPTZ`, unbounded `TEXT`,
  `SERIAL`, triggers or procedures);
- no duplicate YAML keys in a changelog file.

FR19: A Go module at the repository root provides `cmd/x9-qrcode` with:
- configuration from one YAML file plus the documented environment variables, which take
  precedence; required keys are validated at start, with no silent defaults for anything
  security-relevant;
- refusal to start on Spring-style or MongoDB configuration, naming `x9-config-convert`;
- `slog` JSON logging, including incoming `b3`/`traceparent` ids;
- two pgx pools (writes 8, reads 4), both on the primary;
- startup checks that `current_schema()` and the changelog version match;
- `/actuator/health`, `/liveness` and `/readiness` in the existing JSON shape;
- graceful shutdown as in ADR-0031.

FR20: `internal/clock` is the only source of business time. Lint forbids the package-level time
helpers (`time.Now`, `Since`, `Until`, `After`, `Tick`, `NewTimer`) everywhere else, with explicit
exemptions only for real-duration measurement.

FR21: The CI Go job runs:
- `go vet`, `golangci-lint` (with a banned-import list matching the chosen libraries) and `forbidigo`;
- `govulncheck` and `go-licenses`;
- `go test -race -shuffle=on`, with leak checks;
- a fuzz smoke run.

Dev tools are pinned in `go.mod` with the `tool` directive.

FR22: A multi-stage `Dockerfile` builds a static binary (`CGO_ENABLED=0`, `-trimpath`, version and
commit stamped) onto `gcr.io/distroless/static-debian13:nonroot` pinned by digest, for amd64 and
arm64 via `docker buildx`. `make build`, `make test` and `make image` wrap `go` and `docker`.

**Later phases (epic stubs only)**

| Requirement | Phase | Scope |
|---|---|---|
| FR23 | 4 | EMV, JWS and keys (ADR-0027) |
| FR24 | 5 | payment requests (ADR-0024, 0026, 0035) |
| FR25 | 6 | payload, notifications and events (ADR-0025, 0026, 0035) |
| FR26 | 7 | differential run and interop (ADR-0028) |
| FR27 | 8 | housekeeping (ADR-0029) |
| FR28 | 9 | `x9-migrate` and `x9-config-convert` (ADR-0030, 0031) |
| FR29 | 10 | image and deploy (ADR-0031) |
| FR30 | 10b | benchmark campaign and `BENCHMARK.md` (S1, S2, S7) |
| FR31 | 11 | cutover; rollback only until Go's first write (ADR-0030) |
| FR32 | 12 | freeze Java into `legacy/java/` (ADR-0022) |

FR34: Pre-commit is available, and the rule is the same, on every rail. A notification without a
`transactionId` is a pre-commit: it goes through the acceptance gate at the payee's clock and takes the
reservation, so two payers cannot pay one QR Code at the same time. A notification with a
`transactionId` is a post-commit: accepted only from the payer that won the pre-commit, recorded, never judged
again. A post-commit on an `ACTIVE` QR Code (a payer who skipped the pre-commit) moves it to
`PAYMENT_INITIATED` for that payer and registers its hash; it is not refused. The evidence decides the phase on every
rail, Solana included (ADR-0004): a `PAYMENT_INITIATED` carrying a hash is payer misbehaviour, and is
still a post-commit, recorded and logged as a warning. Every hash is recorded, a second one included,
so the payment system can detect a duplicate payment. Today Solana keys on `blockchain.action`,
FedNow/RTP refuse a notification without an End-to-End ID (no pre-commit, no
race protection) and ACH gates every notification as a pre-commit. Fixed in Java first.

FR35: The reservation holder may retry its pre-commit (same `payer.info`, present on both, and certificate subject; without `payer.info` two payers are assumed different): the retry
is judged again at the payee's clock. If it is accepted, the window is refreshed and no event is
emitted. If it is refused, the refusal also releases the reservation: the QR Code goes back to
`ACTIVE` at once, instead of staying reserved until the window lapses. A payer who takes over a
lapsed reservation produces `payment.initiated`. Today a refused retry leaves the reservation in
place, and a takeover emits nothing because `notifyPayment` compares against the stored status.
Fixed in Java first.

FR36: Honour what was signed (owner, 2026-11-13). The advertised `validUntil` of an adjusted amount
is the exact instant that amount stops applying: the discount's eligibility instant
`dueDate − daysBefore`, or the next 24-hour boundary of the late fee counted from `dueDate`, capped
by the payment method's and the QR Code's `validUntil`. The acceptance gate accepts the advertised
amount up to and including that instant. Today the advertised `validUntil` is truncated to 23:59:59
UTC of the day, up to ~21 h away from the real edge. Fixed in Java first.

FR33: The payee's answer to an accepted payment notification (pre-commit and post-commit) is a signed JWS
with body `{"statusCode": 200}` (spec A.4.5 note), whose `correlationId` echoes the payer's signed
notification. The signed question plus the signed yes is the authorization evidence; settlement and
reconciliation of the funds stay out of scope. Refusals are signed too (owner, 2026-11-13): the same
HTTP status, and a JWS whose body is `{"statusCode": <status>}` plus today's error fields.
When this service sends a notification (acting for the payer), it verifies the payee's signed
answer and its `correlationId`; an empty or unverifiable 200 is reported as an unsigned yes. This is
the spec's own model (owner confirmed 2026-11-13). Fixed in Java first, so the differential run
sees both builds agree.

### NonFunctional Requirements

NFR1: **Precedence** (ADR-0022): the ANSI X9.150 standard first, then the published contract and
accepted ADRs, then Matera's internal engineering standards, followed in full otherwise.

NFR2: **Public-repo rule** (ADR-0022): no internal standard is copied, named or numbered in this
repository. Rules show only through code and the enforcing lint and CI configuration.

NFR3: **The external contract is frozen** (ADR-0028) once Epic 2 ships: no change to paths, status
codes, headers, field names or types after that. The owner-approved changes of Epic 2 (signed
answers, pre-commit on every rail, the outbound `signature` verdict, exact `validUntil`) land in Java
first, so the contract that freezes already contains them. Equivalence is proved by the
differential run.

NFR4: **Portable SQL** (ADR-0023):
- no recorded exceptions;
- `READ COMMITTED` set per transaction;
- timeouts set with `SET LOCAL`;
- schema-unqualified SQL;
- PostgreSQL ≥ 16.

NFR5: **A guest in a bank's database** (ADR-0032):
- bounded pools, `application_name=x9-qrcode`;
- no session state, so PgBouncer ≥ 1.21 in transaction mode works;
- TLS configurable;
- no transaction spans outbound HTTP.

NFR6: **One clock** (ADR-0033): one `now` per request. A production deployment can never move it.

NFR7: **The suite is under this repository's licence** (ADR-0034):
- dependencies: pytest; `cryptography` and `pyyaml` (required); `psycopg` and `pymongo` (optional
  extras);
- HTTP on the standard library;
- runnable with `uv run`.

NFR8: **Every black-box test asserts what did not happen** (no status change, no event, no
reservation), not only the status code. Time scenarios run serially and reset the clock.

NFR9: **The Java changes of Epics 1–2 are the last before the freeze:** the due-date fix (Story
1.3), the test clock, the ETag fix, signed answers and their verification, pre-commit on every rail, the
holder retry and takeover event, and the exact `validUntil` (Stories 2.1–2.6). Nothing else.

NFR10: **Go build:** one pinned Go release, a cgo-free static binary, a reproducible build, one
module, and no Maven artefacts on the Go side.

NFR11: **Commit conventions:** one story per PR, and `build` green before the admin rebase merge.
DCO is a known failure and is not "fixed".

### Additional Requirements

From the plan and the ADRs:

- **No starter template.** Brownfield: the Go code is new at the repository root beside the Java
  modules, until phase 12 moves Java to `legacy/java/`.
- **Guest manners** (ADR-0032) and **one clock** (ADR-0033) bind the track B foundations from the
  first commit.
- **Suite first:** the suite must be green on Java before any rule-bearing Go code (phases 4–6) is
  built.
- **Schema conventions bind from the first changelog:**
  - `TIMESTAMP` in UTC;
  - sentinels instead of NULL;
  - `BIGINT` minor units;
  - holder encoding `''`;
  - one owner of problem `type` URIs (`internal/problem`).
- **Stack pins:**
  - Go 1.27.0 / go1.27.4;
  - pgx v5.13.0;
  - go-jose v4.1.9;
  - google/uuid v1.6.0;
  - x/crypto v0.59.0;
  - Liquibase 4.33.0;
  - distroless static-debian13:nonroot by digest;
  - k6 v2.4.0.
- **CI must not regress the existing Java `build` job** while both builds coexist.
- **The stories are published** (owner, 2026-11-13): this file. Every acceptance criterion carries
  an ID (`S1.3-AC2`) that the tests cite in their docstrings. The file is deleted when the rewrite
  ships.

### UX Design Requirements

None. The service has no UI.

### FR Coverage Map

| Requirement | Epic | Covers |
|---|---|---|
| FR1 | Epic 1 | docs agree with the Java code |
| FR2 | Epic 1 | payee's clock decides; due date on time; fixed and pinned |
| FR3 | Epic 2 | Java test clock |
| FR4 | Epic 2 | Java ETag fix |
| FR33 | Epic 2 | signed answer to payment notifications |
| FR34 | Epic 2 | pre-commit on every rail; `transactionId` decides the phase |
| FR35 | Epic 2 | holder retry; a refused retry releases; takeover is evented |
| FR36 | Epic 2 | advertised `validUntil` of an adjusted amount is its exact edge |
| FR5 | Epic 3 | `x9client` |
| FR6 | Epic 3 | contract-generated refusal cases |
| FR9 | Epic 3 | `acceptance.py` folded in |
| FR13 | Epic 3 | suite in CI against Java |
| FR7 | Epic 4 | scenario catalogue A–I |
| FR8 | Epic 4 | time edges T1–T12 |
| FR10 | Epic 4 | rule-coverage table |
| FR11 | Epic 4 | ADR-0035 map verified against Java |
| FR12 | Epic 4 | golden JWS/EMV corpora |
| FR14 | Epic 5 | benchmark harness + Java baseline |
| FR15 | Epic 6 | changelog: tables, lookups, comments, indexes |
| FR16 | Epic 6 | four roles, grants, default privileges, role `search_path` |
| FR17 | Epic 6 | compose + Liquibase one-shot; CI on PostgreSQL 16 and newest; `update-sql` |
| FR18 | Epic 6 | changelog conventions check |
| FR19 | Epic 7 | Go skeleton: config, logging, pools, schema check, health, shutdown |
| FR20 | Epic 7 | `internal/clock` + lint bans |
| FR21 | Epic 7 | CI Go job |
| FR22 | Epic 7 | multi-stage `Dockerfile` + `make` wrappers |
| FR23 | Epic 8 | EMV, keys, PKI and revocation, JWS signing and verification, the test clock in Go |
| FR24 | Epic 9 | create, GET, PATCH with audit, status-update, ETag and `If-Match`, location rebind, on PostgreSQL |
| FR25 | Epic 10 | event stream, published keys and signature endpoints, payload, notifications under ADR-0036, payer-side endpoints, decoder; the whole black-box suite green on Go |
| FR26 | Epic 11 | the differential run and interop |
| FR27 | Epic 12 | retention and housekeeping |
| FR28–FR32 | Epics 13–17 | stubs for plan phases 9–12 |

## Epic List

**Track A: pin behaviour on Java.** Epics 1–5 follow each other: 1 → 2 → 3 → 4, and 5 can run
alongside 3–4.

**Track B: Go/PostgreSQL foundations.** Epics 6–7 follow each other, and the track runs in parallel
with track A.

**Stubs.** Epics 13–17. Epic 8 was detailed on 2026-11-14, once Epic 4 was green; Epics 9 and 10
on 2026-11-15, once Epic 8 was built; Epic 11 on 2026-11-16, once Epic 10 was done; Epic 12 the same day, once Epic 11 was done.

### Epic 1: The docs tell the truth

Maintainers and integrators read reference docs that match the running Java code, including what a
payment on its due date costs. Tests written in Epics 3–4 therefore pin the right words and the
right behaviour.

**FRs covered:** FR1, FR2

### Epic 2: Time can be tested, conditional requests are right, and a yes is signed

A maintainer can move the clock of a *test* deployment over HTTP, and never of a production one.
A conditional request on a QR Code whose reservation lapsed no longer fails with 412. A payer's PSP
receives a signed answer to its payment notification. These are the last functional changes to
Java before the freeze.

**FRs covered:** FR3, FR4, FR33, FR34, FR35, FR36

### Epic 3: Any deployment can be checked from outside

Anyone, including adopters, can point the black-box suite at a running X9.150 and get an honest
answer. It signs and tampers payer messages independently, generates every field refusal from the
contract, and runs in CI against Java.

**FRs covered:** FR5, FR6, FR9, FR13

### Epic 4: Every rule is pinned

Every rule, edge and transition of X9.150 has a test, so "the Go build behaves the same" becomes a
fact instead of a hope. That covers the scenario catalogue, the time edges, the transition map
verified against Java, and the rule-coverage table. The golden JWS and EMV corpora are exported.

**FRs covered:** FR7, FR8, FR10, FR11, FR12

### Epic 5: A measured baseline for "lighter"

The owner can later back the Docker Hub claim with numbers. A k6 harness (S1, S2, S7) and a
committed Java + MongoDB baseline are captured while Java is still the live build.

**FRs covered:** FR14

### Epic 6: A bank's DBA can host X9.150 in its own PostgreSQL

A DBA can review and apply the schema: one configurable schema, documented tables, four roles,
SQL they can run themselves. CI proves the result on PostgreSQL 16 and on the newest major.

**FRs covered:** FR15, FR16, FR17, FR18

### Epic 7: An operator can run the Go service and see it healthy

An operator can build and start the Go container against that schema. It refuses bad or old
configuration, checks its schema, reports health in the existing shape and shuts down gracefully.
CI enforces the code rules from the first commit. There are no business endpoints yet.

**FRs covered:** FR19, FR20, FR21, FR22

### Epic 8: X9.150's signatures and QR Codes, in Go

Go signs, verifies and encodes exactly as Java does, proved by the golden set of Story 4.7.
**FRs covered:** FR23

### Epic 9: Billers manage payment requests on Go

A biller creates, reads, edits and settles a QR Code on the Go build exactly as on Java, with every
change and its event in one PostgreSQL commit. Done is the black-box suite's payment-request tests
passing against the Go image.

**FRs covered:** FR24

### Epic 10: Payers fetch, notify and are evented on Go, under the pre-commit / post-commit rule

Payers fetch the signed payload, pre-commit and post-commit with a signed answer every time, and
payment systems read the events by HTTP, SQL or CDC. Done is the whole black-box suite passing
against the Go image, except the `java_only` tests and retention (Epic 12).

**FRs covered:** FR25

### Epic 11: Proven equivalent to Java: the differential run and interop

The same inputs go to both builds and their normalised answers are diffed; every difference is on
`TOLERATED-DIFFERENCES.md` with its reason or it fails the build. The two builds pay each other in
all four pairings.

**FRs covered:** FR26

### Epic 12: Retention without surprises: housekeeping

One background job deletes old events, then old QR Codes, on the business clock, in paced batches,
never changing a status; an operator can see what it did and size it.

**FRs covered:** FR27

### Epics 13–17: stubs (detailed later)

| Epic | Phase | Scope |
|---|---|---|
| 13 | 9 | Operators can migrate data and configuration (FR28) |
| 14 | 10 | The lighter image ships (FR29) |
| 15 | 10b | `BENCHMARK.md` published (FR30) |
| 16 | 11 | Cutover (FR31) |
| 17 | 12 | Java frozen in `legacy/java/` (FR32) |

---

## Epic 1: The docs tell the truth

Maintainers and integrators read reference docs that match the running Java code, including how
time is judged at a boundary. Tests written in Epics 3–4 therefore pin the right words and the
right behaviour. Each story is one PR.

### Story 1.1: The reference docs describe the protocol as the code runs it

As a **maintainer or integrator**,
I want STATE-MACHINE, README, CLAUDE.md, INTERPRETATION and the ADRs to state what the Java code
actually does,
So that the black-box tests written from them in Epics 3–4 pin real behaviour, not a story about it.

Every claim below was checked against the code on 2026-11-13. Where a doc is wrong, the doc is
fixed; where the *code* is wrong against the spec, the doc says so and names the story that fixes
it. No code changes in this story.

**Implements:** FR1; ADR-0004, ADR-0010, ADR-0012

**Acceptance Criteria:**

**S1.1-AC1. The status is called `PAYMENT_INITIATED`.**
**Given** `QRCodeStatusEnum` and `openapi.yaml` name the status `PAYMENT_INITIATED`
**When** `grep -nw INITIATED STATE-MACHINE.md CLAUDE.md | grep -v PAYMENT_INITIATED` is run
**Then** it prints nothing (today: about 20 hits in STATE-MACHINE, incl. both diagrams, and
CLAUDE.md:175)
**And** both mermaid diagrams still render.

**S1.1-AC2. README names all four interpreted rails.**
**Given** the code interprets `fednow`, `rtp`, `ach` and `solana`
**When** README is read
**Then** the opening paragraph (today: "FedNow, RTP and ACH, settling in USD") names Solana and its
currencies
**And** README:477's "the only networks this build interprets" is reworded to say `protectionType`
applies to the bank rails.

**S1.1-AC3. The per-network notification table includes Solana.**
**Given** STATE-MACHINE's "Payment-notification effects (by network)" table lists FedNow/RTP, ACH and
"anything else"
**When** it is read
**Then** it has a Solana row pointing to the `blockchain.action` table below it
**And** the stale phrase "when an interpreted chain returns" is removed.

**S1.1-AC4. The docs say, per rail, what carries the link between a payment and its QR Code.**
**Given** the payee can only mark a QR Code paid if something links the incoming funds to it
**When** STATE-MACHINE's phase section and `official-spec/INTERPRETATION.md` are read
**Then** they state, per rail:

| Rail | The QR Code id travels in the payment | Pre-commit (no `transactionId`) | Post-commit (with `transactionId`) |
|---|---|---|---|
| FedNow / RTP | yes: pacs.008 `CdtrRefInf` `QRCD` (spec A.6) | reserves the QR Code (after Story 2.4) | courtesy, plus principal/tip split |
| ACH | yes: addenda `QRCD:[32-digit-id]` (spec A.7, NACHA confirmation pending) | reserves the QR Code | courtesy (after Story 2.4) |
| Solana | only if the memo `{QRCD:"id"}` is set and survives; some custody providers' webhooks do not expose it | reserves the QR Code | **the link in practice** |
| any other blockchain (when interpreted) | most chains have no memo | reserves the QR Code | **the link** |

**And** they state the two purposes: **pre-commit is always used**, on every rail, so that two payers
cannot pay the same QR Code at the same time (the reservation); **post-commit** carries the
`transactionId` (txId / txHash) and is recorded, never judged again on amount or time
**And** until Story 2.4 ships, they record as a known deviation that FedNow/RTP have no pre-commit today
(a notification without an End-to-End ID is refused), that ACH gates every notification as a pre-commit,
and that Solana decides the phase from `blockchain.action` rather than from the transaction hash.

**And** ADR-0004 gets a dated `## Amendment`: its `transactionId` rule stands on every rail, the
Solana code departs from it until Story 2.4, and the pre-commit / post-commit protocol is the reconciliation
mechanism for every chain, memo or not.

**S1.1-AC5. ADR-0012 acknowledges Solana, and claims no default currency.**
**Given** ADR-0012:36 says "Only `fednow`, `rtp` and `ach` are accepted", and ADR-0012:38 says
"`USD` by default", while the code has no default currency (`PaymentMethodVO` refuses a missing
currency; `openapi.yaml` requires it)
**Then** a dated `## Amendment` adds `solana` on its Foundation's published embedding (ADR-0010)
**And** states that every payment method names its currency, with no default, and that the shipped
allow-list is `USD`, `USDC`, `FRNT`.

**S1.1-AC6. The unsigned answer is a recorded deviation until it is fixed.**
**Given** `POST /pub/api/v1/payment-notification` answers an empty, unsigned 200, while the spec's
A.4.5 note asks for a signed `{"statusCode": integer}`
**When** `official-spec/INTERPRETATION.md` is read
**Then** it records this as a known deviation, closed by Story 2.3.

### Story 1.2: Contributor docs state the real defaults, locations and open items

As a **contributor arriving at the repository**,
I want AGENTS.md, CLAUDE.md, known-bugs.md and TODO.md to describe the repository as it is,
So that I don't act on stale facts.

**Implements:** FR1

**Acceptance Criteria:**

**S1.2-AC1. No currency is implied.**
**Given** every payment method and every notification must state its currency (no default in the
code), and `supported-currencies.json` ships `USD`, `USDC` and `FRNT`
**When** AGENTS.md:95 (today: "`USD` by default") is read
**Then** it says the currency must always be specified, and names the shipped allow-list as an
allow-list, not a default
**And** `grep -rn -i "by default" --include=*.md . | grep -i usd` finds nothing outside the frozen
history.

**S1.2-AC2. Both skill locations are named.**
**Given** `.agents/skills/` holds `senior-developer` and `x9150-integration`, and the onboarding
skill is at `.claude/skills/x9-qrcode/`
**When** CLAUDE.md:7 (today: `.agents/skills` only) and AGENTS.md (today: only the onboarding skill,
line 218) are read
**Then** both name both locations and which skill lives where.

**S1.2-AC3. A fixed bug is not listed as known.**
**Given** BUG-001 is marked Fixed (2026-07-20) on a page titled "Known Bugs"
**Then** it sits under a "Fixed" heading, or the page title and intro say the page holds both.

**S1.2-AC4. TODO holds only open items, and its footer is last.**
**Given** TODO.md's "Publish a 'payment expected' event" item (line 26) was superseded by pull-based
events (ADR-0001, ADR-0025), and its footer (line 65) is followed by another section
**Then** the item is removed, or marked superseded with links
**And** the copyright footer is the last thing in the file.

**S1.2-AC5. CLAUDE.md says a rewrite is planned, without pretending it has happened.**
**Given** CLAUDE.md describes the Java/MongoDB build, which stays true until the cutover (Epic 16)
**Then** one line under "Tech Stack" points to `PLAN-GO-POSTGRES-REWRITE.md` and ADR-0022
**And** nothing else in CLAUDE.md describes Go as current.

### Story 1.3: The payee's clock decides, and the due date's last instant is on time

As a **payee**,
I want every approval request judged by my own clock at the moment it arrives,
So that clock skew never lets a payer lock in a discount or validity I no longer grant, and a
payment at the due date's last instant is still accepted.

Java already judges approvals at the payee's `nowUTC()` (`PaymentNotificationAcceptancePolicy`),
with `validUntil` inclusive. The gap is the formula at exactly `dueDate`.

**Implements:** FR2

**Acceptance Criteria:**

**S1.3-AC1.** **Given** a QR Code whose discount window ends at T
**When** the payer fetched the discounted amount before T and its approval request reaches the payee
after T
**Then** it is refused with 400 on `paymentNotification.data.payment.amount`, naming the adjusted
and face amounts
**And** a new request with the face amount is accepted.

**S1.3-AC2.** **Given** a payer whose clock runs behind the payee's
**When** its approval request arrives after `validUntil` on the payee's clock
**Then** it is refused with 400 on `validUntil`.

**S1.3-AC3.** **Given** an approval request arriving at exactly `validUntil`
**Then** it is accepted; only after `validUntil` is it refused.

**S1.3-AC4.** **Given** an approval request or payload fetch at exactly `dueDate`
**Then** the expected amount is the full amount with no late fee, and no `IllegalCallerException`
is thrown
**And** a unit test pins `dueDate − 1 ms` (same as `dueDate` unless a discount window still covers
it), `dueDate` and `dueDate + 1 ms` (late fee applies).

**S1.3-AC5.** **Given** `official-spec/INTERPRETATION.md`
**Then** one entry states: the payee's clock at receipt decides validity and amount; the payer's
check is advisory; a payment up to and including `dueDate` is on time.

Unit tests cover AC1–AC4 in this story. Their black-box versions need the test clock (Epic 2) and
land with the time edges in Epic 4.


## Epic 2: Time can be tested, conditional requests are right, and a yes is signed

These are the last functional changes to Java before the freeze. Each story is one PR.

### Story 2.1: A test deployment's clock can be moved, and a production one's cannot

As a **maintainer writing time-boundary tests**,
I want to move the clock of a test deployment over HTTP,
So that "fetch at 23:59:50, approve after midnight" runs in seconds, not overnight.

**Implements:** FR3; ADR-0033

**Acceptance Criteria:**

**S2.1-AC1.** **Given** `X9_TESTING_CLOCKCONTROL=true` and a demo or test-CA signing certificate
**When** the app starts
**Then** `PUT/GET/DELETE /test/clock` is served only on `X9_TESTING_CLOCKLISTEN` (default
`127.0.0.1:8099`), never on the API port
**And** a WARN is logged at startup.

**S2.1-AC2.** **Given** the flag is on but the certificate's SHA-256 fingerprint is not a bundled
demo or test-CA one
**Then** the app refuses to start.

**S2.1-AC3.** **Given** the flag is off or unset
**Then** nothing listens on the clock address, and `/test/clock` on the API port answers 404.

**S2.1-AC4.** **Given** an offset is set
**Then** all four time reads use it: `DateTimeUtils.nowUTC()`, both `Clock.systemUTC()` reads in
`JwsQRCodeSignatureService`, and `RetrieveQRCodePayloadInput`
**And** a JWS signed under the offset carries an `iat` that includes it.

**S2.1-AC5.** **Given** a non-zero offset
**Then** every API response carries `X-Test-Clock-Offset`
**And** `DELETE` restores real time and the header disappears.

### Story 2.2: The ETag uses the effective status, so a lapsed reservation doesn't answer 412

As an **integrator using `If-Match`**,
I want the ETag I'm given to be the one the server compares against,
So that a conditional request doesn't fail just because a reservation lapsed.

**Implements:** FR4; ADR-0028

**Acceptance Criteria:**

**S2.2-AC1.** **Given** a QR Code in `PAYMENT_INITIATED` whose reservation has lapsed (effective
status `ACTIVE`)
**When** GET returns its ETag and a PATCH sends it back in `If-Match`
**Then** the PATCH succeeds instead of answering 412.

**S2.2-AC2.** **Given** the code
**Then** one function builds the ETag from the effective status, and every `If-Match` comparison
calls it.

**S2.2-AC3.** **Given** a stale ETag (another revision)
**Then** the request still answers 412.

### Story 2.3: Every answer to a payment notification is signed, and we verify the answers we receive

As a **payer's PSP**,
I want the payee's yes to my payment notification to be a signed JWS linked to my signed question,
So that I hold evidence of what was authorized and when. Settlement and reconciliation of the funds
stay with the system that receives them.

Today Java answers `ResponseEntity.ok().build()` (empty, unsigned), contrary to the ANSI X9.150 A.4.5
note, and its outbound sender passes the payee's answer through unverified.

**Implements:** FR33; ADR-0027, ADR-0028

**Acceptance Criteria:**

**S2.3-AC1.** **Given** an accepted payment notification, on pre-commit or post-commit
**Then** the 200 response is a compact JWS with body `{"statusCode": 200}`, signed with the payee's
X9 key
**And** its `correlationId` equals the notification's, and its `iat` is the payee's clock at the
decision.

**S2.3-AC2.** **Given** a refused notification (wrong amount, expired, another holder)
**Then** the response keeps its HTTP status (400 / 409) and is a JWS whose body is
`{"statusCode": <status>}` plus today's error fields (`type`, `title`, `detail`, `violations`),
`Content-Type: application/jose`, `correlationId` echoing the question's
**And** when the question cannot be read at all (not a JWS, signature invalid), the refusal is still
signed, with a fresh `correlationId`, because there is none to echo.

**S2.3-AC3.** **Given** an approval signed at 23:59:50 for the discounted amount
**When** the `SENT` report arrives at 00:00:05
**Then** it is recorded as reported, without re-checking amount or time, and answered with a signed
yes.

**S2.3-AC4.** **Given** the payer's signed notification and the signed yes
**Then** a verifier with only public certificates links them by `correlationId` and reads the
amount, currency and decision instant.

**S2.3-AC5.** **Given** the response body changes
**Then** ADR-0028 gets a dated amendment (a second deliberate Java fix before the freeze)
**And** `INTERPRETATION.md` records the answer format as the spec's A.4.5 model.

**S2.3-AC6.** **Given** this service sends a payment notification and the payee answers 200 with a
JWS
**Then** the JWS is verified against the payee's X9 certificate, its `correlationId` must equal the
one sent, and the verified `statusCode` is what is reported
**And** a 200 with an empty or unverifiable body is reported as an unsigned yes, not refused.

### Story 2.4: Pre-commit works on every rail, so two payers cannot pay the same QR Code

As a **payee**,
I want every rail to offer a pre-commit (the reservation) and to tell pre-commit from post-commit by the same rule,
So that two payers cannot pay the same QR Code at the same time, whatever rail they use.

Today: FedNow/RTP require an End-to-End ID on every notification
(`QRCodeEntityValidator.validatePaymentNotificationFromInstantPayments`), so they have no pre-commit;
ACH gates every notification as a pre-commit, even with a Trace Number.

**Implements:** FR34; ADR-0036, ADR-0021

**Acceptance Criteria:**

**S2.4-AC1. FedNow/RTP pre-commit.**
**Given** an `ACTIVE` QR Code with a FedNow or RTP method
**When** a payer sends a notification without a `transactionId`
**Then** it goes through the acceptance gate at the payee's clock (Story 1.3)
**And** on acceptance the QR Code moves to `PAYMENT_INITIATED` held by that payer,
`payment.initiated` is emitted, and the answer is a signed yes (Story 2.3).

**S2.4-AC2. The race.**
**Given** payer A holds the reservation, on any rail
**When** payer B sends a pre-commit
**Then** B gets 409 and nothing changes
**And** a concurrency test fires A and B together N times: exactly one wins each time
**And** a post-commit from B while A holds is accepted and changes nothing: A keeps the reservation,
and on Solana a `payment.sent` registers B's hash with `deviations: ["RECEIVED_IN_WRONG_STATUS"]` and
`receivedInStatus: "PAYMENT_INITIATED"` (ADR-0036); today's 409 is gone
**And** INTERPRETATION states this: the only payment X9.150 authorizes is the one that won the pre-commit.

**S2.4-AC3. Post-commit on the bank rails.**
**Given** a notification with a `transactionId` (End-to-End ID or Trace Number) from the holder
**Then** it is recorded, status unchanged, with no amount or time check, and answered with a signed
yes; no `payment.sent` (bank rails never emit it).

**S2.4-AC4. A post-commit on an `ACTIVE` QR Code reserves it.**
**Given** an `ACTIVE` QR Code (effective status, so a lapsed reservation counts)
**When** any rail's post-commit arrives (a payer who skipped the pre-commit)
**Then** it is accepted, never judged on amount or time, and answered with a signed yes
**And** in one transaction the QR Code moves to `PAYMENT_INITIATED`, held by that payer, and
`payment.initiated` is published; on Solana `payment.sent` follows, registering the hash ("if this
hash arrives, it is for this QR Code"); bank rails publish no `payment.sent`
**And** both events carry `deviations: ["RECEIVED_IN_WRONG_STATUS"]` and `receivedInStatus: "ACTIVE"`,
and `schemaVersion` is `1.1` (ADR-0036)
**And** another payer's pre-commit is then refused with 409
**And** this reservation never lapses (Story 2.5, S2.5-AC6).

**S2.4-AC5. Solana follows the evidence, and a contradiction is recorded loudly.**
**Given** a Solana notification
**When** `action = PAYMENT_INITIATED` carries a transaction hash
**Then** it is a post-commit: recorded as received (its declared `action` included), never judged on
amount or time, answered with a signed yes, and logged as a warning naming the payer
**And** a code comment and INTERPRETATION say plainly that this is bad payer behaviour, and that the
hash is kept because funds that arrive in the right amount with a matching hash are enough for the
payment system to mark the QR Code paid
**And** its events carry `ACTION_CONTRADICTS_HASH` in `deviations` (with `RECEIVED_IN_WRONG_STATUS`
too when the QR Code was not `PAYMENT_INITIATED` held by that payer)
**And** `SENT` without a hash is still refused with 400, and `NOT_SENT` still releases.

**S2.4-AC6. Every transaction hash is recorded.**
**Given** the holder has already reported a hash
**When** it reports another one
**Then** the second is stored beside the first, never overwriting it, and registered with the
payment system as its own `payment.sent` (on Solana; the outbox event is the registration)
**And** X9.150 never marks the QR Code paid itself: the payment system matches each hash against the
funds and calls the status-update API, and two hashes for one QR Code are its signal of a duplicate
payment.

**S2.4-AC7. A post-commit on a closed QR Code is registered, and changes nothing.**
**Given** a QR Code already `PAID` or `CANCELLED`
**When** a post-commit with a hash arrives, from anyone
**Then** it is accepted with a signed acknowledgement, never judged on amount or time
**And** on Solana a `payment.sent` registers the hash with `deviations: ["RECEIVED_IN_WRONG_STATUS"]`
and `receivedInStatus` `PAID` or `CANCELLED`; bank rails publish no `payment.sent`
**And** the status does not change: a closed QR Code never reopens
**And** today's 400 is gone.

**S2.4-AC8. Every notification records the status it found.**
**Given** any accepted notification
**Then** it is stored with the QR Code's effective status when it arrived and its `deviations`
(`received_in_status_code` and `deviations` in Go; in Java, on the event as `receivedInStatus` and
`deviations`)
**And** a test lists, per payer, the post-commits flagged `RECEIVED_IN_WRONG_STATUS`, including one
received while another payer held the QR Code.

**S2.4-AC9. Docs.**
**Then** STATE-MACHINE carries ADR-0036's message-by-status matrix, its happy-path sequence diagram
and its unhappy-path state diagram, replacing today's per-network tables
**And** INTERPRETATION and the ADR-0035 transition map get the new rows, and the deviation recorded by
S1.1-AC4 is closed
**And** the black-box suite has one test per cell of the matrix (Story 4.3).

### Story 2.5: The same payer can retry, a refused retry frees the QR Code, and a takeover is visible

As a **payer recovering from a failure**, and as a **payee**,
I want my own retried pre-commit accepted or refused on its merits, and a refusal to free the QR Code,
So that a lost answer can be retried, and a refused payer does not keep everyone else out.

Today Java accepts the holder's retry (`validateAnnouncement`, `ReservationHolderVO.isSameParty`)
and judges it again at the payee's clock (correct). Two gaps: a refused retry leaves the reservation
in place until it lapses, and a takeover of a lapsed reservation emits no event.

**Implements:** FR35; ADR-0036, ADR-0019, ADR-0021

**Acceptance Criteria:**

**S2.5-AC1. A retry is judged again.**
**Given** payer A holds the reservation, approved at 23:59:50 with the discounted amount
**When** A retries its pre-commit at 00:00:02 with the same amount
**Then** it is refused with 400 (the discount has ended): the payee's clock decides (Story 1.3).

**S2.5-AC2. A refused retry releases the reservation.**
**Given** the refusal in AC1
**Then** in the same transaction the QR Code goes back to `ACTIVE` and the reservation is cleared,
emitting what a release emits on that rail (Solana: `payment.failed`; bank rails: none)
**And** A may send a new pre-commit with the face amount and be accepted
**And** a refused pre-commit from a non-holder, or on an `ACTIVE` QR Code, still changes nothing.

**S2.5-AC3. An accepted retry refreshes the window.**
**Given** A retries within the discount window
**Then** it is accepted with a signed yes, the window moves, and no event is emitted.

**S2.5-AC4. Identity is `payer.info` plus the certificate subject.**
**Given** two notifications signed by the same certificate
**Then** with different `payer.info` they are different payers (409 for the second)
**And** with `payer.info` absent on either, they are **different** payers: without a means to tell
them apart, X9.150 assumes they are not the same (ADR-0036, replacing ADR-0021's certificate-only
fallback in `ReservationHolderVO.isSameParty`)
**And** so a holder without `payer.info` cannot retry (409) or release with `NOT_SENT` (refused), and
its post-commit is registered with `RECEIVED_IN_WRONG_STATUS` and changes nothing; tests pin each
**And** INTERPRETATION and BEST-PRACTICES tell PSPs that sending `payer.info` is what lets their
payers retry, release and report cleanly.

**S2.5-AC5. A takeover is evented.**
**Given** A's reservation lapsed (stored status still `PAYMENT_INITIATED`)
**When** B wins the pre-commit
**Then** `payment.initiated` is emitted for B, because the transition is decided on the effective
status, not the stored one
**And** the test asserts the events, not only that nothing was thrown.

**S2.5-AC6. A registered hash stops the window.**
**Given** a reservation, held by anyone
**When** any post-commit is registered for the QR Code, from the holder or not
**Then** `reserved_until` takes the "no lapse" sentinel and the holder does not change
**And** the QR Code still reads `PAYMENT_INITIATED` long after the window would have passed
**And** only the status-update API ends it: `PAID`, `CANCELLED`, or `ACTIVE` (the payment system's
revert, which clears the reservation).

**S2.5-AC7. `ttl-seconds: 0` hands the window to the bank.**
**Given** `x9.reservation.ttl-seconds` set to `0`
**Then** an accepted pre-commit takes a reservation that never lapses; it ends only by a refused
retry, `NOT_SENT`, or the status-update API
**And** today's behaviour (every reservation lapses at once) is gone
**And** the default stays `90`; `application.yml`, the Helm values and the OpenAPI 409 description
say what `0` means, under the same environment variable for Java and Go.

**S2.5-AC8. Docs and map.**
**Then** STATE-MACHINE, INTERPRETATION and the ADR-0035 transition map get the "refused retry
releases", "takeover emits", "a hash stops the window" and "`0` means no window" rows.

### Story 2.6: The amount's advertised `validUntil` is the instant it stops applying

As a **payer**,
I want the `validUntil` signed next to a discounted or late-fee amount to be exactly when that
amount stops applying,
So that an amount the payee signed as valid is never refused while its signature says it is valid.

Today `FixedDiscountLateFeeLinearInterestFormulaService.calculateValidUntil` truncates to 23:59:59 UTC
of the day: the discount stops at E = `dueDate − daysBefore` but is advertised until V (up to ~21 h
later), and a late fee rises every 24 h from `dueDate` but is advertised until the end of the
calendar day.

**Implements:** FR36

**Acceptance Criteria:**

**S2.6-AC1. Discount.**
**Given** a discount with `daysBefore` = N and due date D
**When** the payload is fetched before E = D − N days
**Then** the discounted amount's `validUntil` is exactly E (capped by the method's and the QR Code's
`validUntil`)
**And** a pre-commit with that amount is accepted at E and refused at E + 1 ms (face amount still
accepted).

**S2.6-AC2. Late fee.**
**Given** the payload fetched k days late (k = ceil((t − D) / 24 h))
**Then** the fee amount's `validUntil` is exactly D + k × 24 h, capped as above
**And** a pre-commit with that amount is accepted at that instant and refused 1 ms later.

**S2.6-AC3. Several discounts.**
**Given** several discounts
**Then** the amount served is the largest eligible one and its `validUntil` is that discount's E.

**S2.6-AC4. No more truncation.**
**Given** the code
**Then** no `withHour(23).withMinute(59).withSecond(59)` remains in the formula, and unit tests pin
both edges.

**S2.6-AC5. Docs.**
**Then** INTERPRETATION records the rule ("the payee honours what it signed") and plan §4.2 T1/T3/T5
expected results are updated.

## Epic 3: Any deployment can be checked from outside

Today `others/blackbox/` (52 pytest tests) and `others/acceptance/acceptance.py` (10 sections) both
sign payer messages with the service under test (`POST /api/v1/signature/generate`, the payee's own
key), and neither runs in CI. Order: 3.1 → 3.2 → (3.3, 3.4, 3.5) → 3.6.

### Story 3.1: `x9client` holds the protocol, and the existing tests use it

As a **suite author**,
I want one package that speaks X9.150 (HTTP, EMV, the clock, events, optional database inspectors),
So that tests read like scenarios, not plumbing.

**Implements:** FR5; ADR-0034

**Acceptance Criteria:**

**S3.1-AC1.** **Given** `others/blackbox/x9client/` with `api`, `emv`, `clock`, `events` and `db/`
**Then** `api` handles `ETag`/`If-Match` and problem+json on the standard library, `emv` decodes TLV
and checks CRC16, `clock` offers `move_to`/`now`/`reset`, and `events` reads by cursor with dedupe.

**S3.1-AC2.** **Given** the existing 52 tests moved onto `x9client`
**Then** all 52 pass against Java, none skipped.

**S3.1-AC3.** **Given** `X9_DATABASE_URL` unset, or its driver extra not installed
**Then** database checks are skipped with a stated reason; API checks are unaffected.

**S3.1-AC4.** **Given** a deployment with the clock off
**Then** time tests are skipped with "test clock not enabled", not failed.

### Story 3.2: The suite signs as independent payers, and checks the answers it gets

As a **suite author**,
I want payer messages signed with the payers' own test-CA keys,
So that signature refusals, holder rules and the payee's signed answers are tested as in the field.

**Implements:** FR5; ADR-0034

**Acceptance Criteria:**

**S3.2-AC1.** **Given** `x9client.jws` and the pinned `cryptography` dependency
**Then** it signs with the X9.150 profile (ms `iat`/`ttl`, `correlationId`, `crit`, `x5c`) as two
distinct test-CA payer identities, A and B
**And** a PEP 723 header lets `uv run` work, and `make blackbox` keeps working.

**S3.2-AC2.** **Given** the tamperer
**Then** it produces: wrong key, untrusted CA, expired `iat + ttl`, `iat` in the future, missing or
unknown `crit`, missing `x5c`, altered payload, `alg: none`
**And** each is refused, naming the reason.

**S3.2-AC3.** **Given** A holds a reservation
**When** B announces (pre-commit) or sends `NOT_SENT` with its own key
**Then** it is refused with 409
**And** when B reports a payment (post-commit) with its own key, it is accepted, A keeps the
reservation, and the hash is registered flagged `RECEIVED_IN_WRONG_STATUS` (ADR-0036).

**S3.2-AC4.** **Given** any answer from the service to a notification
**Then** `x9client.jws` verifies it against the payee's certificate and checks its `correlationId`
against the question's.

**S3.2-AC5.** **Given** the suite
**Then** no test calls `/api/v1/signature/generate` to sign as a payer.

**S3.2-AC6.** **Given** an accepted notification
**Then** the answer is 200, `Content-Type: application/jose`, a JWS whose body is exactly
`{"statusCode":200}`, whose `correlationId` equals the question's and whose `iat` is the test
clock's instant within 1 s.

**S3.2-AC7.** **Given** a refused notification (each refusal reason in Epic 2)
**Then** the answer keeps its HTTP status and is a JWS whose `statusCode` equals it, with the error
fields inside, and a `correlationId` equal to the question's (fresh when the question was
unreadable).

**S3.2-AC8.** **Given** the same signed question sent twice (same `correlationId`)
**Then** the suite pins today's answer; refusing replays would be a separate interpretation entry.

### Story 3.3: The contract writes the field-refusal tests

As a **maintainer adding a field**,
I want its length, pattern and required refusals generated from `openapi.yaml`,
So that a new field is tested without anyone typing the tests.

**Implements:** FR6; ADR-0034

**Acceptance Criteria:**

**S3.3-AC1.** **Given** `contract.py`, reading `openapi.yaml` with pinned `pyyaml`
**Then** it generates one refusal case per `maxLength`, `minLength`, `pattern` and `required` in the
request schemas, each asserting 400, the field's path, and no state change
**And** ADR-0034's dependency table gets a dated amendment adding `pyyaml`.

**S3.3-AC2.** **Given** a constraint the service does not enforce today
**Then** the case is listed in a known-gaps file with a reason, never silently dropped.

**S3.3-AC3.** **Given** a run
**Then** it reports how many cases were generated, executed and skipped.

### Story 3.4: `acceptance.py` is folded in, section by section

As a **maintainer**,
I want one suite instead of two,
So that "the deployment works" has one answer.

**Implements:** FR9

**Acceptance Criteria:**

**S3.4-AC1.** **Given** the 10 sections of `acceptance.py`
**Then** each becomes a test module, and each fold deletes the section it replaced.

**S3.4-AC2.** **Given** the last section folded in
**Then** `acceptance.py` and `make acceptance` are deleted, and the publish script calls
`make blackbox` only.

### Story 3.5: A test payee answers right and wrong, and the service tells them apart

As a **payer's PSP using this service to notify**,
I want every malformed or mismatched answer from a payee flagged,
So that I never mistake a forged, replayed or unsigned answer for authorization.

The outbound result (`OutboundPaymentNotificationResultDTO`: `statusCode`, `accepted`, `body`) gains
`signature` = `VERIFIED` / `ABSENT` / `INVALID` and `signatureProblem` (additive in the OpenAPI;
built in Story 2.3, AC6). `signatureProblem` is a fixed code from a closed list (`CORRELATION_ID_MISMATCH`,
`BAD_SIGNATURE`, `UNTRUSTED_CHAIN`, `EXPIRED`, `FUTURE_IAT`, `STATUS_MISMATCH`, ...; the list is in
the OpenAPI), with the words in `signatureDetail` (Story 2.3 follow-up), so the suite asserts on the
code. Order note: this story precedes CI (3.6), which runs it.

**Implements:** FR5, FR33

**Acceptance Criteria:**

**S3.5-AC1.** **Given** `x9client.peer`, a test payee on its own test-CA identity, set as the QR
Code's notification endpoint
**When** the suite triggers a pre- or post-payment notification through this service
**Then** the test payee captures the exact JWS it received.

**S3.5-AC2.** One test per answer, each asserting `statusCode`, `accepted`, `signature` and
`signatureProblem`:

| # | The test payee answers | Expected `signature` |
|---|---|---|
| 1 | 200, signed `{"statusCode":200}`, matching `correlationId` | `VERIFIED` |
| 2 | 200, signed, wrong `correlationId` | `INVALID`, correlation mismatch |
| 3 | 200, signed, no `correlationId` header | `INVALID` |
| 4 | 200, a valid yes replayed from another notification | `INVALID`, correlation mismatch |
| 5 | 200, empty body | `ABSENT` |
| 6 | 200, unsigned JSON `{"statusCode":200}` | `ABSENT` |
| 7 | 200, a body that is not a JWS | `ABSENT` |
| 8 | 200, signed, payload altered after signing | `INVALID`, signature |
| 9 | 200, signed by a certificate outside the trusted chain | `INVALID`, untrusted |
| 10 | 200, signed by a revoked certificate | `INVALID`, revoked |
| 11 | 200, signed, `iat + ttl` expired, or `iat` in the future | `INVALID`, time |
| 12 | 200, `alg: none` | `INVALID` |
| 13 | 200, signed, unknown `crit` header | `INVALID` |
| 14 | HTTP 200 but signed `statusCode` 400, or the reverse | `INVALID`, status mismatch |
| 15 | 400, plain JSON error | `ABSENT`; `accepted` false; body passed through |
| 16 | 400, signed `{"statusCode":400}`, matching `correlationId` | `VERIFIED`; `accepted` false |
| 17 | no answer within the timeout | 502, as today |

**S3.5-AC3.** **Given** any `INVALID` or `ABSENT` case
**Then** nothing in the QR Code's state or events changes because of the answer; the service only
reports.

**S3.5-AC4.** **Given** case 10 needs a revoked certificate
**Then** the test CA publishes a CRL with a revoked payee certificate, added by this story if
missing.

### Story 3.6: The suite runs in CI against Java

As a **reviewer of any PR**,
I want the black-box suite to run on every PR,
So that a behaviour change is caught before merge, not at publish.

**Implements:** FR13

**Acceptance Criteria:**

**S3.6-AC1.** **Given** a PR
**Then** a CI job builds the image, starts it with a MongoDB replica set, the clock flag on and the
test-CA keystore, and runs the suite, inside the compose network so the test payee (Story 3.5) is
reachable from the service.

**S3.6-AC2.** **Given** a suite that could not run (service down, zero tests collected)
**Then** the job fails; it does not skip.

**S3.6-AC3.** **Given** the existing `build` job
**Then** it is unchanged, and the publish gate still runs the suite.

## Epic 4: Every rule is pinned

The catalogue of plan §4.1 and the time edges of §4.2, as tests against Java, with expected results
updated by the owner's decisions of 2026-11-13 (Stories 1.3 and 2.3–2.6). Order: 4.1 first (every
other group asserts events against the map), then 4.2–4.5 in parallel, then 4.6 and 4.7.

### Story 4.1: The transition map is verified against Java, one test per row

As a **Go implementer**,
I want ADR-0035's transition map proven against the running Java build,
So that the Go code ports a map that is true, not one that was believed.

**Implements:** FR7 (group I), FR11; ADR-0035

**Acceptance Criteria:**

**S4.1-AC1.** **Given** every row of the ADR-0035 map, including the rows added by Stories 2.4 and
2.5 (FedNow/RTP pre-commit, post-commit holder-only, refused retry releases, takeover emits)
**Then** one black-box test per row asserts the resulting status, the events emitted (exactly, in
order) and what did not change.

**S4.1-AC2.** **Given** a row Java contradicts
**Then** the ADR gets a dated amendment before any Go rule code is written (suite first), or the
Java bug gets a story; never silently.

**S4.1-AC3.** **Given** event bodies
**Then** each test checks that the body comes from the last stored notification, else from the first
payment method.

### Story 4.2: Payment requests are pinned: create, PATCH, ETag, rebind

As an **integrator creating and editing payment requests**,
I want every refusal and limit of create and PATCH pinned,
So that the Go build cannot accept what Java refuses, or the reverse.

**Implements:** FR7 (groups A, B, B2, B3)

**Acceptance Criteria:**

**S4.2-AC1. Create (group A), beyond `contract.py`.**
**Then** tests cover: unsupported network named in the 400; unsupported and wrong-case currency
(`usd`); a missing currency (no default); currencies from two peg groups; duplicate currency;
`validUntil` in the past or exactly now; `dueDate` after `validUntil`; a method's `validUntil` after
the QR Code's; tip `allowed: false` with a range and `true` with neither a range nor presets; a negative amount; a
19-digit amount refused, 18 nines accepted (ADR-0005); a host too long for EMV tag 26.

**S4.2-AC2. PATCH (group B).**
**Then** tests cover: non-patchable fields never applied; adding or dropping a currency (ADR-0018);
a no-op patch refused; patching while `PAYMENT_INITIATED`, `PAID`, `CANCELLED`; a valid patch moves
`revision`, a status move does not (ADR-0016).

**S4.2-AC3. ETag (group B2).**
**Then** tests cover: `"<revision>-<STATUS>"`; `If-Match` with and without `W/` and quotes; stale
revision and stale status → 412; after a lapsed reservation the ETag reads `ACTIVE` and is accepted.

**S4.2-AC4. Location rebind (group B3).**
**Then** tests cover: same creditor, holder `PAID`/`CANCELLED`/expired-`ACTIVE` → accepted, the
holder moves to a fresh location and `/loc/{old}` resolves only the new code; holder live → refused;
different creditor → refused; no event from the release.

### Story 4.3: Payers are pinned: payload, notifications, payer-side endpoints

As a **payer's PSP**,
I want every way of fetching, notifying and being refused pinned,
So that payers see the same service on both builds.

**Implements:** FR7 (groups C, E, H)

**Acceptance Criteria:**

**S4.3-AC1. Payload (group C).**
**Then** tests cover: unknown location; `qrCodeContent` not byte-equal to the issued EMV; bad
signature, expired `iat + ttl`, missing or unknown `crit`, revoked certificate (CRL and OCSP),
untrusted CA, unreachable revocation source (soft-fail); expired QR Code; an expired method dropped
while others stay; `PAID` and `CANCELLED` codes.

**S4.3-AC2. Notifications (group E), under the pre-commit / post-commit rule.**
**Then** per rail: pre-commit reserves (FedNow, RTP, ACH, Solana); post-commit from the holder recorded; post-commit
on `ACTIVE` reserves and registers the hash; wrong amount; tip outside its range; tip on top of the bill (ADR-0017);
unoffered currency; destination not on the QR Code; rail and currency in another case (accepted,
echoed); second payer → 409; holder re-announces (refresh, no event); `NOT_SENT` releases; `SENT`
never marks `PAID`; no `paymentNotification` or `kind: EXTERNAL`; every answer signed (Story 2.3)
**And** 32 concurrent pre-commit announcements → exactly one 200 and one `payment.initiated`.

**S4.3-AC3. Payer-side endpoints (group H).**
**Then** tests cover: the payee refuses → 200 + `accepted: false`; the payee is unreachable → 502; a
pre-payment carrying a `transactionId` is refused before sending (ADR-0015); the signature verdicts
of Story 3.5.

### Story 4.4: Status update and the event stream are pinned

As an **event consumer**,
I want every status transition and every property of the event stream pinned,
So that my integration keeps working across the cutover.

**Implements:** FR7 (groups F, G)

**Acceptance Criteria:**

**S4.4-AC1. Status update (group F).**
**Then** the full transition matrix, including `PAID` after expiry and after a lapsed reservation
(ADR-0020), `CANCELLED` final, `PAYMENT_INITIATED` through status-update (no payer can take it
over), and a stale `If-Match`.

**S4.4-AC2. Events (group G).**
**Then** every transition emits exactly its event; per-QR order; no gaps across pages; `wait` above
30 refused with 400 naming `wait` (the contract's `maximum: 30`); `hasMore`; replay from an old cursor is safe by `eventId`.

### Story 4.5: The time edges are pinned on the test clock

As a **payee**,
I want every boundary in time tested at the instant it falls,
So that "valid until" means the same on both builds, to the millisecond.

**Implements:** FR8; ADR-0033

**Acceptance Criteria:**

**S4.5-AC1.** **Given** the serial stage and `x9client.clock`
**Then** T1–T12 of plan §4.2 exist as tests, each resetting the clock at the end, with these
expected results:

| # | Expected |
|---|---|
| T1/T3 | one edge: the discount is served, advertised and accepted up to and including E, refused 1 ms after (Story 2.6) |
| T2 | post-commit after the edge, from the holder: recorded, signed yes, no re-check |
| T4 | at exactly `dueDate`: full amount, no fee (Story 1.3) |
| T5 | late fee per 24 h from `dueDate`; advertised `validUntil` = the next boundary (Story 2.6) |
| T6 | pre-commit after `validUntil` refused; the payee may still mark it `PAID` (ADR-0020) |
| T7 | an expired method is absent from the payload and refused in a notification; others served |
| T8 | after a lapse, B's pre-commit accepted with `payment.initiated` (Story 2.5); A's late `SENT` registered, flagged `RECEIVED_IN_WRONG_STATUS`, and B keeps the QR Code (ADR-0036) |
| T9 | a reservation taken at `validUntil − 30 s` ends at `validUntil` |
| T10 | a payer `iat` 10 min old with a 5-min `ttl`: refused before any lookup |
| T11 | per build: Go keeps the expired code (payload refused with the expiry reason, GET works); Java's TTL deletion runs on the real clock, so it is asserted by waiting real time or marked `java_only` |
| T12 | Go only: retention purges events, then QR Codes, by the test clock |

**S4.5-AC3.** **Given** T11 (Go half) and T12
**Then** they are written now and marked `go_only`: against Java they skip with that reason, and
they first run in Epics 11–12.

**S4.5-AC2.** **Given** the clock-skew rule (Story 1.3)
**Then** a test moves the service clock ahead of the suite's and shows the payee's clock decides.

### Story 4.6: Every rule points to a test

As a **maintainer**,
I want a table from every documented rule to the tests that prove it,
So that a rule without a test is visible, not assumed covered.

**Implements:** FR10

**Acceptance Criteria:**

**S4.6-AC1.** **Given** STATE-MACHINE, ENDPOINTS, INTERPRETATION, ADRs 0004–0021 and the ADR-0035
map
**Then** `docs/TEST-INDEX.md` (or a section of it) maps every rule to at least one test and to the
acceptance-criterion IDs it proves.

**S4.6-AC2.** **Given** a check script in CI
**Then** it fails when a test cites an unknown criterion ID, or a rule row has no test.

### Story 4.7: Golden JWS and EMV sets are exported from Java

As a **Go implementer**,
I want Java's signed, tampered and encoded artifacts committed as fixtures,
So that Go's crypto and EMV are checked against the real thing before any endpoint exists.

**Implements:** FR12; ADR-0027

**Acceptance Criteria:**

**S4.7-AC1. JWS.**
**Then** fixtures cover: valid, tampered, expired, missing and unknown `crit`, wrong `typ`, revoked,
untrusted, EC keys, and the signed answers of Story 2.3 (yes and refusal).

**S4.7-AC2. EMV.**
**Then** strings for representative hosts and locations, with their expected decoding and CRC.

**S4.7-AC3.** **Given** the fixtures
**Then** they are produced by a documented, re-runnable export, carry test-CA keys only, and are
committed under `others/blackbox/golden/`.

## Epic 5: A measured baseline for "lighter"

The harness can be built alongside Epics 3–4. The baseline is captured on the frozen Java, after
Epic 2, so both builds do the same crypto (signed answers and their verification).

### Story 5.1: The k6 harness runs S1, S2 and S7 against either build

As the **owner**,
I want one harness that drives the same demand against Java or Go,
So that the comparison is the same test twice, not two tests.

**Implements:** FR14

**Acceptance Criteria:**

**S5.1-AC1.** **Given** S1
**Then** it creates only, with today's README profile (ramp to 500 VUs, 3 min sustain).

**S5.1-AC2.** **Given** S2
**Then** each VU runs create → signed payload fetch → pre-commit → post-commit (Solana, with a
`transactionId`) → status-update `PAID`, signing payer messages in k6 with WebCrypto as test-CA
payers
**And** every answer's signature and `correlationId` is checked; a failed check counts as an error.

**S5.1-AC3.** **Given** S7
**Then** it records image size, cold start to `/actuator/health` UP, and resting memory after 5 idle
minutes.

**S5.1-AC4.** **Given** the Kubernetes manifests
**Then** they set the plan §9.2 limits (app 2 vCPU / 2 GiB, database 2 vCPU / 2 GiB, k6 a Job with
its own 2 vCPU, in-cluster) and durability parity (MongoDB `w:majority` journaled, PostgreSQL
`synchronous_commit=on`).

**S5.1-AC5.** **Given** `collect.sh`
**Then** it runs the interleaved J-G-J-G-J-G sequence and writes raw results plus image tags
(`git-<sha>`), versions and resource settings.

**S5.1-AC6.** **Given** anything committed
**Then** it does not name the host, its network or its location; the environment is described
generically (CPU, architecture, virtualisation layers), per NFR2.

### Story 5.2: A Java + MongoDB baseline is committed

As the **owner**,
I want the Java numbers captured while Java is still the live build,
So that `BENCHMARK.md` (Epic 15) compares against a measured baseline, not a remembered one.

**Implements:** FR14

**Acceptance Criteria:**

**S5.2-AC1.** **Given** the frozen Java image after Epic 2
**Then** the baseline is run against it and identified by its `git-<sha>` tag.

**S5.2-AC2.** **Given** S1, S2 and S7
**Then** each runs 3 times; median, min and max are committed under
`others/benchmark/results/java-mongo/` with the raw k6 output.

**S5.2-AC3.** **Given** the JVM warm-up
**Then** the excluded 2 minutes are recorded in the results.

**S5.2-AC4.** **Given** a run
**Then** it starts from an empty database volume, with nothing else running on the host.

## Epic 6: A bank's DBA can host X9.150 in its own PostgreSQL

Runs in parallel with track A. **The whole schema is created here, not table by table**, by
design: the DBA's review of one complete schema, its roles and its grants is this epic's value
(ADR-0032), and the tables are fixed by the accepted ADR-0024/0025/0026, not discovered later. The
same reasoning covers the retention purge's grant in 6.2. The schema conventions bind from the first
changeset: `TIMESTAMP` in UTC,
sentinels instead of NULL, `BIGINT` minor units, holder encoding `''`. Order: 6.1 → 6.2 → 6.3, with
6.4 alongside 6.1, and 6.5 (the integrators' document) once 6.2 exists.

### Story 6.1: The changelog creates the schema of ADR-0024, documented in the database itself

As a **bank's DBA**,
I want every table and column created by a reviewable changelog and described by a `COMMENT`,
So that I can understand what X9.150 stores without reading Go code.

**Implements:** FR15; ADR-0024, ADR-0025, ADR-0026, ADR-0037

**Acceptance Criteria:**

**S6.1-AC1.** **Given** `db/changelog/` applied to an empty schema
**Then** it creates `qr_code`, `qr_code_audit_log`, `qr_code_notification`, `payment_event`,
`event_sequence` (one row, `last_seq = 0`) and `payment_event_legacy_cursor`, with the columns of
ADR-0024/0025/0026 (including the reservation holder `reserved_by_payer_info` and
`reserved_by_signer_subject`, and the raw compact JWS on `qr_code_notification`).

**S6.1-AC2.** **Given** the lookups `qr_code_status`, `event_type` and `event_read_status`
**Then** they are seeded with every status, event type and read status (`PEND`, `DONE`), and
referenced by FK from `qr_code` and `payment_event`.

**S6.1-AC3.** **Given** the indexes
**Then** `location_id` is unique across all rows, every FK has an index, and
`payment_event(occurred_at)` serves the purge
**And** there is no FK from `payment_event` to `qr_code`.

**S6.1-AC4.** **Given** the catalog
**Then** `obj_description` / `col_description` return a non-empty comment for every table and
column.

**S6.1-AC5.** **Given** the schema name as a changelog parameter (default `x9_qrcode`)
**Then** the changelog writes nothing outside that schema, and nothing in `public`.

**S6.1-AC6. `payment_event` is the outbox (ADR-0037).**
**Given** `payment_event`
**Then** it has `read_status_code` (default `PEND`) and `read_at` (sentinel `9999-12-31 00:00:00`),
an index on `(read_status_code, seq)`, and `deviations` (ADR-0036, `''` when none)
**And** `event_id` is documented as a UUIDv7, roughly in time order but not an order (amended
2026-11-15, owner: `seq` is the only order; ADR-0037)
**And** a pending row can be claimed with `FOR UPDATE SKIP LOCKED` and marked `DONE`, on a real
PostgreSQL.

### Story 6.2: Four roles with the least privilege each needs

As a **bank's DBA**,
I want X9.150's roles to be able to do only their own job,
So that the application can never alter the schema and the test suite can never write.

**Implements:** FR16; ADR-0032

**Acceptance Criteria:**

**S6.2-AC1.** **Given** the changelog
**Then** it runs as, and uses, an owner role the DBA created once with the schema (migrations
only; the changelog does not create it), and it creates (or uses, when the DBA pre-created them) an
application role (DML on its own tables), a read-only role (`SELECT` only) and an event-reader role
(S6.2-AC4), with explicit grants and `ALTER DEFAULT PRIVILEGES`.

**S6.2-AC2.** **Given** the application role
**Then** `ALTER ROLE … SET search_path` points it at the schema
**And** it cannot `CREATE`, `ALTER` or `DROP` anything, nor `DELETE` from append-only tables except
through the retention purge's grant.

**S6.2-AC3.** **Given** the read-only role
**Then** every write fails with a permission error; the black-box suite's database checks use it.

**S6.2-AC4. The event reader (ADR-0037).**
**Given** the event-reader role
**Then** it has `SELECT` on `payment_event` and `UPDATE` on `read_status_code` and `read_at` only
**And** an attempt to update any other column, or to insert or delete, fails with a permission error.

**S6.2-AC5.** **Given** a role name or password
**Then** none is hard-coded; names are changelog parameters and passwords come from the DBA.

### Story 6.3: Anyone can apply the schema locally and in CI, and a DBA can review the SQL first

As a **DBA or a contributor**,
I want one command to apply the schema, and the plain SQL it will run,
So that I can try it locally and review it before production.

**Implements:** FR17; ADR-0023

**Acceptance Criteria:**

**S6.3-AC1.** **Given** compose
**Then** it runs PostgreSQL 16 and a `liquibase/liquibase:4.33.0` one-shot that applies the
changelog and exits 0; a second run applies nothing.

**S6.3-AC2.** **Given** CI
**Then** the changelog is applied on PostgreSQL 16 and on the newest major, and a smoke query checks
the tables, comments, roles and `search_path`.

**S6.3-AC3.** **Given** `liquibase update-sql`
**Then** CI publishes the SQL as a build artifact, so a DBA can review exactly what will run.

**S6.3-AC4.** **Given** `db/README.md`
**Then** it tells a DBA: what is created, the four roles (owner, application, read-only, event
reader; ADR-0037), the connection ceiling (12 from the two pools), PgBouncer ≥ 1.21 in
transaction mode, TLS, and that nothing couples to other schemas.

### Story 6.4: The changelog conventions are enforced, not remembered

As a **reviewer**,
I want CI to refuse a changeset that breaks the conventions,
So that the schema stays portable without anyone policing it by hand.

**Implements:** FR18; ADR-0023

**Acceptance Criteria:**

**S6.4-AC1.** **Given** an edit to an already-applied changeset
**Then** the check fails.

**S6.4-AC2.** **Given** a table or column without a `COMMENT`
**Then** the check fails.

**S6.4-AC3.** **Given** `JSONB`, `ENUM`, arrays, `TIMESTAMPTZ`, unbounded `TEXT`, `SERIAL`, triggers
or procedures
**Then** the check fails, naming the construct and the file.

**S6.4-AC4. Duplicate YAML keys (Epic 6 follow-up).**
**Given** a changelog file in which one mapping repeats a key (YAML keeps the last value and
Liquibase only warns; a repeated `constraints`/`defaultValue` once left a column NULL-able)
**Then** the check fails, naming the file, the changeset, the key and both lines.

### Story 6.5: A payment system can read the event table without asking us

As a **payment system's integrator**,
I want the event table documented as the outbox I read,
So that I can stream it with Debezium or pull it with plain SQL, and know what will never change.

**Implements:** ADR-0037, ADR-0036, ADR-0029

**Acceptance Criteria:**

**S6.5-AC1.** **Given** `docs/EVENTS-TABLE.md`
**Then** it states that X9.150 communicates only by inserting rows into `payment_event`, in the
transition's transaction, and never pushes
**And** it lists every column with its type, meaning and sentinel, matching the database comments.

**S6.5-AC2. The three readers.**
**Then** it shows a Debezium connector configuration that streams inserts only (filtering the
readers' updates and the purge's deletes), the plain SQL claim-and-mark pattern with the
event-reader role, and the HTTP API as the third way
**And** it says the read status serves one logical consumer; another uses its own `seq` cursor.

**S6.5-AC3. Order and identity.**
**Then** it says `seq` is the exact and only order, `event_id` (UUIDv7) is roughly in time order but
not an order across instances (amended 2026-11-15, owner; ADR-0037) and is the dedupe key, and
migrated events keep their original ids.

**S6.5-AC4. What can change.**
**Then** it states the contract: within a major `schemaVersion`, columns and codes are only added;
readers ignore what they do not know
**And** it lists the `deviations` codes and `receivedInStatus`, and what each means
**And** it warns that events are purged after `events.retention` (default `P30D`), read or not.

**S6.5-AC5.** **Given** the black-box suite
**Then** a test reads the table with the event-reader role, claims the pending rows of a fresh
transition, marks them `DONE`, and sees them in `seq` order, with matching `event_id` order for
what one writer wrote (across instances that is not promised: ADR-0037, amended 2026-11-15).

## Epic 7: An operator can run the Go service and see it healthy

No business endpoints yet. Until the cutover (Epic 16) the Java `make` targets keep their names; the
Go targets are `go-build`, `go-test`, `go-image`, renamed at the cutover. No Go image is pushed to
Docker Hub before Epic 14. Order: 7.1 → 7.2 → 7.3; 7.4–7.6 alongside.

### Story 7.1: The service reads one configuration and refuses a bad or old one

As an **operator**,
I want the Go service to start only on a complete, current configuration,
So that a mistake is a clear refusal at start, not a surprise at the first payment.

**Implements:** FR19; ADR-0031

**Acceptance Criteria:**

**S7.1-AC1.** **Given** `cmd/x9-qrcode` with one YAML file plus the documented environment
variables
**Then** environment variables take precedence, and every required key is validated at start with
an error naming it
**And** nothing security-relevant has a silent default.

**S7.1-AC2.** **Given** a Spring-style or MongoDB configuration
**Then** the service refuses to start and names `x9-config-convert`.

**S7.1-AC3.** **Given** any run
**Then** logs are `slog` JSON, and incoming `b3` / `traceparent` ids appear in request logs.

### Story 7.2: The service connects like a polite guest and checks it is in the right schema

As a **bank's DBA**,
I want the service to use bounded pools and verify its schema before serving,
So that it never wanders into the wrong schema or exhausts my connections.

**Implements:** FR19; ADR-0032

**Acceptance Criteria:**

**S7.2-AC1.** **Given** the database configuration
**Then** two pgx pools are opened on the primary (writes 8, reads 4), with
`application_name=x9-qrcode` and configurable TLS.

**S7.2-AC2.** **Given** `current_schema()` differs from the configured schema, or the changelog's
latest applied id differs from the one the binary expects
**Then** the service refuses to start, saying which.

**S7.2-AC3.** **Given** PgBouncer ≥ 1.21 in transaction mode
**Then** the service starts and passes its checks (no session state).

### Story 7.3: Health looks as it always did, and shutdown is graceful

As an **operator**,
I want the same health endpoints and a clean shutdown,
So that my probes and dashboards keep working unchanged.

**Implements:** FR19; ADR-0031

**Acceptance Criteria:**

**S7.3-AC1.** **Given** `/actuator/health`, `/liveness` and `/readiness`
**Then** they answer in the existing JSON shape; readiness is `OUT_OF_SERVICE` (503, the Java build's
word) until the schema check passes, while the database is unreachable and from shutdown on; the
aggregate is `DOWN` while the database is unreachable, `OUT_OF_SERVICE` otherwise.

**S7.3-AC2.** **Given** SIGTERM
**Then** readiness goes `OUT_OF_SERVICE`, in-flight requests finish within the ADR-0031 timeout, pools close,
and the process exits 0.

### Story 7.4: There is one clock, and lint enforces it

As a **Go implementer**,
I want business time to come from one place,
So that the test clock (ADR-0033) moves everything, and nothing reads the wall clock behind it.

**Implements:** FR20; ADR-0033

**Acceptance Criteria:**

**S7.4-AC1.** **Given** `internal/clock`
**Then** it is the only source of business time, and it supports an offset
**And** the offset stays disabled in this epic: the `/test/clock` listener and its certificate
fingerprint guard (ADR-0033) need the signing keys, and arrive with Epic 8.

**S7.4-AC2.** **Given** `time.Now`, `Since`, `Until`, `After`, `Tick` or `NewTimer` anywhere else
**Then** lint fails, except at explicit, commented exemptions for measuring real durations.

### Story 7.5: CI builds and checks the Go code without touching the Java job

As a **reviewer**,
I want a Go job that runs every check on every PR,
So that the code rules hold from the first commit.

**Implements:** FR21

**Acceptance Criteria:**

**S7.5-AC1.** **Given** a PR touching Go
**Then** CI runs `go vet`, `golangci-lint` (with a banned-import list matching the chosen libraries)
and `forbidigo`, `govulncheck`, `go-licenses`, `go test -race -shuffle=on` with leak checks, and a
fuzz smoke run.

**S7.5-AC2.** **Given** `go.mod`
**Then** dev tools are pinned with the `tool` directive.

**S7.5-AC3.** **Given** the existing Java `build` job
**Then** it is unchanged and still required.

### Story 7.6: A multi-stage Dockerfile builds a small static image for both architectures

As an **operator**,
I want a small, static, non-root image,
So that "lighter" starts true from the first build.

**Implements:** FR22; ADR-0031

**Acceptance Criteria:**

**S7.6-AC1.** **Given** the `Dockerfile`
**Then** it builds a static binary (`CGO_ENABLED=0`, `-trimpath`, version and commit stamped) onto
`gcr.io/distroless/static-debian13:nonroot` pinned by digest.

**S7.6-AC2.** **Given** `docker buildx`
**Then** the image builds for amd64 and arm64; `make go-build`, `make go-test` and `make go-image`
wrap `go` and `docker`.

**S7.6-AC3.** **Given** the image
**Then** it starts as non-root, and its size is recorded for S7 (Epic 5).

## Epic 8: X9.150's signatures and QR Codes, in Go

The Go build signs, verifies and encodes exactly as Java does, before any endpoint exists. Done means
Go reproduces every verdict and every `signatureProblem` code of the golden set Java exported
(Story 4.7, `others/blackbox/golden/`), and decodes and encodes every golden EMV string.

**Implements:** FR23; ADR-0027, ADR-0033 (owner decisions of 2026-11-14 recorded in ADR-0027 included:
`typ` not checked, each `ES` algorithm bound to its curve, Java's catch-all codes kept).

Order: 8.1, 8.2 and 8.3 in parallel; then 8.4 (needs 8.2), 8.5 (needs 8.3 and 8.4) and 8.6 (needs
8.2). No story adds an HTTP endpoint to the public API; Epics 9 and 10 do.

### Story 8.1: EMV is encoded and decoded by hand

As a **Go implementer**,
I want `internal/emv` to produce and read the one EMV shape X9.150 uses,
So that a QR Code printed by Go is byte-for-byte the one Java prints.

**Implements:** FR23; ADR-0027 ("EMV: `internal/emv`, by hand")

**Acceptance Criteria:**

**S8.1-AC1.** **Given** `internal/emv`
**Then** it encodes and decodes TLV, computes CRC16-CCITT (poly `0x1021`, init `0xFFFF`) and builds
tag 26 with GUI `org.x9` and sub-tag `01` carrying the location URL; no vendored code, no third-party
EMV library.

**S8.1-AC2.** **Given** the 24 golden EMV strings (`others/blackbox/golden/manifest.json`)
**Then** Go decodes each to the manifest's fields and CRC, and encodes each manifest request back to
the identical string.

**S8.1-AC3.** **Given** malformed input (bad length, bad CRC, truncated TLV, wrong GUI)
**Then** the decoder refuses it with an error and never panics; a fuzz target covers the decoder.

**S8.1-AC4.** **Given** a configured host longer than 37 characters (tag 26 would exceed 99)
**Then** the service refuses to start, naming the limit, as Java does (`X9-LOC-480`).

### Story 8.2: Keys and the truststore are loaded from files, and reloaded

As an **operator**,
I want the Go service to read the signing key and the truststore I already mount,
So that moving to Go needs no new secrets and no Jasypt.

**Implements:** FR23; ADR-0027 ("Keys"), ADR-0031

**Acceptance Criteria:**

**S8.2-AC1.** **Given** a PKCS#12 or PEM signing key with its certificate chain, and a PKCS#12 or PEM
truststore, configured under the Java build's property and environment names (ADR-0031)
**Then** `internal/keys` loads them and exposes the signer as a `crypto.Signer` (RSA and EC) with its
chain; a JKS file is refused at startup with a message pointing to the conversion (ADR-0031).

**S8.2-AC2.** **Given** an `ENC(...)` value
**Then** startup refuses it with a message pointing to the configuration converter (Epic 13); Go
never decrypts Jasypt.

**S8.2-AC3.** **Given** the mounted files change
**Then** they are re-read on a timer (Java: every 30 s); a reload that fails keeps the last good
keys and logs the error; a reload never interrupts a signature in progress.

**S8.2-AC4.** **Given** the bundled demo and test material
**Then** PKCS#12/PEM equivalents of Java's demo keystore and truststore ship, so the image starts out
of the box exactly as Java's does, with the same NON-PRODUCTION warning; no new private key is
committed except test-CA material.

### Story 8.3: Certificates are checked for trust and revocation, as Java checks them

As a **payee**,
I want Go to trust and refuse the same certificates Java does,
So that no payer is accepted or refused differently after the cutover.

**Implements:** FR23; ADR-0027 ("Certificate fetching", "Revocation, parity with Java")

**Acceptance Criteria:**

**S8.3-AC1.** **Given** a chain
**Then** `internal/pki` builds it with `x509.Certificate.Verify` against the truststore, checks the
issuer name, skips revocation for a self-signed end entity, and returns the signer subject (the
reservation holder's identity, ADR-0021).

**S8.3-AC2.** **Given** the end entity's revocation sources
**Then** the CRL from its distribution point is checked first (cached until `NextUpdate`, its
signature verified against the issuer), OCSP from its AIA is the fallback, and the four answers of
ADR-0027's table hold: CRL and OCSP unreachable, OCSP only unreachable, CRL only unreachable → accepted
(soft-fail); neither named → refused; a revoked serial → refused.

**S8.3-AC3.** **Given** a certificate referenced by `jku` or `x5u`
**Then** it is fetched with no redirects, no credentials, connect and read timeouts, a size cap and
HTTPS only except the deployment's own host, and cached by `x5t#S256` (the cache skips the fetch,
never the checks).

**S8.3-AC4.** **Given** the test CA (valid, revoked by CRL, revoked by OCSP, no responder,
untrusted, EC)
**Then** a Go test covers each branch, with local CRL and OCSP servers, and none reaches the network.

### Story 8.4: Go signs X9.150 messages and signed answers

As a **payer or payee talking to the Go build**,
I want Go's signatures to carry exactly the headers Java's carry,
So that every counterpart verifying Java today verifies Go tomorrow.

**Implements:** FR23; ADR-0027 ("Signing"); Story 2.3

**Acceptance Criteria:**

**S8.4-AC1.** **Given** `internal/jws`
**Then** it signs with go-jose v4 and `WithCritical(correlationId, iat, ttl[, statusCode])`, `iat` and
`ttl` in milliseconds from `internal/clock`, `typ`, `x5t#S256`, and `jku`/`x5u`/`x5c` as configured;
PS512 by default, RS*, PS* and ES* available.

**S8.4-AC2.** **Given** a signed answer to a notification (Story 2.3)
**Then** it is a JWS over `{"statusCode":n}` with `typ: JOSE` and the same headers as Java's.

**S8.4-AC3.** **Given** a token Go signed
**Then** the suite's independent verifier (`x9client`) accepts it, and its header has the same field
set, types and units as the matching golden token Java signed.

### Story 8.5: Go verifies X9.150 messages with Java's verdicts and codes

As a **payee**,
I want Go to accept, refuse and explain exactly as Java does,
So that a payer sees the same `signatureProblem` from either build.

**Implements:** FR23; ADR-0027 ("Verification is our own sequence", "Fuzzing and corpus")

**Acceptance Criteria:**

**S8.5-AC1.** **Given** a compact JWS
**Then** verification runs ADR-0027's six steps in order: parse with an explicit allowed-algorithm
list; split the raw header and signing input; check `crit` is exactly the supported set,
`correlationId` is a UUID, `iat + ttl` is in the future and `iat` no further ahead than
`X9_JWS_CLOCK_SKEW_SECONDS` (default 60, `FUTURE_IAT`); resolve the certificate and check
`x5t#S256`; PKIX and revocation (Story 8.3); the signature, with each `ES` algorithm bound to its
curve. `typ` is not checked.

**S8.5-AC2.** **Given** the 89 golden JWS cases (`others/blackbox/golden/manifest.json`)
**Then** Go gives every case the manifest's verdict and `signatureProblem` code at its `verifyAt`
instant, including `UNVERIFIABLE` for an `ES256` header over an RSA certificate and `NOT_JWS` for a
token whose signature was removed but its last dot kept.

**S8.5-AC3.** **Given** header parsing and `crit` handling
**Then** fuzz targets cover them, a millisecond `iat` never passes through `float64`, and nothing
panics.

### Story 8.6: The test clock can be moved in Go, in test deployments only

As a **test author**,
I want Go to offer the same `/test/clock` control as Java, behind the same guards,
So that the black-box suite drives time on both builds the same way.

**Implements:** FR23; ADR-0033; Story 7.4 (deferred AC)

**Acceptance Criteria:**

**S8.6-AC1.** **Given** `X9_TESTING_CLOCKCONTROL=true`
**Then** `/test/clock` (`PUT`, `DELETE`, `GET`) is served on its own listener
(`X9_TESTING_CLOCKLISTEN`, `127.0.0.1:8099` by default), never on the API port, with the same
requests, answers and units as the Java build (offsets in milliseconds). There is no per-request
clock header: ADR-0033 rejects one, and Java has none; `X-Test-Clock-Offset` is only a response
header (S8.6-AC2).

**S8.6-AC2.** **Given** the flag is on
**Then** startup refuses unless the signing certificate's SHA-256 fingerprint is one of the bundled
demo or test-CA fingerprints compiled into the binary; startup logs a `WARN`; every API response
carries `X-Test-Clock-Offset` while the offset is non-zero.

**S8.6-AC3.** **Given** the flag is off (the default)
**Then** no listener exists and the clock has no offset; certificate validity is always judged on
the real clock, as in Java.

## Epic 9: Billers manage payment requests on Go

A biller creates, reads, edits and settles a QR Code on the Go build exactly as on Java, under the
transition map of ADR-0035, and every change it makes is in PostgreSQL with its event in the same
commit. Done means the black-box suite's payment-request tests pass against the Go image.

**Implements:** FR24; ADR-0005, ADR-0016, ADR-0018, ADR-0020, ADR-0023 to ADR-0026, ADR-0028,
ADR-0029, ADR-0035, ADR-0036 (the status-update rows of its matrix, amendments of 2026-11-14
included), ADR-0037; the owner's decision of 2026-11-15 on the public host's default.

**Done is defined by the black-box suite** (ADR-0030). Story 9.1 runs the suite against the Go image
and keeps a list of the test keys that must pass there; each later story ends by adding its keys to
that list. A key whose setup needs an endpoint of a later story (a payer's pre-commit, the event
stream) joins the list with that story, which names it. The suite's `go_only` and `java_only`
markers are used as they are. The epic proves these rule-coverage rows on Go: `R-MAP-001`, `-002`,
`-010` to `-013`, `-050`; `R-SM-10` to `-14` and `-29` to `-34`; `R-EP-08`, `-24`, `-25`, `-27`;
`R-I1-01` to `-03`, `R-I2`, `R-I3`, `R-I5`, `R-I6`, `R-I8`, `R-I9-01`, `-03`; `R-ADR0005-01` to `-04`,
`R-ADR0012`, `R-ADR0016`, `R-ADR0018`, `R-ADR0020-01` to `-03`, `R-ADR0035-01`, `-04`.

Order: 9.1, 9.2, 9.3 and 9.4 in parallel; 9.5 after 9.3; 9.6 after 9.2, 9.4 and 9.5; then 9.7 and
9.8 in parallel. Story 10.1 (the event stream) needs only 9.2 and 9.5: build it alongside 9.6,
because the tests of 9.6 to 9.8 that assert "no event" or "exactly this event" read the stream.

### Story 9.1: The black-box suite runs against the Go image

As a **maintainer**,
I want `make blackbox-image` to run the suite against the Go image on PostgreSQL, as it runs against
Java on MongoDB,
So that a Go story is done when its tests pass there, and not when its author says so.

**Implements:** FR24, FR25; ADR-0030, ADR-0031 (Liquibase one-shot, PKCS#12), ADR-0034

**Acceptance Criteria:**

**S9.1-AC1.** **Given** `X9_IMAGE` naming a Go image (`make go-image`, `x9-qrcode:go`)
**Then** `others/blackbox/deployment/run-against-image.sh` starts PostgreSQL 16, applies
`db/changelog/` with the Liquibase one-shot of Story 6.3 under the migration role, starts the image
as the application role with the test clock on and the black-box truststore, and runs the suite in
the service's network namespace, as it does for Java. `X9_DATABASE_URL` is a read-only
`postgresql://` URL, so `X9_BUILD` is `go`. The Java run does not change.

**S9.1-AC2.** **Given** the Go build refuses JKS (ADR-0031)
**Then** `build-truststore.sh` also writes a PKCS#12 copy of the black-box truststore (the test CA
and the shipped demo certificate), which the Go run mounts; the JKS stays for Java, and no new
private key is committed.

**S9.1-AC3.** **Given** `others/blackbox/deployment/go-required.txt`, one test key per line, empty
at first
**Then** the Go run fails when a listed test fails, errors or is skipped (a `java_only` skip
excepted), and reports every other test as informative, with the counts of each outcome. The
stories of Epics 9 and 10 add their keys to it.

**S9.1-AC4.** **Given** a pull request that touches the Go code, `db/` or `others/blackbox/`
**Then** a `blackbox-go` job in `.github/workflows/blackbox.yml` builds the image with
`make go-image` and runs S9.1-AC1; it fails the pull request when a listed key fails. The Java job
is unchanged.

**S9.1-AC5.** **Given** `X9-LOC-421`, a strict expected failure today (BUG-004: Java's
`releaseLocation` ignores `validUntil`)
**Then** its mark becomes conditional on the build: still a strict expected failure on Java, and an
ordinary test on Go, which follows ADR-0024 (Story 9.6). No other mark changes.

**S9.1-AC6.** **Given** the Go deployment under test
**Then** it sets no public host, so it runs on the default of S9.6-AC4, `x9-150.example.com`, one
of the hosts the golden EMV set covers; `test_golden.py`'s EMV cases therefore run on Go instead
of skipping.

### Story 9.2: HTTP answers in Java's shape

As an **integrator**,
I want the Go build to read and write JSON, and to refuse, exactly as Java does,
So that my client cannot tell which build it is talking to (ADR-0028).

**Implements:** FR24, FR25; ADR-0026 (ids on the wire), ADR-0028 ("Behaviours the Go build
reproduces"), ADR-0031 (server limits)

**Acceptance Criteria:**

**S9.2-AC1.** **Given** the routes of Epics 9 and 10
**Then** they are registered on the existing mux behind `internal/httplog` and the test clock's
offset header, through one package of helpers that every handler uses to read a body and to write
an answer; an unknown path or a wrong method is answered with Java's status and problem `type`.

**S9.2-AC2.** **Given** a JSON request body
**Then** keys match case-sensitively; unknown properties are treated as Java treats them for that
schema (an interpreted rail stays closed: an unknown field in `solana` is refused, `R-I9-03`, and,
since 2026-11-15 by the owner's decision, in `fednow`, `rtp` and `ach` too, on create and PATCH
only, `R-I16-04`); a
body that is not JSON, or a number past 2^63−1, is a 400 `malformed-json` (`X9-AMT-422`). The type
decoded is the type validated and stored (ADR-0024), and it tracks which fields a PATCH carried.

**S9.2-AC3.** **Given** a JSON answer
**Then** a value is omitted only when it is null, never because it is zero, `false` or empty
(Jackson's `NON_NULL`); amounts are JSON numbers; pretty-printing is not reproduced (tolerated
difference 1).

**S9.2-AC4.** **Given** a refusal
**Then** it is `application/problem+json` with Java's fields (`type`, `title`, `status`, `detail`,
`violations`, and properties such as `currentStatus` and `currentRevision`) and Java's `type` URIs,
unchanged: `validation-error`, `malformed-json`, `business-rule-violation`, `resource-not-found`,
`status-conflict`, `precondition-failed`, `invalid-signature`, `payload-refused`,
`payload-unreachable`, `notification-undeliverable`, `internal-error`. A 500 carries no stack, SQL
or internal detail.

**S9.2-AC5.** **Given** the request schemas of `openapi.yaml`
**Then** every `required`, `minLength`, `maxLength`, `pattern`, `minimum`, `maximum`, exclusive bound
and `enum` is enforced by hand-written checks, each violation naming Java's field path with Bean
Validation's default message; a Go test reads `openapi.yaml` and fails when a constraint of a
request schema has no check (the Go counterpart of `contract.py`). No server code is generated from
the contract (ADR-0028).

**S9.2-AC6.** **Given** a QR Code or location id on input
**Then** it is accepted in the forms ADR-0026 names and Java accepts, and every id is written as 32
upper-case hexadecimal characters.

**S9.2-AC7.** **Given** an `If-Match` header
**Then** it is parsed as Java parses it: quoted, bare, `W/"…"` and `W/…` forms and a bare number are
read as a tag; `*` or no header means unconditional; anything else is compared as a tag, and so
fails with 412.

**S9.2-AC8.** **Given** the server
**Then** request headers above 30 KB are refused as Java refuses them, and ADR-0031's timeouts hold
(read-header 10 s, read 30 s, write 40 s, idle 120 s). Go tests cover every criterion of this story;
its black-box key is `X9-LIFE-502` (the deployment is ready), added to the list.

### Story 9.3: One package decides every transition

As a **Go implementer**,
I want the QR Code's lifecycle decided by one pure package, with one `now` per decision,
So that every endpoint applies ADR-0035's map the same way and every event is built through one
door.

**Implements:** FR24, FR25; ADR-0016, ADR-0019, ADR-0020, ADR-0021, ADR-0024, ADR-0029, ADR-0035,
ADR-0036

**Acceptance Criteria:**

**S9.3-AC1.** **Given** `internal/domain`
**Then** it imports no database and no HTTP package. It holds the QR Code row of ADR-0024, status as a
typed string parsed at the boundary, the holder (`payer.info` and signer subject), and the
reservation's end (`reserved_until`) with its "no lapse" sentinel.

**S9.3-AC2.** **Given** `EffectiveStatus(row, now)`
**Then** a `PAYMENT_INITIATED` past its stamp reads `ACTIVE`; never once a hash is registered, nor
when `x9.reservation.ttl-seconds` is `0`; a reservation with no stamp is treated as holding
(ADR-0019). Entering `PAYMENT_INITIATED` stamps `min(now + ttl, validUntil)`; leaving it clears the
stamp and the holder; time never writes a status (ADR-0029).

**S9.3-AC3.** **Given** `ETag(row, now)`
**Then** it is `"<revision>-<effective status>"`, and `If-Match` is compared against the same
function (ADR-0028, Story 2.2).

**S9.3-AC4.** **Given** a trigger (create, PATCH, status-update, pre-commit, post-commit, `NOT_SENT`,
location release)
**Then** one function decides it and returns the row's new values, the events to emit in order and
the answer's status; each uses the stored or the effective status as ADR-0035's map says. A table
test has a case for every key of the map (`X9-MAP-001` to `X9-MAP-051`) and every cell of
ADR-0036's matrix, the § and ¶ amendments included.

**S9.3-AC5.** **Given** the same-holder rule
**Then** two notifications are from the same payer only when both carry `payer.info`, the values are
equal, and the certificate subjects are equal; an unattributed reservation (status-update) matches
nobody (ADR-0036, superseding ADR-0021's fallback).

**S9.3-AC6.** **Given** `BuildEvent(type, row, lastNotification, now, reason)`
**Then** it is the only code that constructs a `payment_event`: the body comes from the last stored
notification, else from the first payment method and the payment details, field for field as Java
(amended 2026-11-15, owner: the latest stored notification carrying a `transactionId` comes first,
chosen by `domain.EventBodySource`; ADR-0035);
`deviations` is a closed list (`RECEIVED_IN_WRONG_STATUS`, `ACTION_CONTRADICTS_HASH`), absent rather
than empty; `receivedInStatus` and `schemaVersion` as Java writes them. A test fails if another
package builds an event.

**S9.3-AC7.** **Given** any accepted transition
**Then** it sets `revised_at`; only PATCH moves `revision` (ADR-0016); reactivation and a lapse emit
nothing.

**S9.3-AC8.** **Given** a location held by another QR Code
**Then** the holder is not live when its stored status is `PAID` or `CANCELLED` **or** its
`valid_until` has passed (ADR-0024). Go follows the ADR, not Java's BUG-004.

### Story 9.4: A payment request is checked and priced as Java checks and prices it

As a **biller**,
I want Go to accept and refuse exactly the requests Java does, and to price them the same at any
instant,
So that nothing I send changes meaning when the build changes.

**Implements:** FR24, FR25 (pricing); FR36; ADR-0005, ADR-0010, ADR-0012, ADR-0017; INTERPRETATION
I-1 to I-3, I-5, I-6, I-8 to I-10, I-13, I-14

**Acceptance Criteria:**

**S9.4-AC1.** **Given** a create or PATCH body that passed the contract checks (S9.2-AC5)
**Then** the business rules refuse what Java refuses, naming the same field with the same message:
a network other than `fednow`, `rtp`, `ach` and `solana`, by name, and any other casing of those
(I-1, I-2); a currency not in `supported-currencies.json` or written in another case, every
offender named, an empty list disabling the check (I-3); a payment method without a currency (no
default, not even the bill's); currencies from two peg groups; a duplicate currency; `paymentMethods`
as a bare object (I-5); a bank account number that is not 4 to 17 letters or digits (I-6); a Solana
`recipient` that is not 32 to 44 Base58 characters, or an unknown field in `solana` (I-8, I-9); a
tip offer that contradicts itself, while `presets` has no minimum size (I-10); a `validUntil` not in
the future; a `dueDate` or a method's `validUntil` after the QR Code's.

**S9.4-AC2.** **Given** an amount
**Then** every amount the service takes is at most 999999999999999999 and not negative, the signed
adjustment amount bounded on both sides; a bill whose amount plus the largest late fee reachable
before `validUntil`, or any payment method as the payload would show it with that fee, would pass
18 digits is refused at create and at PATCH; the formula refuses rather than emits such an amount
(ADR-0005).

**S9.4-AC3.** **Given** the formula (`FixedDiscountLateFeeLinearInterest`) and an instant
**Then** the price is computed at that one instant (I-14): a discount applies up to and including
its edge `dueDate − daysBefore`, and the largest eligible one wins; at exactly `dueDate` there is no
fee; the late fee rises at each 24-hour boundary from `dueDate` and stops at `validUntil`; each
adjusted amount advertises as its `validUntil` the exact instant it stops applying, capped by the
method's and the QR Code's (FR36); methods in a pegged currency are adjusted as Java adjusts them
since BUG-001's fix.

**S9.4-AC4.** **Given** the two currency files
**Then** Go ships `supported-currencies.json` and `pegged-currencies.json` with Java's content and
loading rules (`_comment` ignored, strict JSON), and reads replacements from the locations Java's
`x9.currency.supported-currencies-location` and `x9.currency.pegged-currencies-location` name, under
ADR-0031's naming.

**S9.4-AC5.** **Given** the rules and the formula
**Then** each has a table test with Java's message, the formula's edges are tested to the
millisecond with `internal/clock`, and a fuzz target shows the formula never overflows or panics.
The black-box keys come with Stories 9.6, 9.7 and 10.3, which call this code.

### Story 9.5: A transition is one transaction, and its event is in it

As a **payment system reading the events**,
I want every change and its event to commit together, numbered in commit order,
So that my cursor never skips an event and never sees one whose change was rolled back.

**Implements:** FR24, FR25; ADR-0023, ADR-0024, ADR-0025, ADR-0026, ADR-0037

**Acceptance Criteria:**

**S9.5-AC1.** **Given** `internal/store`
**Then** every transition runs ADR-0025's script in one `pgx.BeginFunc` transaction with
`READ COMMITTED` set explicitly, and `SET LOCAL lock_timeout = '2s'` when it emits an event: read
the row; decide in `internal/domain` with one `now`; `UPDATE … WHERE id = ? AND lock_version = ?`.
Zero rows, `40001`, `40P01` or `55P03` re-run the whole script from the read, three attempts in all;
`57014` is a 503; `23505` is answered as the domain maps it; exhausted re-runs are a 409 "busy" with
the current status, never a 500.

**S9.5-AC2.** **Given** one transaction
**Then** its statements run in this order: a location holder's release, the row's update, the audit
or notification row, and last the event, as one pgx batch of `UPDATE event_sequence SET last_seq =
last_seq + 1` and `INSERT INTO payment_event … SELECT last_seq …` with no `RETURNING`. A store test
asserts the order.

**S9.5-AC3.** **Given** an event row
**Then** its `event_id` is a UUIDv7 minted after the sequence lock is taken, from one monotonic
generator, so within one instance `event_id` order equals `seq` order (amended 2026-11-15, owner:
across instances it is not promised, and `seq` is the only order; ADR-0037); `read_status_code` is `PEND` and
`read_at` is `9999-12-31 00:00:00`, and X9.150 never updates either.

**S9.5-AC4.** **Given** new rows
**Then** QR Code ids are UUIDv7, location ids UUIDv4, event ids UUIDv7, all bound as `UUID`
(ADR-0026); SQL is never schema-qualified and uses only ADR-0023's portable dialect.

**S9.5-AC5.** **Given** the two pools of Story 7.2
**Then** writes use the write pool, and GET, the payload and the events read on the read pool.

**S9.5-AC6.** **Given** PostgreSQL in `internal/dbtest`
**Then** tests show: concurrent transitions on one row end with the domain's outcomes and no lost
update; concurrent event-producing transactions on different rows give a gapless `seq` in commit
order; a rolled-back transition leaves no event and no gap.

**S9.5-AC7.** **Given** a committed transaction that inserted an event
**Then** an in-process signal wakes parked event readers (Story 10.1), after the commit and never
on a rollback.

### Story 9.6: A biller creates and reads a QR Code

As a **biller**,
I want to create a QR Code and read it back on the Go build,
So that what I print and what I read are what Java gave me.

**Implements:** FR24; ADR-0016, ADR-0024, ADR-0026, ADR-0028, ADR-0029, ADR-0035 (`X9-MAP-001`,
`X9-MAP-050`); owner decision 2026-11-15 (public host default)

**Acceptance Criteria:**

**S9.6-AC1.** **Given** a valid `POST /api/v1/payment-request`
**Then** the answer is 201 with Java's `PaymentRequestResponse` fields and an `ETag`: the id, revision
`0`, `ACTIVE`, the times, the location, `qrCode` (built by `internal/emv` from the location and the
EMV configuration) and `qrCodeB64` (that string in Base64). No event is written. A refused create
writes nothing (`X9-LIFE-506`, through the PostgreSQL inspector).

**S9.6-AC2.** **Given** a create with `locationId`
**Then** it may take a location another QR Code holds only when the creditor is the same and the
holder is not live (S9.3-AC8); the holder is released first, in the same transaction, to a fresh
UUIDv4 location with its new `emv_content`, its revision unmoved and no event (`X9-MAP-050`); a
live holder or another creditor keeps the location, with Java's 400; a refused create releases
nothing. A `23505` after the re-runs is answered as a refused rebind (400).

**S9.6-AC3.** **Given** a create without `locationId`
**Then** the location is the QR Code's own id, as Java does (ADR-0024; ADR-0026 amended
2026-11-15, owner), and the QR Code id is exactly 32 hex characters with no dashes, in requests and
in answers, as X9.150 defines it; a dashed QR Code id is refused.

**S9.6-AC4.** **Given** the public host
**Then** it defaults to `x9-150.example.com`, Java's documented default (owner, 2026-11-15), so the
image starts with no host configured; a host over 37 characters, or with a character outside
printable ASCII or a space, still stops the service at startup (`R-EP-25`, `R-ADR0027-08`).
While the host is the placeholder `x9-150.example.com`, startup logs a `WARN` saying QR Codes
printed with it cannot be scanned and naming `X9_PUBLICENDPOINTS_HOST` (owner, 2026-11-15).
`emv_content` is written only with `location_id` and never recomputed.

**S9.6-AC5.** **Given** `GET /api/v1/payment-request/{id}`
**Then** one row is read and the answer is composed from its columns and `request_json`, with the
effective status and the `ETag`; `?revision=` matches only the current revision and is otherwise
answered as Java answers it; an unknown id is 404; `lockVersion` never appears; an expired QR Code
is still read (ADR-0029, tolerated difference 2).

**S9.6-AC6.** **Given** the harness of Story 9.1
**Then** these keys are added to `go-required.txt` and pass: `test_qr_code.py` but `X9-DEC-016`;
`test_create_refusals.py`; `test_contract.py`'s cases for `createPaymentRequest`; in
`test_payment_requests_pinned.py`, `X9-RAIL-420`, `X9-CUR-420` to `-423`, `X9-LIFE-420` to `-422`,
`X9-TIP-420`, `-421`, `X9-AMT-420` to `-423`, `X9-LOC-420` to `-425` (`X9-LOC-421` included, which
Java fails); `X9-PATCH-501`; `X9-LIFE-506`; `X9-LOC-501`; `X9-MAP-001`. `X9-MAP-050` joins
with Story 10.1.

### Story 9.7: A biller edits a QR Code, and every edit is audited

As a **biller**,
I want to change what a live QR Code asks for, conditionally if I choose,
So that a partial payment can lower the amount, and the previous values are kept for audit.

**Implements:** FR24; ADR-0016, ADR-0018, ADR-0024, ADR-0028; `R-MAP-002`, `R-EP-27`

**Acceptance Criteria:**

**S9.7-AC1.** **Given** a `PATCH /api/v1/payment-request/{id}`
**Then** it is allowed when the stored status is `ACTIVE` or `PAYMENT_INITIATED` and refused
otherwise as Java refuses it; only the six tags of ADR-0024 change, the others are never applied; a
tag counts as changed when present, `paymentMethods` when its value differs; a PATCH that changes
nothing is a 400.

**S9.7-AC2.** **Given** the currencies of the PATCH (ADR-0018)
**Then** it names every currency the QR Code offers, in any order, or is refused naming the one left
out; a currency the QR Code does not offer is refused naming it, never dropped with a 200; methods
merge by currency. The dates are checked on the merged result, as at creation (`R-EP-27`), and the
amount bounds of S9.4-AC2 hold.

**S9.7-AC3.** **Given** an accepted PATCH
**Then** it runs as ADR-0024 orders it, in one transaction: a location holder released first (S9.6-AC2
rules), then `request_json`, `valid_until`, the location and its `emv_content` only when the
location changed, `revision = n + 1`, `revised_at`; then the audit row `(id, n, now, changed tags,
their previous values)`, the previous `locationId` taken from the column. No event is written
(`X9-MAP-002`).

**S9.7-AC4.** **Given** an `If-Match`
**Then** a tag other than `ETag(row, now)` is a 412 `precondition-failed` with `currentStatus` and
`currentRevision`, and nothing is written; a refused PATCH never moves the revision or the ETag.

**S9.7-AC5.** **Given** the audit log
**Then** no endpoint reads it, and a store test rebuilds a QR Code's earlier request by walking it
backwards.

**S9.7-AC6.** **Given** the harness
**Then** these keys are added and pass: `test_patch_currencies.py`;
`X9-PATCH-420` to `-426`, `-430` to `-432`, `-434`, `-435`, `-502`, `-504`; `X9-MAP-002`;
`test_contract.py`'s cases for `patchPaymentRequest`. `X9-PATCH-433` joins with Story 10.5, and
`X9-TIME-014` with Story 10.3.

### Story 9.8: The payment system sets the status, and a retry is harmless

As a **payment system**,
I want to reserve, settle, cancel and release a QR Code through the status-update API,
So that I decide the outcome of my own receivable, and a retried call never counts twice.

**Implements:** FR24; ADR-0008, ADR-0016, ADR-0019, ADR-0020, ADR-0035 (`X9-MAP-010` to `-013`),
ADR-0036 (status-update rows; amendments of 2026-11-14)

**Acceptance Criteria:**

**S9.8-AC1.** **Given** `PUT /api/v1/payment-request/{id}/status-update` with `PAYMENT_INITIATED`
**Then** from `ACTIVE` (effective, so a lapsed reservation counts) it takes an unattributed
reservation, stamped as S9.3-AC2, and writes `payment.initiated`; it carries no settlement details
(`R-SM-14`); on `PAID` or `CANCELLED` it is a 409 `status-conflict` naming the current status.

**S9.8-AC2.** **Given** `PAID` or `CANCELLED`
**Then** each is accepted from a stored `ACTIVE` or `PAYMENT_INITIATED`, a lapsed reservation and a
QR Code past `validUntil` included (ADR-0020), writing `payment.cleared` or `payment.cancelled`;
`PAID` records `endToEndId` and the network; `PAID` on `CANCELLED` and `CANCELLED` on `PAID` are 400.

**S9.8-AC3.** **Given** `ACTIVE`
**Then** a `PAYMENT_INITIATED` (effective) returns to `ACTIVE` with its reservation and holder
cleared and no event, also once a hash is registered: this is the payment system's control over a
reservation (ADR-0036); on a lapsed reservation, `PAID` or `CANCELLED` it is a 400.

**S9.8-AC4.** **Given** the status the QR Code already has, stored and effective
**Then** the answer is 200 and nothing is written: no revision, no `revised_at`, no event; a repeated
`PAID` keeps the first reference (ADR-0020).

**S9.8-AC5.** **Given** an `If-Match`
**Then** only the current tag passes, otherwise 412 with `currentStatus` and `currentRevision` and
nothing written; a retry sending the first attempt's tag after that attempt succeeded is a 412, and
a retry without `If-Match` is the 200 of S9.8-AC4 (ADR-0020, owner 2026-11-14).

**S9.8-AC6.** **Given** any status-update
**Then** the revision never moves and the ETag follows the status (`R-ADR0016-02`); the event is in
the transaction (S9.5); exhausted re-runs are a 409 "busy". The endpoint's description in
`openapi.yaml`, which still says a `PAYMENT_INITIATED` on a held QR Code is a 409, is corrected to
ADR-0036's amended matrix (a retry, 200); no schema changes.

**S9.8-AC7.** **Given** the harness
**Then** these keys are added and pass: `X9-MAP-010` to `-013`, `X9-MAP-051`; `X9-PATCH-503`,
`-505`; `test_contract.py`'s cases for `putPaymentRequestStatusUpdate`; with Story 10.1,
`X9-LIFE-170` to `-176` and `X9-EVT-050`. `X9-LIFE-503` joins with Story 10.5, whose pre-commit it
uses.

## Epic 10: Payers fetch, notify and are evented on Go, under the pre-commit / post-commit rule

A payer's PSP fetches the signed payload, pre-commits and post-commits, and gets a signed answer
every time; a payment system reads the events over HTTP, SQL or CDC; a PSP can use the Go build to
pay somebody else's QR Code. Done means the whole black-box suite passes against the Go image,
except the tests marked `java_only` and `X9-TIME-015` (retention, Epic 12).

**Implements:** FR25; FR33 to FR36 (ported); ADR-0001, ADR-0003, ADR-0004, ADR-0006, ADR-0014,
ADR-0015, ADR-0017, ADR-0019, ADR-0021, ADR-0025 to ADR-0028 (its 2026-11-14 amendment included),
ADR-0031 (cursor ahead of the log, shutdown), ADR-0035, ADR-0036, ADR-0037; INTERPRETATION I-9 to
I-14.

Each story adds its keys to the list of Story 9.1, under the same rules. The epic proves these
rule-coverage rows on Go: `R-MAP-020` to `-045`, `-051`; `R-SM-15` to `-28`, `-35` to `-46`;
`R-EP-02` to `-07`, `-09` to `-23`, `-26`, `-28`; `R-I1-04` to `-06`, `R-I4`, `R-I9-02`, `R-I10`,
`R-I11`, `R-I12`, `R-I13`, `R-I14`; `R-ADR0004`, `R-ADR0006-01` to `-03`, `R-ADR0014-01` to `-03`,
`R-ADR0015`, `R-ADR0017`, `R-ADR0019`, `R-ADR0020-04`, `R-ADR0021`, `R-ADR0027`, `R-ADR0035-02`,
`-03`, `R-ADR0036`.

Order: 10.1 after 9.2 and 9.5, alongside 9.6 to 9.8; 10.2 after 9.2; 10.3 and 10.4 after 9.6 and
10.2, in parallel; 10.5 after 10.4 (its black-box keys also need 9.8); 10.6 after 10.2; 10.7 after
10.3; 10.8 last. The signature endpoints (`/api/v1/signature/generate` and `validate`) are a biller's
tool but are built in 10.2, with the verifier they need.

### Story 10.1: The event stream reads the outbox

As a **payment system**,
I want to page the events of the Go build with my existing cursor, over HTTP,
So that I keep consuming at cutover without changing anything.

**Implements:** FR25; ADR-0001, ADR-0025, ADR-0026, ADR-0031, ADR-0037; `R-EP-13` to `-23`

**Acceptance Criteria:**

**S10.1-AC1.** **Given** `GET /pub/api/v1/events?after=`
**Then** `after` omitted or empty starts at the beginning of retained history; a decimal `seq`
resumes strictly after it; a ULID is translated through `payment_event_legacy_cursor` (its row's
`seq`, else the first migrated event whose legacy cursor is greater); an `after` greater than
`last_seq` is a 409 "cursor ahead of log" carrying the current `last_seq` (ADR-0031); any other
value is a 400 naming `after`.

**S10.1-AC2.** **Given** `limit` and `wait`
**Then** `limit` is 1 to 500, 100 by default, and `wait` 0 to 30 seconds, 0 by default; a value
outside either range is a 400 naming the parameter, at once.

**S10.1-AC3.** **Given** a page
**Then** events come in `seq` order with Java's fields and values (`eventId`, `type`, `occurredAt`,
`qrCodeId`, `qrCodeRevision`, amounts, `payerInfo`, `deviations`, `receivedInStatus`,
`schemaVersion`, …); `nextCursor` is the last event's `seq` as a decimal string; `hasMore` is true
when the page was cut at `limit`; a read past the end is empty with no `nextCursor`.

**S10.1-AC4.** **Given** nothing to return and `wait > 0`
**Then** the request is held until an event commits (S9.5-AC7) or `wait` passes, with a 1 s poll as
the backstop; a parked request holds no database connection and reads on the read pool; on SIGTERM
parked requests return their current page (ADR-0031).

**S10.1-AC5.** **Given** `docs/EVENTS-TABLE.md`
**Then** the rows Go inserts match it column for column, and a store test reads them as the
document's plain-SQL reader does (claim with `FOR UPDATE SKIP LOCKED`, mark `DONE`) under the reader
role's grants.

**S10.1-AC6.** **Given** the harness
**Then** these keys are added and pass: `X9-EVT-051` to `-057`, and the keys of Epic 9 that waited
for the stream (`X9-MAP-050`, `X9-EVT-050`, `X9-LIFE-170` to `-176`). A `go_only` test is added for
the "cursor ahead of log" answer, which Java does not give; it is recorded for Epic 11's list of
tolerated differences.

### Story 10.2: Signatures on the wire: keys published, signer and verifier wired

As a **payer, or a biller testing a JWS**,
I want the Go build to publish its keys and to sign and verify through the API,
So that every counterpart can check the Go build's signatures, and the payer endpoints have a
verifier to use.

**Implements:** FR25 (and FR24's signature endpoints); FR23 (wiring); ADR-0027, ADR-0033;
`R-EP-06`, `-07`, `-11`; `R-I7`; `R-I12-09` to `-11`

**Acceptance Criteria:**

**S10.2-AC1.** **Given** startup
**Then** one signer (Story 8.4) and one verifier (Story 8.5) are built from `internal/keys` (a key
reload is followed without restart), the truststore, a certificate fetcher that knows the public host
(Story 8.3) and the business clock. `internal/config` reads
`x9.public-endpoints.jws-clock-skew-seconds` (`X9_JWS_CLOCK_SKEW_SECONDS`, 60 by default, a
negative value read as 0, as Java does) and `x9.certificate.issuer-name` (`X9` by default) under
ADR-0031's naming; both are in `config.example.yaml`.

**S10.2-AC2.** **Given** `GET /pub/.well-known/jwks`
**Then** it serves the signing key as a JWK set with its `x5c` chain, in Java's shape, and follows a
key reload.

**S10.2-AC3.** **Given** `GET /pub/.well-known/certificate/{pemFileName}`
**Then** the signing certificate is served in PEM (`application/x-pem-file`) under the file name
Java serves, which is the one the `x5u` header names; any other name is a 404.

**S10.2-AC4.** **Given** `POST /api/v1/signature/generate` with a `Correlation-Id` header and any JSON
body
**Then** the answer is a compact JWS (`application/jose`) signed as Story 8.4 signs, with that
`correlationId` and the requested ttl; a missing header is refused as Java refuses it.

**S10.2-AC5.** **Given** `POST /api/v1/signature/validate`
**Then** a valid JWS is a 200 carrying the `Correlation-Id` header, and an invalid one a **400**
`business-rule-violation` with Java's body. (This criterion first said 422; the Java build has never
answered 422 here, and `openapi.yaml` no longer lists it.)

**S10.2-AC6.** **Given** the harness
**Then** these keys are added and pass: `X9-SIG-507`; `test_contract.py`'s cases for `validate`.
The two signature endpoints have no black-box test of their own, because the suite never signs
through the service under test (`X9-SUITE-501`): a Go handler test covers S10.2-AC4 and S10.2-AC5,
verifying a generated token with the verifier, and Epic 11's differential run compares both
endpoints with Java.

### Story 10.3: A payer fetches the signed payload

As a **payer's PSP**,
I want `POST /pub/api/v1/loc/{id}` to give me the signed payload at today's price, or a reason,
So that I pay what the payee published, and never an expired or closed bill.

**Implements:** FR25; FR36; ADR-0020, ADR-0027 (EMV), ADR-0028 (tolerated difference 2), ADR-0029;
INTERPRETATION I-13, I-14; `R-EP-02` to `-05`, `R-ADR0020-04`, `R-ADR0027-07`

**Acceptance Criteria:**

**S10.3-AC1.** **Given** a payload request (`application/jose`)
**Then** it is verified by the verifier of S10.2-AC1, and a request that does not verify is a 400,
not a 401, with Java's one generic violation (`Invalid JWS signature for payment payload request.`)
and no `signatureProblem` code, which goes to the log only (`X9-LOC-304`); an unknown location is a
400, not a 404, as Java answers.

**S10.3-AC2.** **Given** the signed `qrCodeContent`
**Then** it must decode to exactly the stored `emv_content`, byte for byte; malformed EMV is refused
as `CONTENT_MISMATCH`, saying it is malformed, and any other difference as `CONTENT_MISMATCH`.

**S10.3-AC3.** **Given** a QR Code that is `PAID`, `CANCELLED` or past `validUntil`
**Then** the payload is refused with a 400 naming the reason; an expired QR Code answers with its
expiry reason, because the Go build keeps the row (tolerated difference 2); a lapsed reservation
reads `ACTIVE` and is served.

**S10.3-AC4.** **Given** a served payload
**Then** it is priced by S9.4-AC3 at the later of `dateForPayment` and the payee's now; expired
payment methods are dropped; `paymentNotification.endpoint` is the creditor's own when given,
otherwise the host-derived URL; the body is Java's `PaymentPayloadResponse`, field for field.

**S10.3-AC5.** **Given** the answer
**Then** it is a compact JWS (`application/jose`) signed by the signer of S10.2-AC1 with Java's
header, `statusCode` in `crit`; nothing is written (the quote is not stored: ADR-0011 is not built).

**S10.3-AC6.** **Given** the harness
**Then** these keys are added and pass: `test_payload.py`; `X9-LOC-301` to `-312` of
`test_payers.py`; `test_clock.py`; `X9-LOC-502`; `X9-PATCH-440`; `X9-TIME-014`; `X9-LOC-470` to `-473` of
`test_golden.py`.

### Story 10.4: The notification endpoint verifies, refuses by name, and always answers signed

As a **payer's PSP**,
I want every answer to my payment notification to be a JWS signed by the payee, refusals included,
So that the signed question and the signed answer are the evidence of what was agreed (FR33).

**Implements:** FR25; FR33; ADR-0006, ADR-0027, ADR-0028 (2026-11-14 amendment), ADR-0036 (every
answer signed); INTERPRETATION I-1, I-4, I-12; `R-I12-01` to `-11`, `R-ADR0006-01` to `-03`,
`R-SM-16` to `-18`

**Acceptance Criteria:**

**S10.4-AC1.** **Given** `POST /pub/api/v1/payment-notification`
**Then** every answer is a compact JWS (`application/jose`, `typ: JOSE`) signed with the payee's key,
also a 400 or 415 refused before the handler runs: a yes has the body `{"statusCode": 200}`; a
refusal keeps its HTTP status and its error fields, with `statusCode` added.

**S10.4-AC2.** **Given** the notification's signature
**Then** a notification that does not verify is a signed 401 carrying the fixed `signatureProblem`,
under a fresh `correlationId`; once it verifies, the answer echoes its `correlationId`; the answer's
`iat` is the payee's clock at the decision.

**S10.4-AC3.** **Given** a verified notification
**Then** these are refused before any transition, with Java's status and field: an unknown QR Code
(404); a QR Code created without `paymentNotification`, or with `kind: EXTERNAL` (ADR-0006); a
network or currency the QR Code does not offer, matched whatever the case and echoed as sent (I-1,
I-4); `blockchain` data on a bank rail; ACH without `payer.info` or `expectedDate`; an amount past
18 digits (ADR-0005; signed bodies included). (A notification carries no `solana` object, so there
is no unknown `solana` field to refuse here; that rule belongs to create and PATCH.)

**S10.4-AC4.** **Given** a refusal
**Then** it is logged with the payer's certificate subject and `payer.info` and the reason, so a
payee can count refusals per payer.

**S10.4-AC5.** **Given** the harness
**Then** these keys are added and pass: `X9-SIG-508` to `-526` of `test_signatures.py` but those
whose setup takes a reservation (they join with Story 10.5); `X9-SIG-001` to `-003`, `-110`, `-111`,
`X9-RAIL-001`, `-002`, `X9-CUR-001` of `test_payment_notification.py`; `X9-HOLD-403`, `-404` and
`X9-SIG-301` to `-304` of `test_payers.py`.

### Story 10.5: Pre-commit and post-commit, under the transition map

As a **payee**,
I want Go to decide every notification by ADR-0036's matrix, and to register every hash,
So that two payers never pay one QR Code, and money that moved is never lost to the payment system.

**Implements:** FR25; FR34, FR35, FR36; ADR-0003, ADR-0004, ADR-0014, ADR-0017, ADR-0019, ADR-0021,
ADR-0025, ADR-0035 (`X9-MAP-020` to `-045`), ADR-0036 (the owner's § and ¶ included); INTERPRETATION
I-10, I-11, I-13, I-14

**Acceptance Criteria:**

**S10.5-AC1.** **Given** a verified notification on any rail
**Then** no `transactionId` makes it a pre-commit and a `transactionId` a post-commit (ADR-0004);
`blockchain.action` only corroborates and decides `NOT_SENT` alone; a `PAYMENT_INITIATED` carrying a
hash is a post-commit flagged `ACTION_CONTRADICTS_HASH`, and a warning names the payer; a `SENT`
without a hash is a 400.

**S10.5-AC2.** **Given** a pre-commit
**Then** the acceptance gate judges it at the payee's clock at receipt: `amount − tipAmount` against
the payload's price at that instant (S9.4-AC3, exact edges), the tip rules of I-10 in the notified
currency, `validUntil` inclusive; a refusal is a 400 naming `payment.amount` or `validUntil`.

**S10.5-AC3.** **Given** the QR Code's effective status at receipt
**Then** the pre-commit, post-commit and `NOT_SENT` rows of ADR-0035's map hold, cell for cell of
ADR-0036's matrix: a new holder reserves with `payment.initiated` (a takeover of a lapsed
reservation too); the holder's retry is judged again, refreshes the window and emits nothing, and a
refused retry releases the QR Code in the same transaction (`payment.failed` on a chain); another
payer is 409; once a hash is registered, no payer message changes the status (§); `PAID` or
`CANCELLED` refuse a pre-commit with 400.

**S10.5-AC4.** **Given** a post-commit
**Then** it is accepted in every status and never judged on amount or time: on `ACTIVE` it reserves
the QR Code for the sender with `payment.initiated`, then `payment.sent` on a chain; held by the
sender, by someone else, `PAID` or `CANCELLED`, the status does not change; every hash on a chain is
its own `payment.sent`; a bank rail never publishes `payment.sent`; a registered hash stops the
window, whoever sent it, and the holder does not change. Nothing a payer sends ever makes a QR Code
`PAID`.

**S10.5-AC5.** **Given** an accepted notification
**Then** it is stored as a `qr_code_notification` row (received time, `received_in_status` = the
effective status at receipt, `deviations`, phase, network, currency, amount, tip, transaction id,
`payer.info`, signer subject, the notification as accepted, the compact JWS) before its events, in
the same transaction, and each event is built by `BuildEvent` from that row; `deviations` and
`receivedInStatus` are on every event a notification produces.

**S10.5-AC6.** **Given** concurrent notifications
**Then** 32 pre-commits from different payers on one QR Code end with exactly one 200 and the rest
409; the same payer racing itself gets 200 (the re-run decides, ADR-0025); a notification that keeps
losing is a 409 "busy" with the current status, never a 500.

**S10.5-AC7.** **Given** `x9.reservation.ttl-seconds: 0`
**Then** a reservation never lapses and ends only by a refused retry, `NOT_SENT` or the status-update
API (ADR-0036); a Go test proves it on the test clock.

**S10.5-AC8.** **Given** the harness
**Then** these keys are added and pass: `X9-MAP-020` to `-045` and `-051`'s hash case;
`test_payment_notification.py`; `test_two_phase.py`; `test_settlement.py`; `test_tip_offers.py`;
`X9-HOLD-401`, `-402`, `-405` to `-407`; `X9-LIFE-507`; `X9-PATCH-433`; the `test_signatures.py`
keys that take a reservation; `test_time_edges.py` except `X9-TIME-013` (`java_only`) and
`X9-TIME-015` (Epic 12).

### Story 10.6: A PSP pays somebody else's QR Code through the Go build

As a **PSP integrating as a payer**,
I want the pre-payment and post-payment endpoints to sign, deliver and check for me,
So that I never build a JWS, and I know whether the payee's yes was really signed.

**Implements:** FR25; FR33 (the answer is checked); ADR-0015, ADR-0027 (certificate fetching),
ADR-0028 (2026-11-14 amendment), ADR-0036 (pre-commit on every rail); `R-EP-10`, `R-ADR0015`,
`R-I12-06` to `-08`

**Acceptance Criteria:**

**S10.6-AC1.** **Given** `POST /api/v1/payment-notification/pre-payment` or `post-payment`
**Then** a pre-payment carrying a `transactionId`, a post-payment without one, or a relative
endpoint is a 400 before anything is sent; otherwise the notification is composed, signed with this
deployment's key (S10.2-AC1) and posted as `application/jose` to the endpoint. There is no
rail-specific refusal: pre-commit exists on every rail (ADR-0036), and the pre-payment description
in `openapi.yaml`, which still tells FedNow and RTP payers to skip it, is corrected; no schema
changes.

**S10.6-AC2.** **Given** the delivery
**Then** redirects are not followed; no answer, a closed connection, or a whole answer (headers and
body) not received within 15 seconds is a 502 `notification-undeliverable`.

**S10.6-AC3.** **Given** the payee's answer
**Then** it is returned as a 200 with `accepted` and the payee's body, a refusal included; its
signature is checked against the truststore and its `correlationId` against the one sent, and
reported as `signature` `VERIFIED`, `ABSENT` or `INVALID` with a `signatureProblem` from the closed
list; a 200 that is not verified is still `accepted` (an unsigned yes). The check reports, never
refuses.

**S10.6-AC4.** **Given** the harness
**Then** these keys are added and pass: `test_payee_answers.py`; the payer-side cases of
`test_payers.py`; `X9-SIG-470` to `-475` of `test_golden.py`; `test_contract.py`'s cases for
`sendPrePaymentNotification` and `sendPostPaymentNotification`.

### Story 10.7: The decoder reads a scanned QR Code and fetches its payload

As a **payer's app**,
I want to send the scanned string and get the payee's verified payload, or a refusal I can act on,
So that I can tell "do not pay" from "try again".

**Implements:** FR25; ADR-0027 (EMV refused, never tolerated; certificate fetching); `R-EP-09`,
`R-EP-26`, `R-EP-28`, `R-I9-02`, `R-ADR0027-07`

**Acceptance Criteria:**

**S10.7-AC1.** **Given** `POST /api/v1/qrcode-emv-decoder`
**Then** malformed EMV (by `internal/emv`'s rules) is a 400 `business-rule-violation` whose
violation reads `qrCode: The QR Code is malformed: …`, before any fetch; a well-formed string with no
`org.x9` template carrying a URL is refused the same way, saying so.

**S10.7-AC2.** **Given** the location URL
**Then** a payload request signed with this deployment's key is sent to it; a URL is this
deployment's own only when its host, port included, equals the public host, ignoring case, and is
then read internally; any other is fetched over `https`, without user information; the payload's
JWS is verified (S10.2-AC1).

**S10.7-AC3.** **Given** the payee's answer
**Then** a refusal is a 400 `payload-refused` (any other 4xx the payee gave relayed the same way)
whose `violations` carry the payee's reason, capped and reduced to plain strings; no answer is a 502
`payload-unreachable` carrying no address; a payload is a 200 `PaymentPayloadResponse` with the
amount due priced by S9.4-AC3, and an unknown network in someone else's payload is carried intact
(I-9).

**S10.7-AC5. Strict to generate, tolerant to read (INTERPRETATION I-16; owner, 2026-11-15).**
**Given** a well-formed, correctly signed payload that another bank generated
**Then** nothing in it that this deployment does not know is a reason to refuse it: an unknown
payment method entry, an unknown rail, an unknown field inside `fednow`, `rtp`, `ach` or `solana`, and
a currency outside `supported-currencies.json` are parsed past and not interpreted, and the methods
and rails this deployment knows are presented as usual. The payload is read with a reader that never
refuses an unknown member: not with the create and PATCH checks of `internal/contract`, which refuse
them. Integrity failures stay refusals: malformed EMV (S10.7-AC1), a payload whose signature does not
verify (S10.7-AC2), a content mismatch the payee reports (S10.7-AC3).

**S10.7-AC4.** **Given** the harness
**Then** these keys are added and pass: `X9-DEC-016`; `X9-DEC-006`, `-007`; `test_contract.py`'s
cases for `decodeEmv`.

### Story 10.8: The whole suite passes on Go

As the **owner**,
I want the Go build to pass every test the Java build passes, and the ones Java fails by mistake,
So that Epic 11's differential run starts from two builds that each pass on their own.

**Implements:** FR24, FR25; ADR-0030 ("a black-box suite defines done"), ADR-0028

**Acceptance Criteria:**

**S10.8-AC1.** **Given** the Go run of Story 9.1
**Then** `go-required.txt` gives way to the whole suite under `X9_REQUIRE_ALL=1`: every test passes
except the `java_only` ones, skipped with their reason, and `X9-TIME-015` (retention, Epic 12), a
strict expected failure on Go that names Epic 12, so it turns red the day retention lands.

**S10.8-AC2.** **Given** `test_contract.py` on Go
**Then** its last line reads `N cases generated, N executed (0 known gaps, 0 failed)`: the Go build
enforces every constraint of the contract, with no gaps list of its own.

**S10.8-AC3.** **Given** `test_golden.py` and `test_database.py` on Go
**Then** every golden case gets the manifest's verdict and code on the service, every golden EMV
string is read and re-encoded identically, and the PostgreSQL inspector sees what the API says.

**S10.8-AC4.** **Given** the `blackbox-go` job
**Then** it runs the whole suite on every pull request touching the Go code, `db/` or
`others/blackbox/`, and fails the pull request on any failure; the Java job is unchanged.

**S10.8-AC5.** **Given** a behaviour where Go follows the standard or an ADR and Java does not (BUG-004,
the "cursor ahead of log" answer, an expired QR Code kept)
**Then** each is listed, with its test key, in the pull request for Epic 11's
`TOLERATED-DIFFERENCES.md`; no rule-coverage row loses its test.

## Epic 11: Proven equivalent to Java: the differential run and interop

Both builds already pass the whole black-box suite on their own (Story 10.8). This epic proves
they answer **alike**: the same inputs to Java and to Go, their normalised answers diffed, and every
difference either on a written list with its reason or a failure. It also proves the two builds pay
each other: a payer's PSP on one build, the payee on the other, in all four pairings.

**Implements:** FR26; ADR-0028 (equivalence is a differential run; the known differences),
ADR-0030 (the suite defines done), ADR-0034; plan §7 (phase 7).

Precedence stays X9.150 > this repository's ADRs > Java. A difference where Go follows the standard
and Java does not is tolerated, listed, and keeps its strict expected failure on Java; it is never
"fixed" by copying Java.

Order: 11.1, then 11.2; 11.3 and 11.4 alongside them.

### Story 11.1: The suite records what each build answered, and a diff compares them

As the **owner**,
I want every request the suite sends and every answer it gets recorded, per build, and compared,
So that "Go answers as Java does" is a diff, not a claim.

**Implements:** ADR-0028 ("normalised responses are diffed")

**Acceptance Criteria:**

**S11.1-AC1.** **Given** `X9_RECORD_DIR` set
**Then** the suite's HTTP client writes, for every test, each exchange in order: method, path
(ids masked), the request body, and the answer's status, `ETag`, `Content-Type`, `Location` and body.
Nothing changes when it is unset.

**S11.1-AC2.** **Given** two recordings, one per build
**Then** a diff tool pairs exchanges by test node id and position and compares them after
normalising: JSON re-serialised (whitespace and key order ignored); server-minted ids,
`correlationId`s, times, JWS signatures and certificates masked; a signed body compared on its
decoded header (minus per-run values such as `iat`) and its decoded payload. Status, `ETag`,
`Content-Type`, `Location` and the normalised body must match.

**S11.1-AC3.** **Given** a test whose exchanges differ in number (a `go_only` or `java_only` test, a
retry)
**Then** the diff reports it by name and does not misalign the rest.

**S11.1-AC4.** **Given** the diff tool
**Then** its own unit tests prove each masking rule, and that a real difference (a changed status, a
changed violation text, a member added or dropped) is caught.

### Story 11.2: Every tolerated difference is written down, and anything else fails the build

As an **integrator**,
I want every place the builds answer differently listed with its reason,
So that I know exactly what changes when I move to Go.

**Implements:** ADR-0028 (`TOLERATED-DIFFERENCES.md`; "a difference not on that list fails the build")

**Acceptance Criteria:**

**S11.2-AC1.** **Given** `others/blackbox/TOLERATED-DIFFERENCES.md`
**Then** it takes over ADR-0028's "Known differences between the builds" (every row), BUG-004, the
cursor ahead of the log and the expired QR Code kept; each entry has a number, the reason, the test
keys that show it, and a machine-readable matcher the diff applies (which tests, which exchange,
which field). ADR-0028's table points to it.

**S11.2-AC2.** **Given** a CI job `differential`
**Then** it runs the suite against both images with recording on (reusing the `blackbox` and
`blackbox-go` runs' recordings where possible), runs the diff, and fails on any difference that no
tolerated entry matches, and on any tolerated entry that matched nothing (stale).

**S11.2-AC3.** **Given** the first full diff
**Then** every untolerated difference it finds is either fixed (in Go; or in Java, when Java is
clearly wrong and the fix is small) or added to the list with a reason the owner accepts; none is
left failing.

### Story 11.3: The two builds pay each other, in all four pairings

As a **bank** running one build while its counterpart runs the other,
I want a payment to complete between them,
So that the migration can happen one bank at a time.

**Implements:** plan §7 ("`two-instance-payment-cycle.sh` in all four Java/Go pairings")

**Acceptance Criteria:**

**S11.3-AC1.** **Given** `others/demo/two-instance-payment-cycle.sh`
**Then** it takes an image (and so a build) per instance, and runs the whole cycle (create, decode,
payload fetch, pre-commit, post-commit, status `PAID`, events) for Java→Java, Java→Go, Go→Java and
Go→Go, with every signed answer verified by the other side.

**S11.3-AC2.** **Given** CI
**Then** a job runs the four pairings on pull requests touching the Go code, `db/`, the demo or the
suite, and fails if any pairing does not complete.

### Story 11.4: The known differences each have a Go test

As the **owner**,
I want every Go-only behaviour on the list proven by a test,
So that a regression in one is caught.

**Implements:** ADR-0028; Story 10.8's audit

**Acceptance Criteria:**

**S11.4-AC1.** **Given** the known differences with no Go test or a partial one (Story 10.8's audit:
rows 7, 15, 22 and the "no proxy" part of 17; rows 10, 13, 16 and 21 partly)
**Then** each gets a Go test (black-box where the suite can drive it, otherwise a handler or unit
test), keyed and listed in `docs/TEST-INDEX.md`, and its entry in `TOLERATED-DIFFERENCES.md` names
the key.

## Epic 12: Retention without surprises: housekeeping

The Go build keeps every QR Code and event forever today. This epic adds the one background job
ADR-0029 allows: it deletes old events, then old QR Codes, on the business clock, in small paced
batches, and never changes a status. An operator can see what it did and tune it. Done means
`X9-TIME-015` passes on Go, the last test the suite still expects to fail there.

**Implements:** FR27; ADR-0029 (time is applied on read; retention is the only job), ADR-0033 (the
test clock), ADR-0032 (the bank's own database), ADR-0023 (one changelog); plan §8 (phase 8).

Order: 12.1, then 12.2.

### Story 12.1: Old events, then old QR Codes, are purged on the business clock

As an **operator**,
I want old events and old QR Codes deleted automatically,
So that the database does not grow without bound.

**Implements:** ADR-0029 ("Retention is one goroutine with one `time.Timer`")

**Acceptance Criteria:**

**S12.1-AC1.** **Given** the configuration
**Then** `events.retention` (default `P30D`) and `qrcode.retention` (default `P400D`) are read, with
their environment variables, and the service refuses to start when `qrcode.retention` is shorter
than `events.retention`, naming both. The interval between runs is configurable (default one hour)
so a test deployment can run it often.

**S12.1-AC2.** **Given** a run
**Then** it first deletes `payment_event` rows older than `events.retention`, then `qr_code` rows
whose `valid_until` **and** `revised_at` are both older than `qrcode.retention`, in any status,
together with their `qr_code_audit_log` and `qr_code_notification` rows. Cutoffs come from the
business clock (ADR-0033), computed once per run.

**S12.1-AC3.** **Given** many rows to delete
**Then** each purge works in batches of a few hundred rows, one transaction per batch, with a pause
between batches, so a payment never waits behind it; the queries use indexes added by a new
changeset (applied changesets are never edited).

**S12.1-AC4.** **Given** the job
**Then** it is one goroutine with one `time.Timer`, reset after each run; it never changes a status;
it stops cleanly on shutdown (no leaked goroutine); a run that fails is logged and retried at the
next tick, never crashing the service.

**S12.1-AC5.** **Given** the black-box suite on Go
**Then** `X9-TIME-015` passes and its strict expected failure on Go is removed; Go database tests
cover the cutoffs, the "both dates" rule, the child rows, and that nothing younger is touched.

### Story 12.2: An operator can see what retention did, and size it

As an **operator** or a bank's **DBA**,
I want to know what the job deleted and what retention costs in storage,
So that I can tune it without guessing.

**Implements:** ADR-0029 (Consequences: "Operators who want less shorten `qrcode.retention`"),
ADR-0032

**Acceptance Criteria:**

**S12.2-AC1.** **Given** a run
**Then** one INFO log line reports the cutoffs, rows deleted per table and the duration; Prometheus
metrics count deleted rows per table and run failures.

**S12.2-AC2.** **Given** the docs
**Then** `RUNNING.md`, `db/README.md` and the configuration example explain the two retentions,
their defaults, the start-up rule, the interval, and what an operator gives up by shortening them
(an expired QR Code is readable until `qrcode.retention`; a consumer must read events within
`events.retention`), with the storage measured in `BENCHMARK.md` as a sizing guide.

## Epics 13–17: stubs

Detailed in a later run of this workflow.

| Epic | Phase | Scope |
|---|---|---|
| 13 | 9 | Operators can migrate data and configuration, reservation holders included; the legacy `SPRING_*`, `MONGO*` and `JASYPT_*` variables are housekept out of the Helm chart, the compose files and the docs, since the Go service refuses them (owner, 2026-11-14) (FR28) |
| 14 | 10 | The lighter image ships (FR29) |
| 15 | 10b | `BENCHMARK.md` published (FR30) |
| 16 | 11 | Cutover; `make` targets renamed (FR31) |
| 17 | 12 | Java frozen in `legacy/java/` (FR32) |

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
