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

- **Detailed stories:** plan phases 0, 1 and 1b (track A, pin behaviour on Java), and 2 and 3
  (track B, Go/PostgreSQL foundations).
- **Epic stubs only:** phases 4–12.

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

FR16: The changelog creates the three roles of ADR-0032:
- **owner:** migrations only;
- **application:** DML on its own tables;
- **read-only:** `SELECT` only.

It also writes explicit grants and `ALTER DEFAULT PRIVILEGES`, and sets the application role's
`search_path` (`ALTER ROLE … SET search_path`). The schema name is a changelog parameter.

FR17: Compose runs PostgreSQL 16 plus a `liquibase/liquibase:4.33.0` one-shot. CI applies the
changelog on PostgreSQL 16 and on the newest major. `liquibase update-sql` produces DBA-reviewable
SQL.

FR18: A CI check enforces the changelog conventions:
- no edits to applied changesets;
- `COMMENT` present for every table and column;
- no banned non-portable constructs (`JSONB`, `ENUM`, arrays, `TIMESTAMPTZ`, unbounded `TEXT`,
  `SERIAL`, triggers or procedures).

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
again; post-commit without a pre-commit is refused (to be confirmed). The evidence decides the phase on every
rail, Solana included (ADR-0004): a `PAYMENT_INITIATED` carrying a hash is payer misbehaviour, and is
still a post-commit, recorded and logged as a warning. Every hash is recorded, a second one included,
so the payment system can detect a duplicate payment. Today Solana keys on `blockchain.action`,
FedNow/RTP refuse a notification without an End-to-End ID (no pre-commit, no
race protection) and ACH gates every notification as a pre-commit. Fixed in Java first.

FR35: The reservation holder may retry its pre-commit (same `payer.info` and certificate subject): the retry
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
| FR16 | Epic 6 | three roles, grants, default privileges, role `search_path` |
| FR17 | Epic 6 | compose + Liquibase one-shot; CI on PostgreSQL 16 and newest; `update-sql` |
| FR18 | Epic 6 | changelog conventions check |
| FR19 | Epic 7 | Go skeleton: config, logging, pools, schema check, health, shutdown |
| FR20 | Epic 7 | `internal/clock` + lint bans |
| FR21 | Epic 7 | CI Go job |
| FR22 | Epic 7 | multi-stage `Dockerfile` + `make` wrappers |
| FR23–FR32 | Epics 8–17 | stubs for plan phases 4–12 |

## Epic List

**Track A: pin behaviour on Java.** Epics 1–5 follow each other: 1 → 2 → 3 → 4, and 5 can run
alongside 3–4.

**Track B: Go/PostgreSQL foundations.** Epics 6–7 follow each other, and the track runs in parallel
with track A.

**Stubs.** Epics 8–17. Epics 10–12 (the code that carries the business rules) wait for Epic 4.

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

A DBA can review and apply the schema: one configurable schema, documented tables, three roles,
SQL they can run themselves. CI proves the result on PostgreSQL 16 and on the newest major.

**FRs covered:** FR15, FR16, FR17, FR18

### Epic 7: An operator can run the Go service and see it healthy

An operator can build and start the Go container against that schema. It refuses bad or old
configuration, checks its schema, reports health in the existing shape and shuts down gracefully.
CI enforces the code rules from the first commit. There are no business endpoints yet.

**FRs covered:** FR19, FR20, FR21, FR22

### Epics 8–17: stubs (detailed later)

| Epic | Phase | Scope |
|---|---|---|
| 8 | 4 | X9.150 crypto in Go: EMV, JWS, keys (FR23) |
| 9 | 5 | Billers manage payment requests on Go (FR24) |
| 10 | 6 | Payers fetch, notify and are evented on Go (FR25) |
| 11 | 7 | Proven equivalent to Java: differential run and interop (FR26) |
| 12 | 8 | Retention without surprises: housekeeping (FR27) |
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

**S2.3-AC2.** **Given** a refused notification (wrong amount, expired, another holder, post-commit without
pre-commit)
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

**Implements:** FR34; ADR-0036 (proposed), ADR-0021

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
**And** a post-commit from B while A holds is refused with 409 too. B never received a signed yes, so B
paid without authorization and that payment is B's to resolve; refusing it is intended, not a
contradiction of "money that moved is never refused" (which protects the holder only)
**And** INTERPRETATION states this: the only payment X9.150 authorizes is the one that won the pre-commit.

**S2.4-AC3. Post-commit on the bank rails.**
**Given** a notification with a `transactionId` (End-to-End ID or Trace Number) from the holder
**Then** it is recorded, status unchanged, with no amount or time check, and answered with a signed
yes; no `payment.sent` (bank rails never emit it).

**S2.4-AC4. No post-commit without a pre-commit (to be confirmed with the owner).**
**Given** an `ACTIVE` QR Code nobody reserved
**When** any rail's post-commit arrives (a payer who skipped the pre-commit)
**Then** it is refused, and nothing changes: pre-commit is what blocks duplication, and a payer who
skipped it holds no signed yes
**And** a holder whose reservation lapsed may still send a post-commit (ADR-0021), unless another payer has
since won the pre-commit
**And** the change is noted for integrators: FedNow/RTP payers who send only the after-payment
notification today now get 400 (the payee still reconciles from `QRCD` in the pacs.008).

**S2.4-AC5. Solana follows the evidence, and a contradiction is recorded loudly.**
**Given** a Solana notification
**When** `action = PAYMENT_INITIATED` carries a transaction hash
**Then** it is a post-commit: recorded as received (its declared `action` included), never judged on
amount or time, answered with a signed yes, and logged as a warning naming the payer
**And** a code comment and INTERPRETATION say plainly that this is bad payer behaviour, and that the
hash is kept because funds that arrive in the right amount with a matching hash are enough for the
payment system to mark the QR Code paid
**And** `SENT` without a hash is still refused with 400, and `NOT_SENT` still releases.

**S2.4-AC6. Every transaction hash is recorded.**
**Given** the holder has already reported a hash
**When** it reports another one
**Then** the second is recorded beside the first, never overwriting it, and published as its own
`payment.sent`
**And** X9.150 never marks the QR Code paid itself: the payment system matches each hash against the
funds and calls the status-update API, and two hashes for one QR Code are its signal of a duplicate
payment.

**S2.4-AC7. Docs.**
**Then** STATE-MACHINE, INTERPRETATION and the ADR-0035 transition map get the new rows, and the
deviation recorded by S1.1-AC4 is closed.

### Story 2.5: The same payer can retry, a refused retry frees the QR Code, and a takeover is visible

As a **payer recovering from a failure**, and as a **payee**,
I want my own retried pre-commit accepted or refused on its merits, and a refusal to free the QR Code,
So that a lost answer can be retried, and a refused payer does not keep everyone else out.

Today Java accepts the holder's retry (`validateAnnouncement`, `ReservationHolderVO.isSameParty`)
and judges it again at the payee's clock (correct). Two gaps: a refused retry leaves the reservation
in place until it lapses, and a takeover of a lapsed reservation emits no event.

**Implements:** FR35; ADR-0036 (proposed), ADR-0019, ADR-0021

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
**And** with no `payer.info` they read as the same party (the payer's bank); a test pins this and
INTERPRETATION warns PSPs to send `payer.info`
**And** two anonymous announcements never match.

**S2.5-AC5. A takeover is evented.**
**Given** A's reservation lapsed (stored status still `PAYMENT_INITIATED`)
**When** B wins the pre-commit
**Then** `payment.initiated` is emitted for B, because the transition is decided on the effective
status, not the stored one
**And** the test asserts the events, not only that nothing was thrown.

**S2.5-AC6. Docs and map.**
**Then** STATE-MACHINE, INTERPRETATION and the ADR-0035 transition map get the "refused retry
releases" and "takeover emits" rows.

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
**When** B announces or reports with its own key
**Then** it is refused with 409.

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
built in Story 2.3, AC6). Order note: this story precedes CI (3.6), which runs it.

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
the QR Code's; tip `allowed: false` with a range and `true` without one; a negative amount; a
19-digit amount (today's behaviour pinned); a host too long for EMV tag 26.

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
**Then** per rail: pre-commit reserves (FedNow, RTP, ACH, Solana); post-commit only from the holder; post-commit
without pre-commit refused; wrong amount; tip outside its range; tip on top of the bill (ADR-0017);
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
30 clamps; `hasMore`; replay from an old cursor is safe by `eventId`.

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
| T8 | after a lapse, B's pre-commit accepted with `payment.initiated` (Story 2.5); A's late `SENT` refused |
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
6.4 alongside 6.1.

### Story 6.1: The changelog creates the schema of ADR-0024, documented in the database itself

As a **bank's DBA**,
I want every table and column created by a reviewable changelog and described by a `COMMENT`,
So that I can understand what X9.150 stores without reading Go code.

**Implements:** FR15; ADR-0024, ADR-0025, ADR-0026

**Acceptance Criteria:**

**S6.1-AC1.** **Given** `db/changelog/` applied to an empty schema
**Then** it creates `qr_code`, `qr_code_audit_log`, `qr_code_notification`, `payment_event`,
`event_sequence` (one row, `last_seq = 0`) and `payment_event_legacy_cursor`, with the columns of
ADR-0024/0025/0026 (including the reservation holder `reserved_by_payer_info` and
`reserved_by_signer_subject`, and the raw compact JWS on `qr_code_notification`).

**S6.1-AC2.** **Given** the lookups `qr_code_status` and `event_type`
**Then** they are seeded with every status and event type, and referenced by FK from `qr_code` and
`payment_event`.

**S6.1-AC3.** **Given** the indexes
**Then** `location_id` is unique across all rows, every FK has an index, and
`payment_event(occurred_at)` serves the purge
**And** there is no FK from `payment_event` to `qr_code`.

**S6.1-AC4.** **Given** the catalog
**Then** `obj_description` / `col_description` return a non-empty comment for every table and
column.

**S6.1-AC5.** **Given** the schema name as a changelog parameter (default `x9_qrcode`)
**Then** the changelog writes nothing outside that schema, and nothing in `public`.

### Story 6.2: Three roles with the least privilege each needs

As a **bank's DBA**,
I want X9.150's roles to be able to do only their own job,
So that the application can never alter the schema and the test suite can never write.

**Implements:** FR16; ADR-0032

**Acceptance Criteria:**

**S6.2-AC1.** **Given** the changelog
**Then** it creates (or uses, when the DBA pre-created them) an owner role (migrations only), an
application role (DML on its own tables) and a read-only role (`SELECT` only), with explicit grants
and `ALTER DEFAULT PRIVILEGES`.

**S6.2-AC2.** **Given** the application role
**Then** `ALTER ROLE … SET search_path` points it at the schema
**And** it cannot `CREATE`, `ALTER` or `DROP` anything, nor `DELETE` from append-only tables except
through the retention purge's grant.

**S6.2-AC3.** **Given** the read-only role
**Then** every write fails with a permission error; the black-box suite's database checks use it.

**S6.2-AC4.** **Given** a role name or password
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
**Then** it tells a DBA: what is created, the three roles, the connection ceiling (12 from the two
pools), PgBouncer ≥ 1.21 in transaction mode, TLS, and that nothing couples to other schemas.

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
**Then** they answer in the existing JSON shape; readiness is DOWN until the schema check passes and
while the database is unreachable.

**S7.3-AC2.** **Given** SIGTERM
**Then** readiness goes DOWN, in-flight requests finish within the ADR-0031 timeout, pools close,
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

## Epics 8–17: stubs

Detailed in a later run of this workflow, once Epic 4 is green. Scope now includes the Epic 2
behaviour (signed answers and their verification, pre-commit on every rail, the holder retry and
takeover event, exact `validUntil`), which Epics 9–10 port and Epic 11 proves equivalent.

| Epic | Phase | Scope |
|---|---|---|
| 8 | 4 | X9.150 crypto in Go: EMV, JWS (including signed answers), keys; the test-clock listener and its certificate guard (FR23) |
| 9 | 5 | Billers manage payment requests on Go (FR24) |
| 10 | 6 | Payers fetch, notify and are evented on Go, under the pre-commit / post-commit rule (FR25) |
| 11 | 7 | Proven equivalent to Java: differential run and interop (FR26) |
| 12 | 8 | Retention without surprises: housekeeping (FR27) |
| 13 | 9 | Operators can migrate data and configuration, reservation holders included (FR28) |
| 14 | 10 | The lighter image ships (FR29) |
| 15 | 10b | `BENCHMARK.md` published (FR30) |
| 16 | 11 | Cutover; `make` targets renamed (FR31) |
| 17 | 12 | Java frozen in `legacy/java/` (FR32) |

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
