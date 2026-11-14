# Plan — Rewrite in Go on PostgreSQL

Status: **Design accepted 2026-11-13** (ADRs 0022–0035); execution not started
Work breakdown: [docs/rewrite/EPICS-AND-STORIES.md](docs/rewrite/EPICS-AND-STORIES.md) — epics, stories
and acceptance criteria with IDs the tests cite
Decisions: [ADR-0022](docs/adr/0022-rewrite-in-go-as-a-standalone-product.md) –
[ADR-0035](docs/adr/0035-one-event-builder-and-a-written-transition-map.md)
Owner's intent: same repository, Java kept frozen in `legacy/java/` for history, and the same Docker
Hub image (`materainc/x9-qrcode`) becomes lighter while behaving as before.
Precedence ([ADR-0022](docs/adr/0022-rewrite-in-go-as-a-standalone-product.md)): **the ANSI X9.150
standard** > **this product's published contract and accepted ADRs** > **Matera's internal Go and
PostgreSQL engineering standards**. The internal standards are not copied here; they show only
through the code, the lint configuration, the CI Go job and the Liquibase changelog conventions.

This plan is the execution document. It is deleted when the work ships. The decisions live in the
ADRs, and where the two disagree the ADR wins.

---

## 1. Evaluation: is it worth it?

**Yes.** The owner's three drivers, none of which depends on who wins the benchmark:

1. **Memory.** One small Go container instead of a JVM.
2. **No second database.** A small bank can host X9.150 in a schema of the PostgreSQL it already
   runs ([ADR-0032](docs/adr/0032-x9150-can-live-in-the-banks-own-database.md)). A big bank will
   split it out anyway, and that also works.
3. **Simplicity.** Storing and signing a QR Code's JSON should not take forty mappers
   ([ADR-0024](docs/adr/0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)).

The benchmark (§9) is published to state facts, not to make this decision. Remaining blocker: B2 (§8).

### 1.1 What the Java codebase actually is

| Measure | Value |
|---|---|
| Main code | 290 files, **19.8k LOC** (domain 4.9k, application 5.2k, infrastructure 9.7k) |
| Tests | 71 files, 11.5k LOC, 293 methods, mostly Spring + Testcontainers Mongo; **0 tests in `application`** |
| Shape conversion | **~9.1k LOC (~46%)**: 47 app DTOs, 9 enum DTOs, 40 mappers, Mongo models and mappers, Input/Output records; plus 93 DTOs generated from OpenAPI at build time |
| Behaviour | **~5–6k LOC (~28%)**: entity, validator, acceptance and currency policies, formula, JWS, events |
| A domain enum crosses… | ~5 representations between the wire and the database |

The complexity you feel is mostly that mapping layer. The domain itself is small and well
understood: 21 ADRs, a state machine and an interpretation document.

### 1.2 What is gained

| Gain | Weight |
|---|---|
| MongoDB goes, and with it the replica-set requirement, the `7.0.45` kernel pin, `mongo-setup`, and a data store no other Matera service runs | **High** |
| A small bank runs **no second database**: one schema in its existing PostgreSQL, three roles, bounded connections (ADR-0032) | **High** — the owner's main driver |
| Four tables and one struct from wire to database replace ~9k lines of DTOs, mappers and persistence models (ADR-0024) | **High** |
| The event log gets simpler: no outbox array, drain, drain lease or merge-before-save ([ADR-0025](docs/adr/0025-a-transition-is-one-transaction-and-its-event-is-in-it.md)); one event builder and a written transition map ([ADR-0035](docs/adr/0035-one-event-builder-and-a-written-transition-map.md)) | **High** — removes the three subtlest invariants in AGENTS.md |
| An audit log of every PATCH's replaced value instead of silent overwrite ([ADR-0024](docs/adr/0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)) | Medium |
| Expired QR Codes stop vanishing after 30 s ([ADR-0029](docs/adr/0029-time-is-applied-on-read-and-retention-is-the-only-job.md)) | Medium — fixes Q19a |
| One language, one toolchain and one deployment shape with Matera's other payment software (Kubernetes, Liquibase run before the binary) | Medium |
| Static binary, ~10× smaller memory than a Spring JVM, instant start | Low–medium (not a bottleneck today) |
| Fewer dependencies: Nimbus, Bouncy Castle, Jasypt, the vendored EMV jar and Spring Cloud Kubernetes all go | Medium — also clears SECURITY.md's carried jasypt advisory |

### 1.3 What it costs and risks

| Cost / risk | Mitigation |
|---|---|
| Java tests don't port | Black-box suite first, green on Java, then a differential run Java vs Go ([ADR-0028](docs/adr/0028-the-http-contract-is-frozen-and-equivalence-is-proved-by-a-differential-run.md), [ADR-0030](docs/adr/0030-a-black-box-suite-defines-done-and-migration-writes-only-runtime-valid-rows.md)) |
| Go has no built-in revocation checking | Explicit CRL check with OCSP fallback and soft-fail, test CA fixtures, policy unchanged ([ADR-0027](docs/adr/0027-crypto-in-go-x9150-jws-profile-pkix-with-crl-and-emv-by-hand.md)) |
| JWS `crit` / milliseconds subtleties | Own verification sequence on go-jose's parser, golden corpus from Java, fuzzing (ADR-0027) |
| JKS and PKCS#11 HSM support | JKS → PKCS#12 by `x9-config-convert` ([ADR-0031](docs/adr/0031-same-image-lighter-and-3-0-is-a-configuration-migration.md)); HSM behind `crypto.Signer`, deferred (§8 B2) |
| Operators must migrate configuration | 3.0.0 is a documented configuration migration with a converter; old configuration makes the binary refuse to start and name the converter (ADR-0031) |
| Consumer cursors at cutover | **Translated, not reset**: migrated events keep their ULID in `payment_event_legacy_cursor`; `eventId` dedupe covers any overlap ([ADR-0026](docs/adr/0026-identifiers-and-the-event-cursor.md)) |
| Feature freeze on Java during the rewrite | Keep the freeze short; any rule added to Java must land as a black-box test first |
| The portable SQL dialect is stricter than PostgreSQL | Fine for this workload; no recorded exceptions ([ADR-0023](docs/adr/0023-postgresql-one-schema-portable-sql.md)) |

### 1.4 Things found during the evaluation (fix or record; do not port silently)

| Finding | Where | Disposition |
|---|---|---|
| ADR-0002 describes `findAndModify`; the code does whole-document `@Version` saves in a transaction | `QRCodeMongoRepository` | Superseded by ADR-0025 |
| PATCH overwrites the request with no trace; `qrcode_history` model exists but nothing writes it | `QRCodeMongoRepository.java:100`, `QRCodeHistoryMongoPersistenceModel.java:18` | Fixed by ADR-0024: `qr_code_audit_log` logs the replaced value and its time, for audit. `GET ?revision=` keeps today's contract (current only) |
| **ETag bug.** The ETag `"<revision>-<STATUS>"` is built from the *effective* status on GET but `If-Match` is compared against the *stored* status, so once a reservation lapses every conditional request answers 412 | `PaymentRequestsController` / ETag handling | **Fixed in both builds in phase 1** ([ADR-0028](docs/adr/0028-the-http-contract-is-frozen-and-equivalence-is-proved-by-a-differential-run.md)): one `ETag(row, now)` on effective status, used for both GET and `If-Match` |
| **Discount edges differ.** Eligibility ends at the instant `dueDate − daysBefore` (line 59), but the advertised `validUntil` of the discounted amount is `23:59:59` UTC of that day (line 91). They can be up to ~21 h apart | `FixedDiscountLateFeeLinearInterestFormulaService.java:59,91` | **Resolved** (Q1): the advertised `validUntil` becomes the exact edge, so there is one edge; fixed in Java first (Story 2.6), then pinned (§4.2 T1, T3) ([ADR-0033](docs/adr/0033-the-clock-can-be-moved-in-test-deployments-only.md)) |
| `FormulaService` throws `IllegalCallerException` when the payment date **equals** the due date | `FixedDiscountLateFeeLinearInterestFormulaService` | **Write a failing black-box test on Java first**, the intended result is decided (Q1, resolved: on time, full amount), fix in both |
| Java treats an expired-but-`ACTIVE` location holder as releasable only because MongoDB already deleted it | location release | Closed explicitly by ADR-0024's location rule, now that rows are retained (ADR-0029) |
| ADRs 0003, 0006, 0007, 0011 accepted but not implemented | — | Not ported ([ADR-0022](docs/adr/0022-rewrite-in-go-as-a-standalone-product.md)); status to be revisited (Q4) |
| `StandardRailKeys` accepts `solana`; ADR-0012's text lists only bank rails | web mapper | Code is right (ADR-0010); note in INTERPRETATION |
| `pegged-currencies.json` carries a BRL group while `supported-currencies.json` refuses BRL | config | Harmless; keep identical files on cutover |
| The doc drift listed earlier (status name `INITIATED` vs `PAYMENT_INITIATED`, Solana missing from README/STATE-MACHINE) | docs | Fix before phase 0, so the suite tests the right words |

### 1.5 Verdict as a trade-off

- **Go + PostgreSQL** (recommended): the largest simplification and alignment with Matera's other
  payment software, at the cost of a rewrite and a 3.0.0 configuration migration for operators.
  API consumers notice nothing (ADR-0028, ADR-0026).
- **Java + PostgreSQL via JdbcTemplate** (fallback): gets the database gains (about 60% of the
  value) for about 40% of the effort, keeps the tests, and keeps two stacks.
- **Status quo**: no cost now. MongoDB's kernel and support clock (7.0 EOL 2027-08-31, README) will
  force a database decision within a year anyway.

---

## 2. Target shape

```
x9.150-qrcode/
├── cmd/x9-qrcode/main.go          wiring only: slog, config, two pgxpools (write/read), mux, retention timer, Ready()
├── cmd/x9-migrate/main.go         Mongo → PostgreSQL (ADR-0030) ┐ shipped in the separate migrate image tag
├── cmd/x9-config-convert/main.go  Spring config → Go config      ┘ (ADR-0030/0031), NOT in the service image
├── legacy/java/                   the Maven modules, moved with git mv, frozen, not built in CI (ADR-0022)
├── internal/
│   ├── paymentrequest/            create, get, patch, status-update — handler, service, store
│   ├── payload/                   POST /pub/api/v1/loc/{id} — formula, quote assembly, signing
│   ├── notification/              inbound /pub notifications + outbound pre/post-payment (ADR-0015)
│   ├── events/                    GET /pub/api/v1/events — reader, ULID cursor translation, in-process long-poll waiters
│   ├── domain/                    QRCode, effectiveStatus, the transition map (ADR-0035), EMV(location, config), BuildEvent, ETag, policies (pure Go, no I/O)
│   ├── jws/                       X9.150 profile: go-jose v4 parser + own verification, PKIX + CRL/OCSP, cert fetch/cache
│   ├── emv/                       TLV + CRC16, tag 26
│   ├── keys/                      crypto.Signer from PKCS#12/PEM, reload timer
│   ├── clock/                     the one business clock; test offset on a separate listener (ADR-0033)
│   ├── retention/                 one goroutine, one time.Timer: purge events, then QR Codes (ADR-0029)
│   └── config/                    one YAML file + documented env vars, validated at start; refuses old config
├── db/changelog/                  Liquibase: initial changelog, then numbered increments; COMMENTs; grants
├── db/seed/                       lookup rows (statuses, event types)
├── x9-qrcode-infrastructure/src/main/resources/apis/openapi.yaml   ← moves to api/openapi.yaml, unchanged
├── others/blackbox/               pytest + x9client helpers, under this repo's licence ← the definition of done (ADR-0034)
│   └── TOLERATED-DIFFERENCES.md   every tolerated Java/Go difference, with its reason (ADR-0028)
└── deploy/ (compose, Helm)        PostgreSQL instead of Mongo; Liquibase 4.33.0 one-shot / init container
```

`domain/` is the one package that holds rules. Handlers decode the contract types, `domain` decides,
and `store` writes rows. **There is one set of structs.** The type decoded from the HTTP body is the
type validated, stored as `request_json`, read back, and signed into the payload. There are no
DTOs, no `*UpdateDTO`, no enum mirrors and no mappers. PATCH decodes into the same struct with
presence tracking (`json.RawMessage` per field, or a small `Optional[T]`), replacing the Java
`PartialInput<T>` + `*UpdateDTO` pair.

Three `domain` functions each have exactly one owner role:

- **`EMV(location_id, emvConfig)`** computes `emv_content`, written only with `location_id` (create, PATCH, rebind); `valid_until` is computed from `request_json`. The location is a column, not part of `request_json` (ADR-0024).
  Only create and PATCH call it, and they write its result in the same statement as `request_json`
  (ADR-0024). `x9-migrate` calls the same function (ADR-0030).
- **`BuildEvent(type, row, lastNotification, now, reason)`** builds every `payment_event`, following
  Java's body rule field for field (ADR-0035).
- **`ETag(row, now)`** builds the ETag from the effective status; `If-Match` compares against the same
  function (ADR-0028).

**The transition map** of ADR-0035 is normative: each trigger, the status it reads (stored or
effective, ADR-0029), the status after, and the event it emits. Every row is pinned by the suite.

### 2.1 Schema sketch (ADR-0024 / 0025 / 0026 / 0029 / 0032)

| Table | Key columns |
|---|---|
| `qr_code` | `id UUID PK` (v7), `location_id UUID` (v4, **UNIQUE across all rows**), `status_code VARCHAR(4)`, `revision INT`, `lock_version INT`, `request_json JSON` (request fields only — never lifecycle data), `valid_until`, `reserved_until` (sentinel `9999-12-31`), `reserved_by_payer_info VARCHAR(…)`, `reserved_by_signer_subject VARCHAR(1024)`, `end_to_end_id`, `payment_network`, `emv_content VARCHAR(512)`, `created_at`, `revised_at`. **`location_id` and `emv_content` are written together (create, PATCH, rebind), `emv_content` only via `domain.EMV`; `valid_until` from `request_json` by create and PATCH**; lifecycle columns, and `revised_at` on every transition, only by the transition functions |
| `qr_code_audit_log` | audit log of updates: PK `(qr_code_id, revision)` = the revision **before** `++`, `changed_at TIMESTAMP`, `changed_fields VARCHAR(128)` (which of the six top-level tags changed), `previous_values JSON` (**only those tags**, with their previous values; `null` = had no value); append-only, purged with its QR Code |
| `qr_code_notification` | every **accepted** inbound notification: `id UUID PK`, `qr_code_id`, `received_at`, `phase`, `network`, `amount`, `tip_amount`, `currency`, `transaction_id`, `payer_info`, `signer_subject`, `jws_compact` (empty for migrated rows); the newest row is the "last stored notification" of ADR-0035 |
| `payment_event` | `seq BIGINT PK`, `event_id UUID UNIQUE` (v7 for new events), `qr_code_id` (**no FK to `qr_code`**, ADR-0029), `qr_code_revision`, `type_code`, `occurred_at`, payload columns per EVENTS contract, `schema_version` |
| `event_sequence` | one row: `id SMALLINT PK = 1`, `last_seq BIGINT`; incremented by `UPDATE`, then read by `INSERT … SELECT last_seq` in the same batch (ADR-0025); no `RETURNING` |
| lookups | `qr_code_status`, `event_type` |

There is **no `housekeeping_schedule` table** (retention is one timer, ADR-0029) and **no
`create_idempotency` table** (`Idempotency-Key` is deferred until after cutover, ADR-0026/0028).

**Schema and roles (ADR-0032).** Schema name configurable (default `x9_qrcode`); **SQL is never
schema-qualified** — the connection's `search_path` selects it. Three roles: **owner** (migrations
only, through Liquibase), **application** (DML on its own tables), **read-only** (`SELECT`, for
auditors and the suite's below-HTTP checks). The changelog grants explicitly and sets `ALTER DEFAULT
PRIVILEGES`; `liquibase update-sql` serves banks whose DBA runs the DDL.

Indexes:

- the unique index on `qr_code(location_id)` (all rows, not partial — ADR-0024);
- `payment_event(occurred_at)` for the event purge;
- `payment_event_legacy_cursor(legacy_cursor PK, seq)` for cursor translation, with rows for migrated events only (ADR-0026);
- an index serving the QR Code purge (`valid_until`, `revised_at`);
- an index on every FK (`qr_code_notification.qr_code_id`; the audit log's PK covers its own).

Exact column widths are settled in phase 2 against the OpenAPI `maxLength`s, and so is how

---

## 3. Phases

Each phase is one reviewable PR. The black-box suite is green against **Java** until phase 7, and
from phase 7 on the **differential run** compares Java and Go.

| # | Phase | Deliverable | Exit criterion |
|---|---|---|---|
| 0 | **Docs truth** | Fix the drift in §1.4 (status naming, Solana, ADR-0012 note); due-date formula case resolved (Q1) | Docs and Java agree |
| 1 | **Pin behaviour** | **Java changes, the last before the freeze:** the test clock (ADR-0033) on all four time reads — `DateTimeUtils.nowUTC()`, the two `Clock.systemUTC()` reads in the JWS service, and `OffsetDateTime.now` in `RetrieveQRCodePayloadInput.java:24` — and the **ETag fix** (ADR-0028), plus the owner-approved protocol fixes: signed answers to notifications and their verification (spec A.4.5), leg 1 on every rail, the holder's retry and the takeover event, and the exact `validUntil` of an adjusted amount ([Epic 2](docs/rewrite/EPICS-AND-STORIES.md)). **Suite:** `x9client` (independent JWS signer + tamperer, clock, EMV, optional read-only DB inspectors) and `contract.py` (refusal cases generated from `openapi.yaml`) in `others/blackbox/` (ADR-0034); scenario catalogue §4.1–4.2 as tests; fold `acceptance.py` in; run in CI; tests cover every rule in STATE-MACHINE, ENDPOINTS, INTERPRETATION and ADR-0004…0021; a **rule-coverage table** mapping each rule to a test; **verify ADR-0035's transition table against the Java code** and correct any row Java contradicts; export golden JWS (signed + tampered + expired + wrong-crit) and golden EMV strings from Java | Suite green on Java; every row of the rule-coverage table and of the transition map has a test |
| 1b | **Benchmark harness + Java baseline** | `others/benchmark/` per §9: k6 scenarios S1, S2, S7, k8s manifests, collection scripts; **baseline Java+MongoDB run while Java is still the live build** | Raw results for Java committed under `others/benchmark/results/` |
| 2 | **Schema** | Liquibase changelog per §2.1 with `COMMENT`s, three roles, explicit grants and default privileges; lookup seed; compose with PostgreSQL 16 and a Liquibase 4.33.0 one-shot | `liquibase update` clean on an empty DB, on 16 and the newest major; the changelog conventions check in CI passes; `update-sql` output reviewed |
| 3 | **Skeleton** | `cmd/x9-qrcode`, config, slog, two pgxpools, `internal/clock` (lint bans `time.Now` & co. elsewhere), actuator-compatible `/actuator/health`, `golangci-lint`/`govulncheck`/`go-licenses` CI | Binary starts against compose; health UP |
| 4 | **EMV + JWS + keys** | `internal/emv`, `internal/jws`, `internal/keys`; JWKS and certificate endpoints; `/api/v1/signature/*`, `/api/v1/qrcode-emv-decoder` | Golden corpora pass; fuzz targets in CI; test-CA revocation cases pass (CRL, OCSP fallback, no responder, untrusted, EC) |
| 5 | **Payment requests** | create, get, patch with audit row (ADR-0018, 0024), status-update (ADR-0016, 0020), currency and peg policies, network refusal, location rule (holder released first), `EMV`, `ETag`, PATCH allowed only from stored `ACTIVE`/`PAYMENT_INITIATED`. **No `Idempotency-Key`** (deferred, ADR-0026/0028) | The payment-request sections of the suite pass on Go |
| 6 | **Payload + notifications + events** | `/pub/api/v1/loc/{id}` with formula; inbound notifications with acceptance policy, reservation and holder (0019, 0021); outbound pre/post (0015); `BuildEvent` and the transition map (0035); event sequence row (0025); `GET /events` with in-process long-poll and ULID cursor translation (0026). **Transaction rules (ADR-0025/0032):** 0 rows on the conditional `UPDATE` ⇒ re-run the whole script from the read, up to 3 times, 409 "busy" only when exhausted; `READ COMMITTED` set on every transaction; two pools (writes 8, reads 4); `lock_timeout` 2 s on event transactions, `statement_timeout` 30 s; the event is the last step, one pgx batch of `UPDATE event_sequence` + `INSERT payment_event … SELECT last_seq` (no `RETURNING`); retry on 40001/40P01/55P03, never 57014; notification row inserted before the event; no transaction spans an outbound HTTP call | Whole suite green on Go; concurrency test: 32 concurrent notifications → exactly one 200 and one event, no gaps in the cursor; the same payer's racing retry gets 200 (ADR-0021); store tests assert statement order and no HTTP inside a transaction |
| 7 | **Differential run + interop** | **Differential run against Java** (ADR-0028): the suite runs on both builds with the same inputs, normalised responses (JSON re-serialised, ids/times/signatures masked) are diffed on status, `ETag`/`Content-Type`/`Location` and body; every tolerated difference is listed in `others/blackbox/TOLERATED-DIFFERENCES.md` (1: whitespace; 2: expired → expiry reason instead of 404); `two-instance-payment-cycle.sh` in all four Java/Go pairings; k6 at today's profile | No untolerated difference; golden corpora pass; four pairings complete; P95 < 2 s at 500 VUs as in README |
| 8 | **Housekeeping** | Retention per ADR-0029: **one goroutine, one `time.Timer`, no schedule table**; purge events then QR Codes (with their audit and notification rows) in paced batches; start-up refusal if `qrcode.retention` < `events.retention`; keystore reload timer | Retention tests driven by the test clock |
| 9 | **Migration + config tools** | **`x9-migrate`** (ADR-0030): each document → one `qr_code` row, `revision` kept, `request_json` bill fields only, `location_id` and **`emv_content` copied verbatim** (refuse if `EMV(location, converted config)` differs), `valid_until` computed; **no audit rows**; last stored notification → one `qr_code_notification` row without JWS; `payment_events` copied in order with new `seq`, original `eventId`, ULID into `payment_event_legacy_cursor` (events of deleted codes included); `event_sequence.last_seq` set to the highest `seq`; **refuses to run if any Mongo document has a non-empty `outbox` or the target schema is not empty**; row counts, per-QR checksum, `--dry-run`. **`x9-config-convert`** (ADR-0031): Spring YAML + `application-secrets.yml` + Jasypt password + keystores → Go config file, `ENC(...)` decrypted into secret files, JKS → PKCS#12 | A restored copy of a real deployment converts and migrates, the suite's read-only checks pass against it, and a translated ULID cursor resumes without gaps |
| 10 | **Image & deploy** | Per ADR-0031: multi-stage `Dockerfile` built with `docker buildx` (amd64 + arm64), `Makefile` targets kept as thin wrappers over `go`/`docker`; distroless service image carrying `x9-qrcode` + the demo identity as PKCS#12 (no Mongo driver); a **separate migrate image tag** carrying `x9-migrate` + `x9-config-convert`; same ports, health paths and JSON shape, documented env var names, OCI labels; multi-arch; start-up refusal on Spring-style or MongoDB configuration, naming the converter; actuator exposes **only health, prometheus and info**; incoming `b3`/`traceparent` ids logged per request, no exporter; **metric inventory**: every metric Java exports is kept under its name or listed as dropped; upgrade notes (dropped actuator endpoints, log field mapping, configuration migration); compose with PostgreSQL + Liquibase `4.33.0` (Apache-2.0) one-shot; Helm with Liquibase init container, `strategy: Recreate`, **no HPA**, `GOMEMLIMIT`; tag the last Java build `java-final`; Go line tagged `3.x` | `docker run` with only the demo identity serves `/actuator/health` UP and the black-box suite passes; compose `up` works on a clean machine; `helm template` smoke check in CI |
| 10b | **Benchmark & `BENCHMARK.md`** | §9 campaign — **S1, S2, S7** (owner decision D6) — Java and Go interleaved on the same machine; `BENCHMARK.md` published with method, environment, results and raw data | Every number in `BENCHMARK.md` traces to a committed raw file; the Docker Hub "lighter" claim cites it |
| 11 | **Cutover** | Per deployment, per ADR-0030: (1) announce the window; (2) stop **inbound** traffic to Java but keep it running until its drain has emptied every outbox (`x9-migrate` checks); (3) stop Java; (4) Liquibase on the empty schema; (5) `x9-config-convert` + `x9-migrate`; (6) start Go; (7) consumers continue with their stored cursors, translated. **Rollback** (stop Go, start Java on the untouched MongoDB) is rehearsed once and is allowed **only until Go accepts its first write of any kind** (create, PATCH, notification, status-update); after that, forward-fix only — the runbook checks the event count since start. **Then** move `:latest` to Go, tag `3.0.0`, push DOCKERHUB.md stating the database and configuration change in its first paragraph and linking the upgrade guide | An existing consumer of the event stream continues from its stored cursor with no gaps and no duplicates beyond `eventId` replays |
| 12 | **Freeze Java** | `git mv` the Maven modules, `x9-qrcode-build-init` and the vendored jar into `legacy/java/` with a README (frozen, last tag `java-final`, how to build it if ever needed); drop them from CI; keep the Mongo compose file there; mark ADR-0002 and ADR-0013 *Superseded*; rewrite AGENTS.md, CLAUDE.md, README, RUNNING, HIGH-AVAILABILITY, ENDPOINTS for Go/PostgreSQL; delete this plan | Root builds only Go; `legacy/java/` untouched afterwards |

Phases 4 and 5 can run in parallel once phase 3 lands. Phase 8 can run any time after phase 6.
Phase 9's converter can start as soon as phase 3 fixes the Go config shape.

---

## 4. The rule-coverage table (phase 1's real output)

One row per behavioural rule, each pointing to the test that pins it. Without this table, "the
rewrite is equivalent" is an opinion. Seed rows:

| Rule | Source | Test |
|---|---|---|
| A network other than fednow/rtp/ach/solana is refused at create, by name | ADR-0012, 0010 | `blackbox/…` (to write) |
| Currency outside the allow list → 400 naming the correct spelling | ADR-0012 | |
| Mixed currencies must share one peg group | AGENTS | |
| A reservation lapses after `ttl` on read; reactivating a lapsed one is refused | ADR-0019 | |
| Only the announcing payer (payer.info + signer subject) may re-announce or release; others 409 | ADR-0021 | |
| `SENT` never marks PAID; status-update does | STATE-MACHINE, ADR-0020 | |
| `NOT_SENT` releases without a transaction id | ADR-0021 | |
| Revision moves only on PATCH; ETag is `"<revision>-<STATUS>"` with the **effective** status, and `If-Match` compares against the same value | ADR-0016, ADR-0028 | |
| A location is unique across all rows; a create or PATCH may take it from another QR Code only for the same creditor and a holder that is not live (stored `PAID`/`CANCELLED`, or past `valid_until`); the holder gets a fresh random location in the same transaction | ADR-0024 | |
| Every row of the transition map: trigger, status read (stored or effective), status after, event | ADR-0035, ADR-0029 | |
| A PATCH from revision n leaves audit row `(id, n)` holding only the changed tags with their previous values; unchanged tags absent; a refused or no-op PATCH leaves none *(verified with SQL through the read-only role; not observable over HTTP)* | ADR-0024 | |
| A PATCH names every currency or none | ADR-0018 | |
| `amount` includes the tip; tip is checked against the range as integer percent | ADR-0017 | |
| Notification matching is case-insensitive; `$.payment.network` echoed verbatim | INTERPRETATION I-1 | |
| Payer endpoints: refusal = 200 + `accepted:false`; unreachable payee = 502 | ADR-0015 | |
| `qrCodeContent` must byte-match the issued EMV | entity | |
| Discount and late-fee windows, both discount edges, and the due-date case | FormulaService, ADR-0033 | |
| Events: dedupe by `eventId`, per-QR order, `wait` ≤ 30 clamps, `hasMore`; a ULID `after=` is translated | ENDPOINTS, ADR-0026 | |
| JWS: `crit`, `iat + ttl`, revoked certificate refused (CRL, OCSP fallback), unreachable revocation soft-fails | README, ADR-0027 | |

---

### 4.1 The scenario catalogue (outside the service, against any URL)

The suite is **Python in `others/blackbox/`: pytest plus dedicated helpers, under this repository's
licence** ([ADR-0034](docs/adr/0034-the-black-box-suite-is-python-in-this-repository-under-its-licence.md)),
driven by `X9_BASE_URL` (and optionally `X9_DATABASE_URL`, through the read-only role, for
below-HTTP checks). It creates, queries, patches, notifies and reads events exactly as an
integrator would. Every scenario asserts **the status code, the reason, and what did *not* happen**:
no status change, no event, no reservation taken. The Go build adds one extra check where it can,
the audit-log row, read with SQL. Run against both builds, the same scenarios feed the differential
run (ADR-0028).

| Group | Scenarios (each a family of cases) |
|---|---|
| **A. Create: refused** | every field over its `maxLength`, under its `minLength`, or breaking its `pattern`; missing required fields; unsupported network named in the 400; unsupported currency and wrong-case currency (`usd`); currencies from two peg groups; duplicate currency; `validUntil` in the past or exactly now; `dueDate` after `validUntil`; a method's `validUntil` after the QR Code's; tip `allowed: false` with a range, `true` without one; negative amount; amount with 19 digits (records today's behaviour, Q7b); host too long for EMV tag 26 |
| **B. PATCH: refused or limited** | fields that are not patchable (creditor, status, id: rejected or ignored, never applied); adding or dropping a currency (ADR-0018); a no-op patch; patching while `PAYMENT_INITIATED`, `PAID` or `CANCELLED`; a valid patch moves `revision`, and a status move doesn't (ADR-0016) |
| **B2. ETag / `If-Match`** (ADR-0028) | GET returns `"<revision>-<STATUS>"`; `If-Match` accepted with and without `W/` and quotes; stale revision → 412; stale status → 412; **after a lapsed reservation**, GET shows `ACTIVE` in the ETag and an `If-Match` carrying that ETag is **accepted** (the fixed bug; 412 on unfixed Java) |
| **B3. Location rebind** (ADR-0024) | create or PATCH onto a location held by the **same creditor** whose holder is `PAID`, `CANCELLED`, or `ACTIVE` but past `validUntil` → accepted, the holder now answers on a fresh random location and `/loc/{old}` resolves only the new code; same creditor but holder live (`ACTIVE` in time, `PAYMENT_INITIATED`) → refused; **different creditor** → refused whatever the holder's state; no event emitted by the release |
| **C. Payload retrieval** | unknown location; `qrCodeContent` not byte-equal to the issued EMV; JWS with a bad signature, an expired `iat + ttl`, a missing or unknown `crit`, a revoked certificate (CRL and OCSP), an untrusted CA, an unreachable revocation source (soft-fail); an expired QR Code; an expired payment method dropped from the payload while the others stay; `PAID` and `CANCELLED` codes |
| **D. Time edges** (ADR-0033, serial stage) | see §4.2 |
| **E. Notifications** | per rail (FedNow/RTP courtesy, ACH requires payer and `expectedDate`, Solana `PAYMENT_INITIATED`/`SENT`/`NOT_SENT`); wrong amount; tip outside its range; tip on top of the bill (ADR-0017); unoffered currency; destination not on the QR Code; rail and currency in a different case (accepted, network echoed verbatim); a second payer → 409; the holder re-announces (refreshes, no event); `NOT_SENT` releases with no transaction id; `SENT` never marks `PAID`; QR Code with no `paymentNotification` or with `kind: EXTERNAL`; 32 concurrent announcements → exactly one 200 and one event |
| **F. Status update** | the full transition matrix, including `PAID` after expiry and after a lapsed reservation (ADR-0020), `CANCELLED` final, `PAYMENT_INITIATED` through status-update, so no payer can take it over; stale `If-Match` |
| **G. Events** | every transition emits exactly its event; per-QR order; no gaps across pages; `wait` above 30 clamps; `hasMore`; replay from an old cursor is safe by `eventId` |
| **H. Payer-side endpoints** | the payee refuses → 200 + `accepted:false`; the payee is unreachable → 502; a pre-payment carrying a `transactionId` is refused before sending (ADR-0015) |
| **I. Transition map** (ADR-0035) | one test per row: create → `ACTIVE`, no event; PATCH from stored `ACTIVE` → audit row, no event; status-update `PAYMENT_INITIATED` from effective `ACTIVE` → `payment.initiated`; `PAID` from stored `ACTIVE`/`PAYMENT_INITIATED` → `payment.cleared`; `CANCELLED` → `payment.cancelled`; reactivate from effective `PAYMENT_INITIATED` → `ACTIVE`, **no event**; FedNow/RTP notification → unchanged, no event; ACH → `PAYMENT_INITIATED` + `payment.initiated` if changed; Solana `PAYMENT_INITIATED` new holder → `payment.initiated`, same holder → refreshed, **no event**; `SENT` (holder, also after lapse) → unchanged + `payment.sent`; `NOT_SENT` → `ACTIVE` + `payment.failed`; location release by rebind → no event; reservation lapse → reads `ACTIVE`, **no event**. Event bodies come from the last stored notification, else from the first payment method |

### 4.2 Time edges: the scenarios that need a movable clock

These run in their own serial stage. Each test moves the clock with `x9client.clock.move_to()`
(`PUT /test/clock`, ADR-0033) and resets it at the end. **Expected results marked ❓ are to be
observed on Java in phase 1 and then confirmed by the owner.** Where the result is a bug, it is
fixed in both builds, not copied.

Notation for the discount: due date **D** (an instant), discount with `daysBefore` = N.
**E** = the eligibility instant `D − N days` (`FormulaService:59`). **V** = the advertised
`validUntil` of the discounted amount, `23:59:59` UTC of E's day (`FormulaService:91`). E and V can
be up to ~21 h apart.

| # | Scenario | Expected |
|---|---|---|
| T1 | **The eligibility edge E, pre-payment.** Clock at E − 10 s: fetch the payload → discounted amount, `validUntil` = V. Clock moves to E + 10 s (still before V). (a) Fetch again; (b) the payer announces a payment (no `transactionId`) of the **discounted** amount | **Record both outcomes** ❓: does the payload still offer the discount the previous response advertised until V, and is the announcement accepted or refused (with which reason)? The owner then decides which edge is intended (§1.4) |
| T2 | **Post-payment across the edge.** Same as T1, but the payer reports `SENT` with a `transactionId` after the edge (E, and separately V) | **Accepted and forwarded**: money has moved, and the difference is the consumer's to reconcile (ADR-0014). The event carries the amount actually reported ❓ |
| T3 | **The advertised edge V.** Clock at V − 0.5 s (`23:59:58.500`), then V + 0.5 s (`23:59:59.500`), then just past midnight: fetch the payload and announce the discounted amount at each point | **Record each outcome** ❓: discount offered or not, announcement accepted or refused. The window as advertised ends at `23:59:59`, not at midnight, and eligibility may already have ended at E — very likely a bug to fix in both builds |
| T4 | **Exactly on the due date** | Today Java throws `IllegalCallerException` (§1.4, Q1). Expected result decided by the owner, then pinned |
| T5 | **Late fee, first and second day.** Clock at D+1 `00:00:01`, then D+2 | fee = days late × per-day + fixed, `validUntil` `23:59:59` of that day |
| T6 | **`validUntil` crossed between fetch and notify.** Fetch at `validUntil − 5s`, announce at `validUntil + 5s` | pre-payment refused; the payee may still mark it `PAID` (ADR-0020) |
| T7 | **A payment method expires before the QR Code.** Clock between the method's `validUntil` and the QR Code's | that method is absent from the payload; a notification on it is refused; other methods are served |
| T8 | **Reservation lapse.** Announce; clock +91 s; a second payer announces | second payer accepted; the first payer's late `SENT` is still accepted (ADR-0019, 0021); the ETag reads `ACTIVE` and `If-Match` with it succeeds (ADR-0028) |
| T9 | **Reservation capped by `validUntil`.** Announce at `validUntil − 30s` | the reservation ends at `validUntil`, not after 90 s |
| T10 | **JWS freshness.** Payer `iat` 10 min in the service's past, with a 5-min `ttl` | refused before any lookup |
| T11 | **Expired QR Code is not gone.** | **Go:** clock 1 h past `validUntil` → the payload endpoint answers with the expiry reason; GET still returns the code; its location is releasable (ADR-0029). **Java:** MongoDB's TTL monitor runs on the **real** clock, so a shifted clock never deletes the document; the expired → 404 assertion is made by **waiting real time** (`validUntil` a few seconds ahead, then ~60 s for the monitor) or marked `java_only` — **never driven by the test clock**. Tolerated difference 2 (ADR-0028) |
| T12 | **Retention** (Go only). Clock past event retention and past QR Code retention; wait for the retention timer's next run (a short interval in the test deployment) | events and QR Codes (with their audit and notification rows) purged in that order; nothing younger touched; cutoffs computed from the test clock (ADR-0029) |

## 5. Configuration (ADR-0031)

3.0.0 is a **documented configuration migration with a converter**, not a silent drop-in.

- **The Go build reads one YAML file of Go keys, plus the same documented environment variables.**
  Every documented variable keeps its name and meaning (`X9_PUBLICENDPOINTS_HOST`,
  `X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION`, …).
- **`x9-config-convert`** (migrate image) reads an operator's Spring YAML (the ConfigMap selected by
  `SPRING_CONFIG_LOCATION`), `secrets/application-secrets.yml`, the **Jasypt password** and the
  keystores. It writes the Go config file, decrypts every `ENC(...)` value into a plain secret
  file, and converts **JKS → PKCS#12**. Keys it cannot place are reported, never guessed.
- **Old configuration is refused.** Given Spring-style configuration or MongoDB variables, the
  binary refuses to start and names the converter. There is no compatibility loader.
- **Secrets** come from mounted files or the environment. The Spring Cloud Kubernetes secrets API
  and Jasypt are gone.
- **Database:** `DATABASE_*` variables replace `SPRING_DATA_MONGODB_*`: URL/host, schema name
  (default `x9_qrcode`), credentials file, `sslmode` and root CA (`verify-full` recommended), pool
  sizes (writes 8, reads 4), and a **PgBouncer transaction-mode flag that switches off pgx's
  prepared-statement cache** (ADR-0032).
- **Removed:** `x9.events.drain.*` (no drain, ADR-0025), `jasypt.*`, `spring.data.mongodb.*`,
  HSM/`provider` keys (refused with a clear message until a PKCS#11 signer exists, B2).
- **New:** `events.retention` (default `P30D`), `qrcode.retention` (default `P400D`, Q2), the
  keystore reload interval, and `X9_TESTING_CLOCKCONTROL` (test deployments only, ADR-0033).
- The upgrade guide lists the field mapping of the log format and the actuator endpoints that are
  no longer exposed.

---

## 6. Consumer impact (to announce before cutover)

- **Event cursors are translated, not reset.** A stored ULID cursor keeps working: it is resolved
  to the matching migrated event (or the next one after it). New cursors are decimal strings, still
  opaque. Keep deduplicating by `eventId` (ADR-0026).
- **`eventId`s minted after cutover are UUIDv7.** No action needed.
- **Expired QR Codes return their expiry reason instead of 404** once they are past `validUntil` and
  not yet purged (ADR-0028, ADR-0029).
- **Operators** of a self-hosted image run the 3.0.0 configuration migration (`x9-config-convert`)
  and the data migration (`x9-migrate`) once; `java-final` stays available (ADR-0031).
- **Nothing else.** Paths, payloads, status codes and signatures are compatible, and the
  differential run is the evidence (ADR-0028).

---

## 7. Effort (order of magnitude, one experienced Go engineer)

| Block | Estimate |
|---|---|
| Phase 0–1 (docs truth, Java test clock + ETag fix, black-box suite, transition-map check, golden corpora) | 2.5–3.5 weeks |
| Phase 2–3 (schema, roles, skeleton, CI) | 1 week |
| Phase 4 (EMV, JWS, PKIX/CRL/OCSP, keys) | 2 weeks |
| Phase 5–6 (domain, endpoints, events; no idempotency) | 3–3.5 weeks |
| Phase 7 (differential harness, tolerated-differences list, interop) | 0.5–1 week |
| Phase 8–10 (retention timer, `x9-migrate`, `x9-config-convert` ~1 week, image & deploy) | 2.5–3 weeks |
| Phase 1b + 10b (benchmark harness, baseline, S1/S2/S7 campaign, `BENCHMARK.md`) | 0.5–1 week |
| Phase 11–12 (cutover, freeze Java) | 1 week |
| **Total** | **~13–16 weeks**, against ~6–8k LOC of Go expected |

Phase 1 is the most underestimated line. It is also the one that pays for itself even if the
rewrite is cancelled.

---

## 8. Open questions — blockers first

| # | Question | Blocks |
|---|---|---|
| ~~B1~~ | ~~Licence~~ — **resolved**: Matera owns the work; a rewrite by the owner is a new version, not a third party's Derivative Work (ADR-0022) | — |
| **B2** | **Does any deployment use a PKCS#11 HSM or a JKS keystore that `x9-config-convert` cannot convert?** If yes, the PKCS#11 signer moves into phase 4, and the cgo question reopens; until then such deployments stay on `java-final` | Phase 4 |
| ~~D1~~ | ~~Location rule~~ — **resolved** (ADR-0024): unique across all rows; rebind only for the same creditor and a holder that is not live (stored `PAID`/`CANCELLED` or past `valid_until`); holder gets a fresh location in the same transaction | — |
| ~~D2~~ | ~~How equivalence is proved~~ — **resolved** (ADR-0028): a differential run Java vs Go with normalised diffs and `TOLERATED-DIFFERENCES.md`; the ETag bug fixed in both | — |
| ~~D3~~ | ~~Configuration compatibility and image name~~ — **resolved** (ADR-0031): same `materainc/x9-qrcode`, Go line `3.x`, `java-final` kept; Go YAML + same env vars; `x9-config-convert` in the migrate image; old configuration refused naming the converter | — |
| D4 | **Matera's internal shared Go packages are proprietary; this repo is public.** Relicense the ones X9.150 would use under the Matera Source License (published as a module or copied in), or keep reimplementing them here? **Recommendation:** relicense the JWS helpers and the HTTP-error / health helpers, which are the bulk of the reuse; reimplement the rest (ADR-0022) | Phase 3 |
| ~~D5~~ | ~~Public scrub of internal references~~ — **done**: this plan and ADRs 0022–0035 name no internal document, rule or package; the internal standards show only through code and CI configuration (ADR-0022) | — |
| ~~D6~~ | ~~Benchmark scope~~ — **resolved by the owner**: S1, S2 and S7 only; S3–S6 deferred (§9) | — |
| ~~Q1~~ | ~~The due-date formula case and the discount edge~~ — **resolved by the owner** (2026-11-13): a payment up to and including `dueDate` is on time (full amount, no fee); the payee's clock at receipt decides; and the payee honours what it signed — the advertised `validUntil` of an adjusted amount is the exact instant it stops applying, so E and V coincide (Stories 1.3 and 2.6) | — |
| Q2 | `qrcode.retention` default: is `P400D` right for adopters' reconciliation? | Phase 8 |
| Q3 | Should `qr_code_notification` keep **refused** notifications too (with reason), for dispute evidence? Today they leave no trace | Phase 6 |
| Q4 | Do ADRs 0003 / 0006 / 0007 / 0011 stay *Accepted-but-unbuilt* or move to *Deferred*? | Phase 12 |
| Q5 | Should change data capture on `payment_event` ever replace the pull API for push-minded consumers? ADR-0025 keeps both open | after cutover |
| Q7 | Who runs the repository's compose against an existing Mongo volume? If anyone outside Matera does, the compose file must refuse to start PostgreSQL next to a populated Mongo volume until `x9-migrate` has run | Phase 10 |
| Q8 | Which dedicated host runs the benchmark campaign? It must be reservable exclusively for the whole campaign (§9.1) | Phase 1b |
| Q9 | Shared-database test: run the black-box suite with X9.150 in a schema of a PostgreSQL that is also running an unrelated load, using the application role. Is a pgbench background load enough, or should another real service share it? | Phase 10 |
| Q10 | Do auditors need **status** history beyond the 30-day event retention? If yes, add a separate `qr_code_status_audit_log` (one row per transition, same retention as the QR Code). Status moves don't bump `revision` (ADR-0016), so they cannot share `qr_code_audit_log`'s key | Phase 5 |

**Fallback trigger:** if phase 4 (crypto) is not green against the golden corpus within its
estimate plus 50%, stop and re-plan as **Java + PostgreSQL via JdbcTemplate**. ADRs 0023–0026 and
0029–0030 hold unchanged for that path. Only 0022 and 0027 would be rewritten.

---

## 9. Benchmark: Java + MongoDB vs Go + PostgreSQL → `BENCHMARK.md`

**Goal.** Publish a reproducible comparison of the two builds under **the same demand on the same
machine**, so the "lighter" claim on Docker Hub (ADR-0031) is backed by numbers, not adjectives. No
public MongoDB vs PostgreSQL benchmark matches this workload. Ours is the only one that answers the
question.

**What it is not.** It is not a database benchmark. Each payment is a handful of keyed rows, and
signing and verification dominate. The comparison is between **two complete builds of one
product**, as an operator would run them, and `BENCHMARK.md` says so in its first paragraph.

### 9.1 Environment

**Host: one dedicated machine running Kubernetes, reached remotely.** It is fine for this,
under four conditions:

1. **The remote link is the control path only, never the data path.** k6 runs **inside the cluster** as a
   Job, so no request crosses the internet. kubectl, result collection and dashboards use the remote link.
2. **One system under test at a time.** Java and Go are never deployed together during a
   measurement. Each run starts from an empty database volume.
3. **Exclusive windows.** Nothing else runs on the host or in its VM during a campaign.
   The VM's CPU and memory allocation is recorded and kept fixed for the whole campaign.
4. **Disclosed as-is.** The hardware, architecture and virtualisation layers are recorded in `BENCHMARK.md`. Both builds pay
   the same virtualisation tax, so the comparison stays fair even though the absolute numbers are
   lower than on bare metal.

if neither can. Whichever host is used, the full campaign runs on that one host, and the two builds
are never compared across machines.

### 9.2 Fairness rules

| Dimension | Rule |
|---|---|
| Resources | identical Kubernetes limits: app **2 vCPU / 2 GiB**; database **2 vCPU / 2 GiB**; k6 Job its own **2 vCPU** so the load generator never starves the system under test |
| Database | MongoDB `7.0.45` single-node replica set, as shipped; PostgreSQL `16`, defaults except `max_connections` sized to the two pools |
| Durability parity | both acknowledge only **journaled/fsynced** commits: Mongo `w:majority` (which journals on a single node; verify `writeConcernMajorityJournalDefault`) vs PostgreSQL `synchronous_commit=on`. A faster result bought with weaker durability is not a result |
| Storage | same storage class and volume size for both databases |
| Crypto | same keystore (PKCS#12 for Go, converted from the same key), same algorithm (PS512 as shipped), same `endpoint-type` (`JWK_SET`), same test-CA truststore |
| Runtime tuning | each build as shipped: Java with virtual threads and the container-aware JVM, **plus a 2-minute warm-up excluded from measurement** (JIT); Go with `GOMEMLIMIT` at 80% of its limit |
| Versions | each image identified by its `git-<sha>` tag and OCI revision label, recorded in the results; k6 **v2.4.0** |
| Repetition | **3 runs per build, interleaved J-G-J-G-J-G**; the median is reported, min/max shown |

### 9.3 Scenarios

Run (owner decision D6):

| # | Scenario | What it measures |
|---|---|---|
| S1 | **Create only**, today's README profile (ramp to 500 VUs, 3 min sustain) | continuity with the existing k6 script |
| S2 | **Full payment cycle** per virtual user: create → payload fetch (signed request) → pre-payment notification → post-payment notification (Solana, with `transactionId`) → status-update `PAID` | the realistic per-payment cost, end to end |
| S7 | **Footprint**: image size, cold start to `/actuator/health` UP, resting memory after 5 idle min | what "lighter" means to an operator |

Deferred — run only if a question needs them:

| # | Scenario | What it would measure |
|---|---|---|
| S3 | S2 **plus one events consumer** long-polling `GET /pub/api/v1/events?wait=25`, asserting no gaps and no duplicates beyond replays | event-path latency (commit → visible) and correctness under load |
| S4 | **Contention**: 32 concurrent notifications on the same QR Code, repeated | exactly-one-wins in both builds; conflict latency |
| S5 | **Soak**: S2 at 50% of each build's saturation for 30 min | memory growth, GC or allocator behaviour, latency drift |
| S6 | **Saturation**: S2 ramping until P95 > 2 s or errors > 1% | maximum sustainable payments per second per build |

**Payer signatures inside k6.** Notifications and payload requests must carry a payer JWS with a
fresh `iat`. k6 v2.4.0 signs them itself with **WebCrypto through the global `crypto.subtle`**
(ES256), carrying the certificate **inline in `x5c`** from the test CA, so the system under test
never fetches a payer certificate and the network does not distort the result. The payer-sidecar
fallback is no longer needed.

### 9.4 What is recorded

- **Per endpoint:** p50, p95, p99, throughput, error rate (k6 JSON summary + raw NDJSON).
- **Per pod, every 5 s:** CPU and memory for the app and the database (metrics-server / cAdvisor via
  Prometheus).
- **Database, as a DBA would ask (ADR-0032):**
  - on-disk size per 1,000 payments after S2 (rows/documents created are equal by construction);
  - peak and steady connections held;
  - lock waits;
  - the database's own CPU and memory.
- **Total footprint of a small-bank install:**
  - *Java*: app + MongoDB.
  - *Go*: app alone, plus the marginal load it adds to an already-running PostgreSQL.

  This is the comparison that matters to the owner's driver. It is reported separately from the
  like-for-like table.
- **Build (S7):** image size (compressed and uncompressed), startup time, resting memory.

### 9.5 Layout and publication

```
others/benchmark/
├── README.md               how to reproduce, end to end
├── k6/                     s1-create.js, s2-cycle.js, lib/jws.js (WebCrypto signer, crypto.subtle)
├── footprint.sh            S7: image size, cold start, resting memory
├── k8s/                    java+mongo and go+postgres manifests with the §9.2 limits; k6 Job
├── collect.sh              runs a campaign, pulls k6 + Prometheus data, tags with image SHAs
└── results/<date>-<host>/  raw k6 JSON/NDJSON, metrics CSV, environment.txt (VM size, versions)
BENCHMARK.md                method, environment, tables and charts, caveats; links to results/
```

`BENCHMARK.md` structure:

1. What was compared, and what it is not.
2. Environment.
3. Fairness rules.
4. Results per scenario, with Java and Go side by side and the ratio.
5. Footprint.
6. Caveats (virtualised host, arm64, single node, deferred scenarios not run).
7. How to reproduce.

**Published as measured, whichever way it falls.** If Java wins a scenario, the table says so.

### 9.6 Timing

- **Phase 1b:** run S1, S2 and S7 against Java while it is still the live build. This is the
  baseline, and it also catches harness bugs before the Go build exists.
- **Phase 10b:** run S1, S2 and S7 on both builds, interleaved, on the same host as the baseline.
  The baseline is re-run in the same campaign; old and new numbers are never mixed.
- **Afterwards:** `BENCHMARK.md` is re-run, not edited, whenever a release could move a number. Each
  re-run adds a results directory, so the history stays visible.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
