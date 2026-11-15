# Rule coverage

Every documented rule, and the tests that prove it. A rule with no test is listed under
[Gaps](#gaps), with the reason, so it is visible and not assumed covered.

This is the companion of [TEST-INDEX.md](TEST-INDEX.md): that file goes from a test to what it
proves, this one goes from a rule to its tests.

## How to read it

Each row is one rule.

- **Rule** is a stable key. Cite it in a pull request, an issue or an ADR; it is not renumbered.
- **What it says** is the rule in one plain sentence. The source document has the full text and the
  reason.
- **Tests** names at least one test: by its key (`X9-HOLD-104`, see
  [TEST-CONVENTIONS.md](TEST-CONVENTIONS.md)), or, for a test that has no key yet, by
  `File::function`. `GAP` means no test proves the rule today. `NOT BUILT (ADR-0006)` means the
  ADR decided it and nothing was ever built, so there is nothing to observe; the ADR is named so
  the row says where the decision lives.
  [TEST-CONVENTIONS.md](TEST-CONVENTIONS.md)); every test has one. `GAP` means no test proves the
  rule today.
- **Proves** lists the acceptance criteria (`S4.2-AC1`, defined in
  [EPICS-AND-STORIES.md](rewrite/EPICS-AND-STORIES.md)) that those tests cite. `—` means the tests
  were written before criteria had IDs and cite none.

The keys, by source:

| Source | Key |
|---|---|
| [STATE-MACHINE.md](../STATE-MACHINE.md) | `R-SM-NN` |
| [ENDPOINTS.md](../ENDPOINTS.md) | `R-EP-NN` |
| Entry I-n of [INTERPRETATION.md](../official-spec/INTERPRETATION.md) | `R-I<n>-NN` (`R-I10-03` is in I-10) |
| An ADR in [docs/adr](adr/README.md) | `R-ADR<nnnn>-NN` (`R-ADR0012-01` is in ADR-0012) |
| A row of ADR-0035's transition map | `R-MAP-NNN`, the number of its test (`R-MAP-010` is `X9-MAP-010`) |

Find a rule with `grep -n 'R-ADR0020' docs/RULE-COVERAGE.md`, and the rules a test proves with
`grep -n 'X9-HOLD-104' docs/RULE-COVERAGE.md`.

**What is a row.** A statement about what this service does, that a test can observe. These are
not rows: the reason for a rule, its history, a description of how a rail works outside this
service (what a pacs.008 carries), and an instruction to the operator or to the system that reads
the events (one poller per deployment, persist the cursor after storing the events, ignore fields
you do not know). A rule that a later ADR replaced is written as it stands today, under the ADR
that replaced it.

**The same rule appears under each document that states it**, with its own key, because each
document is checked on its own. The matrix of ADR-0036 is in STATE-MACHINE.md and, row by row, in
ADR-0035's map; the cell-by-cell tests are under `R-MAP-NNN`.

**A strict expected failure is still a test.** One test fails today on purpose, and turns red the
day Java changes: `X9-LOC-421` (BUG-004). It pins ADR-0024, which is outside this table.

## The check

`python3 docs/check/check_rule_coverage.py` runs on every pull request
(`.github/workflows/rule-coverage.yml`). It fails when:

- a test cites a criterion ID that EPICS-AND-STORIES.md does not define;
- a rule row has no test, unless it says `GAP` and is listed under [Gaps](#gaps) with a reason,
  or says `NOT BUILT (ADR-nnnn)` naming an ADR that exists (and is then not listed as a gap);
- a rule row names a test that does not exist;
- a **Proves** cell names a criterion that is not defined, or that none of the row's test files
  cites;
- a source has no row: STATE-MACHINE, ENDPOINTS, an entry of INTERPRETATION, an ADR from 0004 to
  0021, ADR-0036, or a key of ADR-0035's map;
- a rule under [Gaps](#gaps) now has a test (remove it from the list);
- a black-box case or a JUnit test carries no key.

**When you add a rule to one of these documents,** add its row here with the next free number and
name its test. **When you write the test for a gap,** put the test in the row and delete the gap.

## Gaps

Rules with no test today. The check reads this table: a row marked `GAP` must be here, and a rule
that is here must still be marked `GAP`.

| Rule | Why there is no test | Recommendation |
|---|---|---|

## STATE-MACHINE.md

| Rule | What it says | Tests | Proves |
|---|---|---|---|
| `R-SM-01` | A new QR Code is `ACTIVE`. | `X9-MAP-001`, `X9-LIFE-010` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-02` | `PAID` and `CANCELLED` are terminal: no status-update, pre-commit, `NOT_SENT` or PATCH changes them. | `X9-LIFE-172`, `X9-MAP-025`, `X9-MAP-002`, `X9-LIFE-117`, `X9-LIFE-124` | `S4.1-AC1`, `S4.1-AC3`, `S4.4-AC1` |
| `R-SM-03` | A reservation past its window (`x9.reservation.ttl-seconds`, 90 by default) reads `ACTIVE` again. | `X9-LIFE-111`, `X9-MAP-051`, `X9-HOLD-315`, `X9-HOLD-324` | `S2.5-AC7`, `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-04` | `ttl-seconds: 0` means no window: a reservation ends only by a refused retry, `NOT_SENT`, or the status-update API. | `X9-HOLD-135`, `X9-HOLD-136`, `X9-HOLD-137`, `X9-HOLD-321`, `X9-HOLD-322` | `S2.5-AC2`, `S2.5-AC7` |
| `R-SM-05` | A negative `ttl-seconds` is invalid: the service refuses to start. | `X9-HOLD-323` | — |
| `R-SM-06` | Nothing sweeps: a lapse is applied on read, writes nothing and emits no event; the stored status stays. | `X9-LIFE-112`, `X9-MAP-051` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-07` | Expiry is not a status: past `validUntil` a QR Code is still `ACTIVE`, payers are refused, and the payee may still mark it `PAID`. | `X9-LIFE-170`, `X9-TIME-006`, `X9-LIFE-122`, `X9-TIME-012` | `S1.3-AC3`, `S4.4-AC1`, `S4.5-AC1` |
| `R-SM-08` | A post-commit is never refused for status reasons. | `X9-MAP-031`, `X9-MAP-033`, `X9-MAP-034`, `X9-HOLD-215` | `S2.4-AC7`, `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-09` | A registered hash stops the window, whoever sent it; the holder does not change, and only the status-update API ends the reservation. | `X9-HOLD-312`, `X9-HOLD-313`, `X9-HOLD-314`, `X9-HOLD-307`, `X9-HOLD-129` | `S2.5-AC6` |
| `R-SM-10` | `PAID` is reached from `ACTIVE` or `PAYMENT_INITIATED`. | `X9-MAP-011`, `X9-LIFE-120`, `X9-LIFE-121` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-11` | `CANCELLED` is reached from `ACTIVE` or `PAYMENT_INITIATED`. | `X9-MAP-012` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-12` | Reactivation (`ACTIVE`) is allowed from `PAYMENT_INITIATED` only. | `X9-MAP-013`, `X9-LIFE-055` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-13` | A status-update `PAYMENT_INITIATED` on an `ACTIVE` QR Code (effective status) takes an unattributed reservation. | `X9-MAP-010`, `X9-LIFE-050`, `X9-LIFE-173`, `X9-LIFE-142` | `S4.1-AC1`, `S4.1-AC3`, `S4.4-AC1` |
| `R-SM-14` | A status-update `PAYMENT_INITIATED` may not carry settlement details. | `X9-LIFE-025`, `X9-LIFE-056` | — |
| `R-SM-15` | Only the payment system marks a QR Code `PAID`, through the status-update API; no payer message does. | `X9-HOLD-402`, `X9-HOLD-214`, `X9-RAIL-101`, `X9-LIFE-503` | `S2.4-AC4`, `S2.4-AC6`, `S2.4-AC7`, `S3.4-AC1`, `S4.3-AC2` |
| `R-SM-16` | A notification must name a rail the QR Code offers. | `X9-RAIL-030`, `X9-RAIL-102` | — |
| `R-SM-17` | `blockchain` data is refused on a bank rail. | `X9-RAIL-002` | — |
| `R-SM-18` | An ACH notification must carry `payer.info` and `expectedDate`. | `X9-HOLD-407` | — |
| `R-SM-19` | A pre-commit has no `transactionId`; a post-commit has one. This holds on every rail. | `X9-HOLD-101`, `X9-HOLD-102`, `X9-HOLD-201` | `S2.4-AC1`, `S2.4-AC2`, `S2.4-AC5` |
| `R-SM-20` | `blockchain.action` only corroborates the phase; it decides `NOT_SENT` alone. | `X9-HOLD-103`, `X9-MAP-040`, `X9-MAP-044` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-21` | A pre-commit is judged by the payee's clock at receipt, and an accepted one reserves the QR Code. | `X9-HOLD-201`, `X9-MAP-020`, `X9-TIME-016` | `S1.3-AC2`, `S2.4-AC1`, `S2.4-AC2`, `S2.5-AC5`, `S4.1-AC1`, `S4.1-AC3`, `S4.5-AC2` |
| `R-SM-22` | A post-commit is recorded and never judged again on amount or time. | `X9-TIME-002`, `X9-SIG-125`, `X9-HOLD-206` | `S2.3-AC3`, `S2.4-AC3`, `S2.4-AC4`, `S4.5-AC1` |
| `R-SM-23` | Matrix, pre-commit: reserves an `ACTIVE` QR Code; the holder's is a retry; 409 when someone else holds it or a hash is registered; 400 on `PAID` or `CANCELLED`. | `X9-MAP-020`, `X9-MAP-021`, `X9-MAP-022`, `X9-MAP-023`, `X9-MAP-024`, `X9-MAP-025` | `S2.5-AC2`, `S2.5-AC5`, `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-24` | Matrix, post-commit: accepted in every status; reserves an `ACTIVE` QR Code; flagged `RECEIVED_IN_WRONG_STATUS` unless the holder sent it. | `X9-MAP-030`, `X9-MAP-031`, `X9-MAP-032`, `X9-MAP-033`, `X9-MAP-034` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-25` | Matrix, `PAYMENT_INITIATED` with a hash: a post-commit, also flagged `ACTION_CONTRADICTS_HASH`. | `X9-MAP-040` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-26` | Matrix, `SENT` without a hash: 400 in every status. | `X9-MAP-041` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-27` | Matrix, `NOT_SENT`: the holder's releases the QR Code with `payment.failed`; 409 from anybody else; 400 on `ACTIVE`, `PAID`, `CANCELLED`; after a hash it changes nothing. | `X9-MAP-042`, `X9-MAP-043`, `X9-MAP-045` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-28` | Matrix, `NOT_SENT` with a `transactionId`: a post-commit, flagged `ACTION_CONTRADICTS_HASH`. | `X9-MAP-044` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-29` | Matrix, status-update `PAYMENT_INITIATED`: reserves an `ACTIVE` QR Code with `payment.initiated`; 409 on `PAID` or `CANCELLED`. | `X9-MAP-010` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-30` | Matrix, status-update `PAID`: `payment.cleared` from `ACTIVE` or a held QR Code; 400 on `CANCELLED`. | `X9-MAP-011` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-31` | Matrix, status-update `CANCELLED`: `payment.cancelled` from `ACTIVE` or a held QR Code; 400 on `PAID`. | `X9-MAP-012` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-32` | Matrix, status-update `ACTIVE`: releases a held QR Code with no event; 400 on `PAID`, `CANCELLED` and a lapsed reservation. | `X9-MAP-013` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-33` | A status-update to the status the QR Code already has (stored and effective) is a retry: 200, nothing written, no revision, no event. | `X9-LIFE-127`, `X9-LIFE-125`, `X9-LIFE-143`, `X9-MAP-011` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-34` | A retry that carries `If-Match` must carry the current tag. | `X9-LIFE-176` | `S4.4-AC1` |
| `R-SM-35` | Once a hash is registered, no payer message changes the QR Code's status. | `X9-MAP-022`, `X9-MAP-043`, `X9-HOLD-128`, `X9-HOLD-138` | `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-36` | A notification that keeps losing a concurrent write answers 409 "busy" with the current status, never 500. | `X9-HOLD-221` | — |
| `R-SM-37` | Every event a notification produces carries `receivedInStatus`. | `X9-HOLD-207`, `X9-MAP-033`, `X9-HOLD-215` | `S2.4-AC4`, `S2.4-AC7`, `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-38` | `deviations` lists `RECEIVED_IN_WRONG_STATUS` and `ACTION_CONTRADICTS_HASH`, and is absent when the payer followed the rules. | `X9-HOLD-113`, `X9-HOLD-118`, `X9-HOLD-119`, `X9-HOLD-120` | `S2.4-AC5` |
| `R-SM-39` | Every hash survives in the event log as its own `payment.sent`. | `X9-HOLD-117`, `X9-HOLD-214` | `S2.4-AC6`, `S2.4-AC7` |
| `R-SM-40` | Past `validUntil`, a pre-commit is refused by the payee's clock (400). | `X9-TIME-006`, `X9-SIG-521` | `S1.3-AC3`, `S3.2-AC7`, `S4.5-AC1` |
| `R-SM-41` | Past `validUntil`, a post-commit is still accepted and registered. | `X9-TIME-018` | — |
| `R-SM-42` | Two pre-commits that arrive together end with one winner and one 409. | `X9-HOLD-203`, `X9-HOLD-406` | `S2.4-AC2`, `S4.3-AC2`, `S4.3-AC3` |
| `R-SM-43` | The same payer means `payer.info` present on both and equal, and the same certificate subject; without `payer.info` the sender is somebody else. | `X9-HOLD-001`, `X9-HOLD-002`, `X9-HOLD-003`, `X9-HOLD-004`, `X9-HOLD-305`, `X9-HOLD-306` | `S2.5-AC4` |
| `R-SM-44` | `NOT_SENT` is keyed on the action, and is the only message by which a payer gives a reserved QR Code back. | `X9-HOLD-103`, `X9-HOLD-020`, `X9-HOLD-021`, `X9-HOLD-213` | `S2.4-AC5`, `S2.4-AC6` |
| `R-SM-45` | `payment.sent` and `payment.failed` are published on chains only. | `X9-HOLD-112`, `X9-HOLD-131`, `X9-HOLD-205`, `X9-MAP-030` | `S2.4-AC2`, `S2.4-AC3`, `S2.5-AC2`, `S4.1-AC1`, `S4.1-AC3` |
| `R-SM-46` | There is no `txHash` field: `payment.transactionId` carries the reference of every rail. | `X9-HOLD-101`, `X9-EVT-503` | `S2.4-AC5`, `S3.4-AC1` |

## ENDPOINTS.md

| Rule | What it says | Tests | Proves |
|---|---|---|---|
| `R-EP-01` | The API has no built-in authentication: management and public endpoints answer without credentials. | `X9-LIFE-100`, `X9-EVT-001` | — |
| `R-EP-02` | The loc URL is in the QR string, in EMV tag 26 under the GUI `org.x9`, and is built from the configured host. | `X9-LOC-030`, `X9-LOC-509`, `X9-LOC-511` | `S3.4-AC1` |
| `R-EP-03` | `POST /pub/api/v1/loc/{id}` answers a signed request with the JWS-signed payload. | `X9-LOC-041`, `X9-LOC-303`, `X9-LOC-304`, `X9-LOC-503` | `S3.4-AC1`, `S4.3-AC1` |
| `R-EP-04` | The notification URL travels in the signed payload (`paymentNotification.endpoint`). | `X9-LOC-505` | `S3.4-AC1` |
| `R-EP-05` | A creditor-supplied `paymentNotification.endpoint` is the notification URL of that QR Code. | `X9-HOLD-404`, `X9-LIFE-046` | `S4.3-AC2` |
| `R-EP-06` | `GET /pub/.well-known/jwks` serves the public signing keys. | `X9-SIG-076`, `X9-SIG-042`, `X9-SIG-507` | `S3.4-AC1` |
| `R-EP-07` | `GET /pub/.well-known/certificate/{pemFileName}` serves the signing certificate. | `X9-SIG-075`, `X9-SIG-041` | — |
| `R-EP-08` | The payment-request endpoints create, read, patch and change the status of a QR Code. | `X9-LIFE-100`, `X9-PATCH-030`, `X9-PATCH-031`, `X9-LIFE-102`, `X9-LIFE-103` | — |
| `R-EP-09` | The decoder reads an EMV string, fetches the payee's signed payload and verifies it. | `X9-SIG-100`, `X9-DEC-003` | — |
| `R-EP-10` | The payer-side endpoints compose, sign and deliver a notification to the payee. | `X9-LIFE-070`, `X9-LIFE-075`, `X9-SIG-030`, `X9-SIG-301`, `X9-SIG-304` | `S4.3-AC3` |
| `R-EP-11` | The signature endpoints generate a JWS and validate one. | `X9-SIG-101`, `X9-SIG-071` | — |
| `R-EP-12` | `/test/clock` is never on the API port; it exists only in a test deployment, on its own listener. | `X9-TIME-532`, `X9-TIME-531`, `X9-TIME-538`, `X9-TIME-537` | `S2.1-AC1`, `S2.1-AC3`, `S2.1-AC4` |
| `R-EP-13` | Events: `after` resumes after the cursor, with no gap and no repeat; without it the read starts at the beginning. | `X9-EVT-051`, `X9-EVT-055`, `X9-EVT-002`, `X9-EVT-504` | `S3.4-AC1`, `S4.4-AC2` |
| `R-EP-14` | Events: `limit` is 1 to 500, 100 by default. | `X9-EVT-056`, `X9-EVT-057` | — |
| `R-EP-15` | Events: `wait` holds an empty read open, 0 to 30 seconds, 0 by default; above 30 is refused with 400 naming `wait`. | `X9-EVT-054`, `X9-EVT-053` | `S4.4-AC2` |
| `R-EP-16` | Events: `hasMore` says a page was cut at `limit`; a read past the end is empty with no `nextCursor`. | `X9-EVT-052` | `S4.4-AC2` |
| `R-EP-17` | Events: `eventId` is stable across reads, so a consumer deduplicates by it. | `X9-EVT-055`, `X9-EVT-002`, `X9-EVT-502` | `S3.4-AC1`, `S4.4-AC2` |
| `R-EP-18` | `payment.sent` is not "paid": only `payment.cleared`, emitted by a status-update, says funds arrived. | `X9-MAP-011`, `X9-HOLD-402`, `X9-EVT-001` | `S4.1-AC1`, `S4.1-AC3`, `S4.3-AC2` |
| `R-EP-19` | Events from a payer's notification carry `receivedInStatus` and, when the payer broke the protocol, `deviations`; `schemaVersion` is `1.1`. | `X9-HOLD-207`, `X9-HOLD-209`, `X9-EVT-050` | `S2.4-AC4`, `S2.4-AC5`, `S4.4-AC2` |
| `R-EP-20` | More than one `payment.sent` for one QR Code is published, never merged. | `X9-HOLD-117`, `X9-HOLD-214` | `S2.4-AC6`, `S2.4-AC7` |
| `R-EP-21` | Events are ordered per `qrCodeId`. | `X9-EVT-050` | `S4.4-AC2` |
| `R-EP-22` | `qrCodeRevision` never goes backwards for one QR Code. | `X9-EVT-050` | `S4.4-AC2` |
| `R-EP-23` | The stream returns every QR Code's events for the deployment; there is no filter. | `X9-EVT-050` | `S4.4-AC2` |
| `R-EP-24` | The loc URL fits in tag 26 (99 characters), so the host is at most 37 characters. | `X9-LOC-425` | `S4.2-AC1`, `S4.2-AC2` |
| `R-EP-25` | A host longer than 37 characters is rejected at startup. | `X9-LOC-480` | — |
| `R-EP-26` | A payer fetches a payload or a JWK set from another host over `https` only. | `X9-LOC-481`, `X9-LOC-482`, `X9-LOC-483` | — |
| `R-EP-27` | A PATCH is checked on its merged result: `dueDate` and every payment method's `validUntil` no later than `validUntil`, whichever side it moved; a refused PATCH changes nothing, the revision included. | `X9-PATCH-040`, `X9-PATCH-041`, `X9-PATCH-426` | — |
| `R-EP-28` | A URL is this deployment's own only when its host, port included, is the configured host (case-insensitive); a URL that only mentions that host is another host's. | `X9-LOC-483`, `X9-LOC-484`, `X9-SIG-068`, `X9-LOC-312` | — |

## INTERPRETATION.md

| Rule | What it says | Tests | Proves |
|---|---|---|---|
| `R-I1-01` | Network keys are written `fednow`, `rtp`, `ach`: accepted and emitted in that spelling. | `X9-RAIL-050`, `X9-RAIL-110` | — |
| `R-I1-02` | Any other casing of a network key on create or PATCH is refused, 400, naming what was sent and what to send. | `X9-RAIL-051`, `X9-RAIL-420` | `S4.2-AC1` |
| `R-I1-03` | A currency code on create or PATCH is exact: `usd` is refused in favour of `USD`. | `X9-CUR-012`, `X9-CUR-063`, `X9-CUR-420` | `S4.2-AC1` |
| `R-I1-04` | `payment.network` and the currency of a notification are matched whatever their case. | `X9-RAIL-070`, `X9-RAIL-013`, `X9-CUR-050`, `X9-CUR-030`, `X9-HOLD-401` | `S4.3-AC2` |
| `R-I1-05` | The notified network is stored and echoed back as sent. | `X9-RAIL-071`, `X9-HOLD-401` | `S4.3-AC2` |
| `R-I1-06` | Leniency about case is not leniency about the rail, the currency or the amount. | `X9-RAIL-072`, `X9-CUR-051`, `X9-AMT-070` | — |
| `R-I2-01` | Only `fednow`, `rtp`, `ach` and `solana` are interpreted. | `X9-RAIL-010`, `X9-RAIL-014`, `X9-RAIL-110` | — |
| `R-I2-02` | Any other network is refused at creation, 400, naming it; it is never dropped silently. | `X9-RAIL-052`, `X9-RAIL-053`, `X9-RAIL-420`, `X9-RAIL-501` | `S3.4-AC1`, `S4.2-AC1` |
| `R-I3-01` | Only a currency on the allow list (`supported-currencies.json`) is accepted at creation; another is refused by name. | `X9-CUR-010`, `X9-CUR-013`, `X9-CUR-060`, `X9-CUR-061`, `X9-CUR-062`, `X9-CUR-420`, `X9-CUR-501` | `S3.4-AC1`, `S4.2-AC1` |
| `R-I3-02` | An empty allow list turns the check off. | `X9-CUR-016`, `X9-CUR-017` | — |
| `R-I3-03` | No currency is implied: a payment method without a currency does not take the bill's. | `X9-CUR-421` | `S4.2-AC1` |
| `R-I3-04` | Currencies on one request must share a peg group. | `X9-CUR-020`, `X9-CUR-022`, `X9-CUR-023`, `X9-CUR-024`, `X9-CUR-027`, `X9-CUR-422` | `S4.2-AC1` |
| `R-I4-01` | The notified network is read from `payment.network`. | `X9-RAIL-070`, `X9-RAIL-030` | — |
| `R-I5-01` | `paymentMethods` is an array; a bare object is refused. | `X9-RAIL-120` | — |
| `R-I5-02` | One `paymentMethods` entry carries every rail that settles its currency. | `X9-RAIL-110`, `X9-RAIL-111` | — |
| `R-I6-01` | A bank account number is 4 to 17 digits or letters; anything else is refused. | `X9-RAIL-080`, `X9-RAIL-081`, `X9-RAIL-082`, `X9-RAIL-083` | — |
| `R-I6-02` | `protectionType` is the payee's (§14.5.x.3): what was sent on a bank rail is what GET and the signed payload return, and a PATCH can change it. | `X9-RAIL-085`, `X9-RAIL-086`, `X9-RAIL-087`, `X9-RAIL-506` | — |
| `R-I7-01` | A payer signing with an RSA key or an EC key is verified, from the `alg` it presents. | `X9-SIG-050`, `X9-SIG-054`, `X9-SIG-509` | `S3.2-AC1`, `S3.2-AC2` |
| `R-I7-02` | This service signs with the key it is given, EC or RSA. | `X9-SIG-040`, `X9-SIG-041`, `X9-SIG-090`, `X9-SIG-091` | — |
| `R-I7-03` | A configured `alg` the key cannot produce fails at startup, naming both. | `X9-SIG-092`, `X9-SIG-093` | — |
| `R-I8-01` | A Solana `recipient` is 32 to 44 Base58 characters: a 43-character address is accepted. | `X9-RAIL-045`, `X9-RAIL-046`, `X9-RAIL-043` | — |
| `R-I8-02` | The Solana `memo` is optional, limited in length, and carried exactly as supplied. | `X9-RAIL-041`, `X9-RAIL-042`, `X9-RAIL-047` | — |
| `R-I9-01` | As issuer, an unknown network is refused by name, on create and on PATCH. | `X9-RAIL-052`, `X9-RAIL-054` | — |
| `R-I9-02` | As payer-side transport, an unknown network in someone else's payload is carried intact, whatever its shape. | `X9-RAIL-060`, `X9-RAIL-062`, `X9-RAIL-063`, `X9-RAIL-064`, `X9-RAIL-017` | — |
| `R-I9-03` | An interpreted rail is closed: an unknown field in `solana` is refused, by its path, on create and on PATCH. | `X9-RAIL-044`, `X9-RAIL-048`, `X9-RAIL-049`, `X9-RAIL-055`, `X9-RAIL-502`, `X9-RAIL-503` | — |
| `R-I10-01` | `payment.amount` is the total, tip included; `amount − tipAmount` is what is judged against the bill. | `X9-TIP-001`, `X9-TIP-002`, `X9-TIP-101`, `X9-TIP-102` | — |
| `R-I10-02` | A tip on a bill that does not allow tips is refused. | `X9-TIP-005`, `X9-TIP-103` | — |
| `R-I10-03` | A tip outside the published range is refused. | `X9-TIP-007`, `X9-TIP-008`, `X9-TIP-009`, `X9-TIP-104` | — |
| `R-I10-04` | Presets do not bind: a bill with presets and no range accepts any positive tip. | `X9-TIP-011`, `X9-TIP-012`, `X9-TIP-109`, `X9-TIP-421`, `X9-TIP-506` | `S3.4-AC1`, `S4.2-AC1` |
| `R-I10-05` | A tip offer must carry a range or presets: `{"allowed": true}` alone is refused at creation. | `X9-TIP-420`, `X9-TIP-502` | `S3.4-AC1`, `S4.2-AC1` |
| `R-I10-06` | The tip percentage is of what is being paid (`amount − tipAmount`), also on an editable amount. | `X9-TIP-013`, `X9-TIP-110`, `X9-TIP-111` | — |
| `R-I10-07` | A tip may miss its bound by one minor unit, not by two. | `X9-TIP-014`, `X9-TIP-015`, `X9-TIP-112`, `X9-TIP-113` | — |
| `R-I10-08` | Tip percentages are taken in the notified currency, not the bill's. | `X9-TIP-140` | — |
| `R-I10-09` | `presets` has no minimum size: a bill with no tip block, with tips refused, or with a range only can be created. | `X9-TIP-121`, `X9-TIP-122`, `X9-TIP-501` | `S3.4-AC1` |
| `R-I10-10` | A tip of zero, a negative tip, and a tip larger than the transfer are refused. | `X9-TIP-105`, `X9-TIP-106`, `X9-TIP-004`, `X9-TIP-108` | — |
| `R-I11-01` | A notification without a `transactionId` is a pre-commit; with one, a post-commit. | `X9-HOLD-101`, `X9-HOLD-102` | `S2.4-AC1`, `S2.4-AC5` |
| `R-I11-02` | A pre-commit is judged by the payee's clock and reserves the QR Code, on every rail. | `X9-HOLD-201`, `X9-MAP-020` | `S2.4-AC1`, `S2.4-AC2`, `S2.5-AC5`, `S4.1-AC1`, `S4.1-AC3` |
| `R-I11-03` | A post-commit is recorded and never judged again. | `X9-TIME-002`, `X9-HOLD-206` | `S2.4-AC3`, `S2.4-AC4`, `S4.5-AC1` |
| `R-I11-04` | While a payer holds the reservation another payer's pre-commit is 409; two that arrive together end with one winner. | `X9-HOLD-202`, `X9-HOLD-203`, `X9-HOLD-406` | `S2.4-AC2`, `S4.3-AC2`, `S4.3-AC3` |
| `R-I11-05` | Another payer's post-commit is registered, flagged, and changes nothing: the holder keeps the QR Code. | `X9-HOLD-204`, `X9-MAP-033`, `X9-HOLD-024` | `S2.4-AC2`, `S4.1-AC1`, `S4.1-AC3` |
| `R-I11-06` | A post-commit on an `ACTIVE` QR Code reserves it for that payer: `payment.initiated`, then `payment.sent` on a chain. | `X9-HOLD-207`, `X9-HOLD-208`, `X9-MAP-030`, `X9-MAP-031` | `S2.4-AC4`, `S2.4-AC5`, `S4.1-AC1`, `S4.1-AC3` |
| `R-I11-07` | A post-commit on a `PAID` or `CANCELLED` QR Code changes nothing; on a chain its hash is still published. | `X9-HOLD-215`, `X9-HOLD-216`, `X9-MAP-034` | `S2.4-AC7`, `S2.4-AC8`, `S4.1-AC1`, `S4.1-AC3` |
| `R-I11-08` | Once a hash is registered, the holder's retried pre-commit is 409 and its `NOT_SENT` changes nothing. | `X9-HOLD-218`, `X9-HOLD-219`, `X9-MAP-022`, `X9-MAP-043` | `S4.1-AC1`, `S4.1-AC3` |
| `R-I11-09` | A `NOT_SENT` carrying a `transactionId` is a post-commit, flagged `ACTION_CONTRADICTS_HASH`. | `X9-HOLD-220`, `X9-MAP-044` | `S4.1-AC1`, `S4.1-AC3` |
| `R-I11-10` | A notification that keeps losing a concurrent write answers 409 "busy", never 500. | `X9-HOLD-221` | — |
| `R-I11-11` | Every event a notification produces carries `receivedInStatus` and, when the payer broke the protocol, `deviations`. | `X9-HOLD-207`, `X9-HOLD-209`, `X9-HOLD-210`, `X9-HOLD-211` | `S2.4-AC4`, `S2.4-AC5` |
| `R-I11-12` | On a bank rail no `payment.sent` is published; the deviation is in `payment.initiated` when there is one. | `X9-HOLD-208`, `X9-HOLD-205`, `X9-HOLD-216` | `S2.4-AC2`, `S2.4-AC3`, `S2.4-AC4`, `S2.4-AC5`, `S2.4-AC7`, `S2.4-AC8` |
| `R-I11-13` | A payer without `payer.info` may win a pre-commit, but cannot retry it or release it (409), and its post-commit is flagged. | `X9-HOLD-306`, `X9-HOLD-132`, `X9-HOLD-133`, `X9-HOLD-134` | `S2.5-AC4` |
| `R-I11-14` | The same certificate with a different `payer.info` is a different payer. | `X9-HOLD-305`, `X9-HOLD-002` | `S2.5-AC4` |
| `R-I11-15` | A retry is judged again, and a refused retry releases the reservation (`payment.failed` on a chain). | `X9-HOLD-301`, `X9-HOLD-302`, `X9-HOLD-308`, `X9-MAP-023` | `S2.5-AC1`, `S2.5-AC2`, `S4.1-AC1`, `S4.1-AC3` |
| `R-I11-16` | A refused pre-commit from a non-holder, or on an `ACTIVE` QR Code, changes nothing. | `X9-HOLD-303`, `X9-LIFE-005` | `S2.5-AC2` |
| `R-I11-17` | Winning the pre-commit on a lapsed reservation publishes `payment.initiated` for the new holder. | `X9-HOLD-311`, `X9-HOLD-108` | `S2.5-AC5` |
| `R-I11-18` | A registered hash stops the window, whoever sent it. | `X9-HOLD-312`, `X9-HOLD-313`, `X9-HOLD-314` | `S2.5-AC6` |
| `R-I11-19` | `x9.reservation.ttl-seconds: 0` means no window; a negative value refuses to start. | `X9-HOLD-321`, `X9-HOLD-322`, `X9-HOLD-323`, `X9-HOLD-324` | `S2.5-AC2`, `S2.5-AC7` |
| `R-I11-20` | A `PAYMENT_INITIATED` carrying a hash is kept as a post-commit, recorded as received, flagged `ACTION_CONTRADICTS_HASH`. | `X9-HOLD-209`, `X9-MAP-040` | `S2.4-AC5`, `S4.1-AC1`, `S4.1-AC3` |
| `R-I11-21` | Every hash is registered, a second one included, and this service never marks a QR Code `PAID`. | `X9-HOLD-214`, `X9-HOLD-402` | `S2.4-AC6`, `S2.4-AC7`, `S4.3-AC2` |
| `R-I12-01` | A yes is a compact JWS (`application/jose`) whose body is `{"statusCode": 200}`, echoing the notification's `correlationId`. | `X9-SIG-120`, `X9-SIG-121`, `X9-SIG-516` | `S2.3-AC1`, `S3.2-AC4`, `S3.2-AC6`, `S3.2-AC7` |
| `R-I12-02` | A no keeps its HTTP status and is signed too, with `statusCode` and the error fields. | `X9-SIG-122`, `X9-SIG-123`, `X9-HOLD-405` | `S2.3-AC2`, `S4.3-AC2` |
| `R-I12-03` | A notification that cannot be verified is refused, signed, under a fresh `correlationId`. | `X9-SIG-124`, `X9-SIG-522` | `S2.3-AC2`, `S3.2-AC7` |
| `R-I12-04` | A signed response carries `statusCode` in its header. | `X9-SIG-072` | — |
| `R-I12-05` | Every answer from the endpoint is signed, also a 400 or 415 refused before the controller and an unmapped 500. | `X9-SIG-146`, `X9-SIG-147`, `X9-SIG-148`, `X9-SIG-149`, `X9-SIG-523` | `S3.2-AC7` |
| `R-I12-06` | Sending for a payer, the service checks the payee's answer and reports `signature` as `VERIFIED`, `ABSENT` or `INVALID`. | `X9-SIG-130`, `X9-SIG-131`, `X9-SIG-133`, `X9-SIG-134`, `X9-SIG-136`, `X9-SIG-137`, `X9-SIG-502` | `S2.3-AC6`, `S3.5-AC2`, `S3.5-AC3` |
| `R-I12-07` | When the answer is not verified, `signatureProblem` is a fixed code from a closed list. | `X9-SIG-135`, `X9-SIG-142`, `X9-SIG-143`, `X9-SIG-506` | `S3.2-AC2` |
| `R-I12-08` | It reports and never refuses: a 200 that is not verified is an unsigned yes. | `X9-SIG-132`, `X9-SIG-136`, `X9-SIG-138` | `S2.3-AC6` |
| `R-I12-09` | A JWS whose `iat + ttl` has passed is refused (`EXPIRED`). | `X9-SIG-141`, `X9-TIME-011` | `S4.5-AC1` |
| `R-I12-10` | A JWS whose `iat` is ahead of our clock by more than the skew is refused (`FUTURE_IAT`), in questions and in answers. | `X9-SIG-140`, `X9-SIG-144`, `X9-SIG-145` | — |
| `R-I12-11` | The answer is signed with `typ: JOSE`. | `X9-SIG-151` | — |
| `R-I13-01` | The payee's clock at receipt decides whether a pre-commit is still valid and which amount is owed. | `X9-TIME-016`, `X9-HOLD-201`, `X9-HOLD-308` | `S1.3-AC2`, `S2.4-AC1`, `S2.4-AC2`, `S2.5-AC1`, `S2.5-AC2`, `S4.5-AC2` |
| `R-I13-02` | A pre-commit the payee's clock refuses is a 400 on the amount or on `validUntil`. | `X9-AMT-042`, `X9-AMT-044`, `X9-LIFE-041`, `X9-SIG-521` | `S3.2-AC7` |
| `R-I13-03` | A pre-commit is accepted up to `validUntil` and refused one millisecond after. | `X9-TIME-006`, `X9-TIME-536` | `S1.3-AC3`, `S4.5-AC1` |
| `R-I13-04` | At exactly `dueDate` the full amount is owed with no late fee; the first day's fee applies one millisecond later. | `X9-TIME-004`, `X9-AMT-501`, `X9-AMT-504` | `S1.3-AC4`, `S2.6-AC1`, `S4.5-AC1` |
| `R-I14-01` | A discount is served, advertised and accepted up to its edge E, and refused one millisecond after; the face amount is still accepted. | `X9-TIME-001`, `X9-TIME-003`, `X9-AMT-506` | `S1.3-AC1`, `S2.6-AC1`, `S2.6-AC3`, `S2.6-AC4`, `S4.5-AC1` |
| `R-I14-02` | Of several discounts the largest eligible one is served, until its own edge. | `X9-AMT-507` | `S2.6-AC3` |
| `R-I14-03` | A late fee rises every 24 hours from `dueDate`, and its `validUntil` is the next boundary. | `X9-TIME-005`, `X9-AMT-510` | `S2.6-AC1`, `S2.6-AC2`, `S4.5-AC1` |
| `R-I14-04` | An adjusted amount's `validUntil` is never later than the QR Code's. | `X9-TIME-005`, `X9-AMT-511` | `S2.6-AC1`, `S2.6-AC2`, `S4.5-AC1` |
| `R-I14-05` | The payload is priced at one instant, the later of `dateForPayment` and now, and shows only the payment methods valid at that instant; a past `dateForPayment` never brings back an expired method. | `X9-TIME-017`, `X9-TIME-020`, `X9-TIME-021` | — |
| `R-I15-01` | A payment request without `paymentNotification` gets no notification: one sent anyway is refused, signed, and the QR Code stays `ACTIVE` (X9.150 §9). | `X9-HOLD-403` | — |
| `R-I15-02` | On FedNow and RTP the payee can reserve the QR Code without any notification: status-update `PAYMENT_INITIATED` from `ACTIVE` reserves it for nobody identifiable, with `payment.initiated`. | `X9-MAP-010` | — |
| `R-I15-03` | On FedNow and RTP the payee can close the QR Code from the network alone: status-update `PAID` with the End-to-End ID. | `X9-MAP-011` | — |
| `R-I15-04` | When notifications are exchanged on FedNow and RTP, they work as on any other rail: a pre-commit reserves, a post-commit is registered. | `X9-MAP-020`, `X9-MAP-030` | — |
| `R-I15-05` | A notification on a rail the payee does not want is refused as not needed on that rail. | NOT BUILT (ADR-0036) | — |
| `R-I16-01` | Generating (create and PATCH): a network this service does not interpret is refused, 400, by name; a network with an empty name is one of them. | `X9-RAIL-052`, `X9-RAIL-054`, `rules_test.go::TestCheckCreate` | — |
| `R-I16-02` | Generating: a currency outside `supported-currencies.json` is refused, by name. | `X9-CUR-013`, `X9-CUR-062` | — |
| `R-I16-03` | Generating: an unknown field in `solana` is refused, by name. | `X9-RAIL-044`, `contract_test.go::TestViolationsAreJavas` | — |
| `R-I16-04` | Generating: an unknown field in `fednow`, `rtp` or `ach` is refused, by name, on create and on PATCH (owner, 2026-11-15). | `X9-RAIL-056`, `X9-RAIL-057`, `X9-RAIL-504`, `X9-RAIL-505` | — |
| `R-I16-05` | Reading another bank's payload (the decoder): an unknown network is not refused; it is carried, and the rails this service knows are presented. | `X9-RAIL-060`, `X9-RAIL-061`, `X9-RAIL-062`, `X9-RAIL-063` | — |
| `R-I16-06` | Reading another bank's payload: an unknown field inside a known rail, an unknown payment-method entry and a currency this deployment does not settle are not refused, and the known methods are presented. | `X9-DEC-017`, `X9-DEC-018`, `X9-DEC-019`, `X9-DEC-020`, `X9-DEC-501`, `X9-DEC-502`, `X9-DEC-503`, `X9-DEC-504` | — |
| `R-I16-07` | Reading never tolerates an integrity failure: malformed EMV, a bad signature, or content that does not match the issued QR Code is refused. | `X9-DEC-015`, `X9-DEC-016`, `X9-DEC-003`, `X9-LOC-304`, `X9-SIG-003` | — |

## ADRs 0004 to 0021

Where a later ADR replaced part of one of these, the row states the rule as it stands and names
the ADR that changed it.

| Rule | What it says | Tests | Proves |
|---|---|---|---|
| `R-ADR0004-01` | The phase is inferred from `payment.transactionId`: absent is a pre-commit, present a post-commit (on every rail since ADR-0036). | `X9-HOLD-101`, `X9-HOLD-102` | `S2.4-AC1`, `S2.4-AC5` |
| `R-ADR0004-02` | `SENT` without a transaction id is rejected. | `X9-MAP-041`, `X9-HOLD-121`, `X9-LIFE-064` | `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0004-03` | `PAYMENT_INITIATED` with a transaction id is treated as a post-commit and recorded. | `X9-MAP-040`, `X9-HOLD-118` | `S2.4-AC5`, `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0005-01` | Amounts are 64-bit integers in minor units, exact to the unit; one past 2^63−1 is an unreadable body. | `X9-AMT-421`, `X9-AMT-422` | `S4.2-AC1` |
| `R-ADR0005-02` | A payment amount and a tip amount are not negative. | `X9-AMT-002`, `X9-AMT-420`, `X9-TIP-106` | `S4.2-AC1` |
| `R-ADR0005-03` | Amount fields carry at most 18 digits: 999999999999999999 is accepted, 10^18 is refused with 400 naming the field (the adjustment amount is signed, bounded in magnitude). | `X9-AMT-110`, `X9-AMT-111`, `X9-AMT-112`, `X9-AMT-120`, `X9-AMT-121`, `X9-AMT-122`, `X9-AMT-123`, `X9-AMT-421`, `X9-AMT-006` | `S4.2-AC1` |
| `R-ADR0005-04` | A bill whose late fee could push the bill or a payment-method amount past 18 digits before the QR Code's `validUntil` is refused with 400 naming the field, at creation and on PATCH (merged state, revision unmoved); the fee stops growing at `validUntil`, is computed without overflow, and the formula refuses rather than emits an amount past 18 digits. | `X9-AMT-113`, `X9-AMT-114`, `X9-AMT-115`, `X9-AMT-116`, `X9-AMT-117`, `X9-AMT-118`, `X9-AMT-119`, `X9-AMT-124`, `X9-AMT-125`, `X9-AMT-126`, `X9-AMT-423` | `S4.2-AC1` |
| `R-ADR0006-01` | A QR Code created without `paymentNotification` refuses notifications. | `X9-LIFE-045`, `X9-HOLD-403` | `S4.3-AC2` |
| `R-ADR0006-02` | `kind: DEFAULT` accepts and records notifications. | `X9-LIFE-040`, `X9-LIFE-104` | — |
| `R-ADR0006-03` | `kind: EXTERNAL` names the creditor's own endpoint, and a notification sent here is refused. | `X9-LIFE-046`, `X9-HOLD-404` | `S4.3-AC2` |
| `R-ADR0006-04` | `mode: APPROVE` asks the biller to approve a payment (two-phase approval, ADR-0003). | NOT BUILT (ADR-0006) | — |
| `R-ADR0007-01` | A payer's certificate must chain to the truststore and not be revoked. | `X9-SIG-052`, `X9-SIG-053`, `X9-LOC-305`, `X9-SIG-512` | `S3.2-AC2`, `S4.3-AC1` |
| `R-ADR0007-02` | `allowed-issuers` narrows the accepted issuers, and `allow-self-signed` admits a self-signed certificate. | NOT BUILT (ADR-0007) | — |
| `R-ADR0007-03` | Every payer holding a certificate from a trusted CA is accepted: there is no list of payers. | `X9-SIG-050`, `X9-SIG-054`, `X9-SIG-509` | `S3.2-AC1`, `S3.2-AC2` |
| `R-ADR0008-01` | A status-update `PAYMENT_INITIATED` reserves the QR Code, with no biller vote. | `X9-LIFE-050`, `X9-LIFE-051`, `X9-MAP-010` | `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0008-02` | On a `PAID` or `CANCELLED` QR Code it is refused with 409 (on a held one it is a retry, ADR-0020). | `X9-MAP-010`, `X9-LIFE-053`, `X9-LIFE-054` | `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0009-01` | There is no tenant: the events API returns everything the deployment holds. | `X9-EVT-050` | `S4.4-AC2` |
| `R-ADR0009-02` | Every event carries the keys the consumer routes by: the QR Code id and the biller's invoice number. | `X9-EVT-001` | — |
| `R-ADR0010-01` | Only rails with a published embedding are interpreted: FedNow, RTP, ACH and Solana. | `X9-RAIL-010`, `X9-RAIL-011`, `X9-RAIL-012`, `X9-RAIL-016` | — |
| `R-ADR0010-02` | Solana is interpreted as published: `recipient` (mandatory) and `memo`; the field name once guessed is refused. | `X9-RAIL-040`, `X9-RAIL-043`, `X9-RAIL-044` | — |
| `R-ADR0010-03` | Every rail's notification is validated: none falls through unchecked. | `X9-RAIL-090`, `X9-RAIL-103`, `X9-RAIL-073` | — |
| `R-ADR0011-01` | The quote presented to a payer is stored, per fetch, and the payment is judged against it. | NOT BUILT (ADR-0011) | — |
| `R-ADR0012-01` | A network this deployment does not support is refused by name, on create and on PATCH. | `X9-RAIL-052`, `X9-RAIL-053`, `X9-RAIL-054`, `X9-RAIL-420` | `S4.2-AC1` |
| `R-ADR0012-02` | Only currencies in `supported-currencies.json` are accepted; every offending one is named; an empty list disables the check. | `X9-CUR-013`, `X9-CUR-014`, `X9-CUR-016`, `X9-CUR-062` | — |
| `R-ADR0012-03` | Our own API accepts one spelling of each network key and currency code; any other casing is refused, naming both. | `X9-RAIL-051`, `X9-CUR-012`, `X9-CUR-063` | — |
| `R-ADR0012-04` | `payment.network` on a notification is matched whatever its case and echoed as sent. | `X9-RAIL-070`, `X9-RAIL-071` | — |
| `R-ADR0012-05` | What we generate is closed: an unknown field inside `fednow`, `rtp` or `ach` is refused, by its path, on create and on PATCH (amendment of 2026-11-15). | `X9-RAIL-056`, `X9-RAIL-057`, `X9-RAIL-504`, `X9-RAIL-505` | — |
| `R-ADR0012-06` | What we read is tolerant: another bank's payload with an unknown rail, an unknown currency or unknown fields decodes, the known methods presented, unknown fields ignored (amendment of 2026-11-15). | `X9-DEC-017`, `X9-DEC-018`, `X9-DEC-019`, `X9-DEC-020`, `X9-DEC-501`, `X9-DEC-502`, `X9-DEC-503`, `X9-DEC-504` | — |
| `R-ADR0012-07` | Tolerance stops at integrity: a foreign payload whose signature fails is refused, and nothing of it is presented (amendment of 2026-11-15). | `X9-DEC-021`, `X9-DEC-505` | — |
| `R-ADR0013-01` | A transaction is retried only when MongoDB labelled it `TransientTransactionError`; a duplicate key or a business failure is not. | `X9-LIFE-080`, `X9-LIFE-081`, `X9-LIFE-082`, `X9-LIFE-083`, `X9-LIFE-084` | — |
| `R-ADR0013-02` | The retry wraps the transaction, so each attempt gets a new one. | `X9-LIFE-086`, `X9-LIFE-087` | — |
| `R-ADR0013-03` | Attempts are bounded, and the original failure is rethrown. | `X9-LIFE-085` | — |
| `R-ADR0014-01` | A post-commit is not compared with the pre-commit: it is recorded with the amount it reports, and both amounts reach the consumer. | `X9-TIME-002`, `X9-EVT-021` | `S4.5-AC1` |
| `R-ADR0014-02` | A post-commit does not mark a QR Code `PAID`; only the status endpoint does. | `X9-HOLD-402`, `X9-LIFE-062` | `S4.3-AC2` |
| `R-ADR0014-03` | A pre-commit's amount is checked against what the payload published. | `X9-AMT-001`, `X9-AMT-004`, `X9-AMT-005`, `X9-AMT-060`, `X9-AMT-061` | — |
| `R-ADR0014-04` | One instance drains the outbox at a time, under a lease. | `X9-EVT-010`, `X9-EVT-011`, `X9-EVT-012`, `X9-EVT-013`, `X9-EVT-014` | — |
| `R-ADR0014-05` | The event log is bounded by time, not by acknowledgement. | `X9-EVT-020` | — |
| `R-ADR0015-01` | The pre-payment and post-payment endpoints sign the notification with this deployment's key and deliver it. | `X9-SIG-030`, `X9-SIG-031`, `X9-SIG-032`, `X9-LIFE-070` | — |
| `R-ADR0015-02` | A pre-payment carrying a `transactionId` is refused before it is sent. | `X9-LIFE-073`, `X9-SIG-303` | `S4.3-AC3` |
| `R-ADR0015-03` | A post-payment without a `transactionId` is refused before it is sent. | `X9-LIFE-074`, `X9-SIG-304` | `S4.3-AC3` |
| `R-ADR0015-04` | The payee's refusal comes back as 200 with `accepted: false` and the payee's body. | `X9-LIFE-071`, `X9-SIG-301` | `S4.3-AC3` |
| `R-ADR0015-05` | An unreachable payee is 502. | `X9-LIFE-072`, `X9-SIG-302`, `X9-SIG-504` | `S3.5-AC2`, `S4.3-AC3` |
| `R-ADR0015-06` | The call has timeouts: a payee answering after 15 seconds is 502. | `X9-LIFE-076`, `X9-LIFE-077`, `X9-SIG-505` | `S3.5-AC2` |
| `R-ADR0015-07` | Redirects are not followed. | `X9-SIG-150` | — |
| `R-ADR0015-08` | A relative notification endpoint is refused. | `X9-SIG-033` | — |
| `R-ADR0016-01` | `revision` moves only on a PATCH that changes data; no status change moves it. | `X9-PATCH-010`, `X9-PATCH-011`, `X9-PATCH-012`, `X9-PATCH-013`, `X9-PATCH-425` | `S4.2-AC2`, `S4.2-AC3` |
| `R-ADR0016-02` | The ETag changes on a status change even though the revision does not. | `X9-LIFE-038`, `X9-LIFE-039`, `X9-PATCH-430` | `S4.2-AC3` |
| `R-ADR0016-03` | `lockVersion` is internal and never exposed. | `X9-PATCH-440` | — |
| `R-ADR0017-01` | `payment.amount` is the total, tip included; the merchant's share is `amount − tipAmount`. | `X9-TIP-001`, `X9-TIP-002`, `X9-TIP-003` | — |
| `R-ADR0017-02` | A tip is accepted only when the bill allows one, it is inside the range, and it is not larger than the transfer. | `X9-TIP-005`, `X9-TIP-007`, `X9-TIP-008`, `X9-TIP-009`, `X9-TIP-004`, `X9-TIP-010` | — |
| `R-ADR0017-03` | Presets do not bind; a bill with presets and no range accepts any positive tip. | `X9-TIP-011`, `X9-TIP-012` | — |
| `R-ADR0017-04` | `presets` has no minimum size. | `X9-TIP-121`, `X9-TIP-122` | — |
| `R-ADR0018-01` | A PATCH naming every currency the QR Code offers is accepted, in any order. | `X9-PATCH-001`, `X9-PATCH-002`, `X9-PATCH-506` | `S3.4-AC1` |
| `R-ADR0018-02` | A PATCH naming a currency the QR Code does not offer is refused, 400, naming it: a PATCH never adds a currency. | `X9-PATCH-003`, `X9-PATCH-005`, `X9-PATCH-424`, `X9-PATCH-508` | `S3.4-AC1`, `S4.2-AC2` |
| `R-ADR0018-03` | A PATCH leaving out a currency the QR Code offers is refused, 400, naming it. | `X9-PATCH-004`, `X9-PATCH-507` | `S3.4-AC1` |
| `R-ADR0018-04` | A refused PATCH writes nothing: the revision and the ETag do not move. | `X9-PATCH-509`, `X9-PATCH-422` | `S3.4-AC1`, `S4.2-AC2` |
| `R-ADR0019-01` | The reservation's end is stamped at `min(now + ttl, validUntil)`: the window never outlives the QR Code. | `X9-LIFE-115`, `X9-TIME-010` | `S4.5-AC1` |
| `R-ADR0019-02` | The stamp is cleared when the reservation ends. | `X9-LIFE-116` | — |
| `R-ADR0019-03` | A reservation past its stamp reads `ACTIVE`; the stored status is left alone and nothing sweeps. | `X9-LIFE-110`, `X9-LIFE-111`, `X9-LIFE-112`, `X9-MAP-051` | `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0019-04` | Every decision about status uses the effective status: the next payer, the ETag and `If-Match`. | `X9-LIFE-113`, `X9-LIFE-114`, `X9-LIFE-047`, `X9-LIFE-128`, `X9-LIFE-129`, `X9-TIME-008` | `S2.2-AC1`, `S2.2-AC2`, `S2.5-AC5`, `S4.5-AC1` |
| `R-ADR0019-05` | A terminal status is never reinterpreted. | `X9-LIFE-117` | — |
| `R-ADR0019-06` | A reservation with no stamp is treated as holding. | `X9-LIFE-118` | — |
| `R-ADR0020-01` | The payee may mark `PAID` from `ACTIVE`, from `PAYMENT_INITIATED` (a lapsed reservation included) and past `validUntil`. | `X9-LIFE-120`, `X9-LIFE-121`, `X9-LIFE-122`, `X9-LIFE-170`, `X9-LIFE-171` | `S4.4-AC1` |
| `R-ADR0020-02` | A `CANCELLED` QR Code cannot be marked paid. | `X9-LIFE-123` | — |
| `R-ADR0020-03` | A status-update to the status already held is accepted and changes nothing: no event, no revision. | `X9-LIFE-127`, `X9-LIFE-125` | — |
| `R-ADR0020-04` | Payers stay strict: the payload and notifications are refused for a `PAID`, `CANCELLED` or expired QR Code. | `X9-LIFE-124`, `X9-LIFE-003`, `X9-LIFE-126`, `X9-LOC-310`, `X9-DEC-006` | `S4.3-AC1`, `S4.3-AC2` |
| `R-ADR0021-01` | A reservation records who took it: `payer.info` paired with the subject of the signing certificate. | `X9-HOLD-001`, `X9-HOLD-002`, `X9-HOLD-003`, `X9-HOLD-305` | `S2.5-AC4` |
| `R-ADR0021-02` | The holder's repeated announcement is accepted, refreshes the window and publishes nothing. | `X9-HOLD-010`, `X9-HOLD-012`, `X9-HOLD-030` | — |
| `R-ADR0021-03` | Somebody else's announcement on a held QR Code is 409. | `X9-HOLD-011`, `X9-LIFE-002`, `X9-LIFE-042` | — |
| `R-ADR0021-04` | `NOT_SENT` from the holder releases the QR Code to `ACTIVE` and publishes `payment.failed`. | `X9-HOLD-020`, `X9-HOLD-021`, `X9-HOLD-022` | — |
| `R-ADR0021-05` | `NOT_SENT` from anybody else is 409. | `X9-HOLD-023` | — |
| `R-ADR0021-06` | A holder whose window has lapsed may still report `SENT`. | `X9-TIME-008` | `S2.5-AC5`, `S4.5-AC1` |
| `R-ADR0021-07` | The payment event carries `payerInfo`, null when the payer sent none. | `X9-HOLD-013`, `X9-HOLD-014` | — |

## ADR-0027: certificate fetching and revocation

The rules both builds must agree on ("Certificate fetching" and "Revocation, parity with Java").

| Rule | What it says | Tests | Proves |
|---|---|---|---|
| `R-ADR0027-01` | A `jku` or `x5u` is fetched over `https`, without user information, unless its host, port included, is the deployment's own; a URL that only mentions that host is another host's. | `X9-LOC-483`, `X9-LOC-484`, `X9-SIG-068`, `X9-LOC-312` | — |
| `R-ADR0027-02` | A revocation source the end entity names but that cannot be reached soft-fails: CRL and OCSP, OCSP only, or CRL only. | `X9-SIG-055`, `X9-SIG-056`, `X9-SIG-057`, `X9-SIG-058`, `X9-LOC-307` | `S4.3-AC1` |
| `R-ADR0027-03` | A named CRL that answers with something that is not a CRL soft-fails as an unreachable one does: accepted alone or beside an unreachable OCSP responder; with a responder that answers, OCSP decides. | `X9-SIG-064`, `X9-SIG-065`, `X9-SIG-066`, `X9-SIG-067`, `X9-LOC-307`, `X9-LOC-311` | `S4.3-AC1` |
| `R-ADR0027-04` | A CA-issued certificate naming no revocation source is refused. | `X9-SIG-059`, `X9-LOC-308` | `S4.3-AC1` |
| `R-ADR0027-05` | A certificate a source reports as revoked is refused, by CRL or by OCSP. | `X9-SIG-053`, `X9-LOC-305`, `X9-LOC-306`, `X9-LOC-311` | `S4.3-AC1` |
| `R-ADR0027-06` | These OCSP answers are refused, not soft-failed: an error status other than `tryLater` or `internalError`, a signature by an unauthorised responder, a delegated responder without `OCSPSigning`, an out-of-date answer, no answer for the certificate. | `X9-LOC-313`, `X9-LOC-314` | — |

## ADR-0035: the transition map

One row per row of the map, with the same number as its black-box test. `R-ADR0035-NN` are the
rules the ADR states outside the table.

| Rule | What it says | Tests | Proves |
|---|---|---|---|
| `R-MAP-001` | Create: `ACTIVE`, no event. | `X9-MAP-001`, `X9-LIFE-010` | `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-002` | PATCH: allowed from `ACTIVE` and `PAYMENT_INITIATED`; status unchanged, no event; refused from `PAID` and `CANCELLED`. | `X9-MAP-002` | `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-010` | Status-update `PAYMENT_INITIATED`: from `ACTIVE` (effective), an unattributed reservation, `payment.initiated`. | `X9-MAP-010`, `X9-LIFE-020`, `X9-LIFE-142` | `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-011` | Status-update `PAID`: from `ACTIVE` or `PAYMENT_INITIATED` (stored), `payment.cleared`. | `X9-MAP-011`, `X9-LIFE-120`, `X9-LIFE-121` | `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-012` | Status-update `CANCELLED`: from `ACTIVE` or `PAYMENT_INITIATED` (stored), `payment.cancelled`. | `X9-MAP-012` | `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-013` | Status-update `ACTIVE`: from `PAYMENT_INITIATED` (effective), no event. | `X9-MAP-013`, `X9-LIFE-055` | `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-020` | Pre-commit, new holder: from `ACTIVE` (a lapsed reservation counts), held by the sender, `payment.initiated`. | `X9-MAP-020`, `X9-HOLD-104`, `X9-HOLD-108` | `S2.4-AC1`, `S2.5-AC5`, `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-021` | Pre-commit, same holder: unchanged, window refreshed, no event. | `X9-MAP-021`, `X9-HOLD-105`, `X9-HOLD-304` | `S2.5-AC3`, `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-022` | Pre-commit once a hash is registered: 409, the holder's included. | `X9-MAP-022`, `X9-HOLD-128` | `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-023` | Pre-commit, same holder, refused by the gate: `ACTIVE`, reservation cleared; `payment.failed` on a chain. | `X9-MAP-023`, `X9-HOLD-130`, `X9-HOLD-131` | `S2.5-AC2`, `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-024` | Pre-commit while another payer holds it: 409. | `X9-MAP-024`, `X9-HOLD-106` | `S2.4-AC2`, `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-025` | Pre-commit on `PAID` or `CANCELLED`: 400. | `X9-MAP-025`, `X9-HOLD-107` | `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-030` | Post-commit, bank rail, on `ACTIVE`: reserved, window stopped, `payment.initiated` flagged. | `X9-MAP-030`, `X9-HOLD-110` | `S2.4-AC4`, `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-031` | Post-commit, blockchain, on `ACTIVE`: reserved, window stopped, `payment.initiated` then `payment.sent`, both flagged. | `X9-MAP-031`, `X9-HOLD-109` | `S2.4-AC4`, `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-032` | Post-commit by the holder: unchanged, window stopped; `payment.sent` on a chain, none on a bank rail. | `X9-MAP-032`, `X9-HOLD-112`, `X9-HOLD-113` | `S2.4-AC3`, `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-033` | Post-commit while someone else holds it: unchanged, the holder keeps it; `payment.sent` flagged on a chain. | `X9-MAP-033`, `X9-HOLD-114` | `S2.4-AC2`, `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-034` | Post-commit on `PAID` or `CANCELLED`: unchanged, never reopens; `payment.sent` flagged on a chain. | `X9-MAP-034`, `X9-HOLD-115`, `X9-HOLD-116` | `S2.4-AC7`, `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-040` | `PAYMENT_INITIATED` with a hash: as the post-commit row, plus `ACTION_CONTRADICTS_HASH`. | `X9-MAP-040`, `X9-HOLD-118`, `X9-HOLD-119`, `X9-HOLD-120` | `S2.4-AC5`, `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-041` | `SENT` without a hash: 400. | `X9-MAP-041`, `X9-HOLD-121` | `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-042` | `NOT_SENT` by the holder, a lapsed reservation's included: `ACTIVE`, `payment.failed`. | `X9-MAP-042`, `X9-HOLD-122` | `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-043` | `NOT_SENT` by the holder once a hash is registered: unchanged, no event. | `X9-MAP-043`, `X9-HOLD-138` | `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-044` | `NOT_SENT` with a `transactionId`: as the post-commit row, plus `ACTION_CONTRADICTS_HASH`. | `X9-MAP-044`, `X9-HOLD-139` | `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-045` | `NOT_SENT` not from the holder: 409 while held; 400 on `ACTIVE`, `PAID`, `CANCELLED`. | `X9-MAP-045`, `X9-HOLD-123`, `X9-HOLD-124`, `X9-HOLD-125` | `S4.1-AC1`, `S4.1-AC3` |
| `R-MAP-050` | A location released by a rebind: the donor keeps its status and gets a new location; no event. | `X9-MAP-050`, `X9-LOC-420` | `S4.1-AC1`, `S4.1-AC3`, `S4.2-AC4` |
| `R-MAP-051` | A reservation lapse: reads `ACTIVE` on read only, no event; never once a hash is registered, nor with `ttl-seconds: 0`. | `X9-MAP-051`, `X9-LIFE-111`, `X9-HOLD-126`, `X9-HOLD-135` | `S2.5-AC6`, `S2.5-AC7`, `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0035-01` | A status-update to the status already held (stored and effective): 200, nothing written, no event. | `X9-MAP-010`, `X9-MAP-011`, `X9-MAP-012`, `X9-MAP-013`, `X9-LIFE-127` | `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0035-02` | An event's body comes from the latest stored notification that carries a `transactionId`, else the last stored notification; with none, from the first payment method and the payment details (amended 2026-11-15). | `X9-MAP-011`, `X9-MAP-032`, `X9-LIFE-171`, `row_test.go::TestEventBodySource`, `postgres_test.go::testEventBodyKeepsTheHash` | `S4.1-AC1`, `S4.1-AC3`, `S4.4-AC1` |
| `R-ADR0035-03` | A notification is stored before its event is built, so `payment.sent` carries the hash it reports. | `X9-MAP-032`, `X9-EVT-503` | `S3.4-AC1`, `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0035-04` | Every transition sets `revisedAt`; only PATCH moves `revision`. | `X9-LIFE-021`, `X9-PATCH-425` | `S4.2-AC2`, `S4.2-AC3` |

## ADR-0036, amendments included

| Rule | What it says | Tests | Proves |
|---|---|---|---|
| `R-ADR0036-01` | Only the payment system marks a QR Code `PAID`; no payer message, in any status, does. | `X9-HOLD-402`, `X9-HOLD-214` | `S2.4-AC6`, `S2.4-AC7`, `S4.3-AC2` |
| `R-ADR0036-02` | The phase comes from the `transactionId` on every rail: FedNow and RTP have a pre-commit, Solana a post-commit. | `X9-HOLD-101`, `X9-HOLD-102`, `X9-HOLD-201` | `S2.4-AC1`, `S2.4-AC2`, `S2.4-AC5` |
| `R-ADR0036-03` | `blockchain.action` decides one thing alone: `NOT_SENT`. | `X9-HOLD-103` | — |
| `R-ADR0036-04` | `PAYMENT_INITIATED` with a hash is a post-commit, recorded as received, never judged on amount or time. | `X9-HOLD-209`, `X9-HOLD-118`, `X9-MAP-040` | `S2.4-AC5`, `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0036-05` | `SENT` without a hash is refused, 400. | `X9-HOLD-212`, `X9-MAP-041` | `S2.4-AC5`, `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0036-06` | Every hash is published as its own `payment.sent`; none is overwritten or dropped. | `X9-HOLD-117`, `X9-HOLD-214` | `S2.4-AC6`, `S2.4-AC7` |
| `R-ADR0036-07` | Two notifications come from the same payer only when both carry `payer.info`, equal, and the certificate subjects are equal. | `X9-HOLD-001`, `X9-HOLD-004`, `X9-HOLD-005`, `X9-HOLD-305` | `S2.5-AC4` |
| `R-ADR0036-08` | A payer without `payer.info` may win a pre-commit, cannot retry it (409) or release it, and its post-commit is flagged. | `X9-HOLD-132`, `X9-HOLD-133`, `X9-HOLD-134`, `X9-HOLD-306` | `S2.5-AC4` |
| `R-ADR0036-09` | A reservation taken through the status-update API matches no payer. | `X9-LIFE-173`, `X9-MAP-024` | `S4.1-AC1`, `S4.1-AC3`, `S4.4-AC1` |
| `R-ADR0036-10` | ACH requires `payer.info`. | `X9-HOLD-407` | — |
| `R-ADR0036-11` | A pre-commit goes through the acceptance gate at the payee's clock and takes the reservation, on every rail. | `X9-HOLD-201`, `X9-MAP-020` | `S2.4-AC1`, `S2.4-AC2`, `S2.5-AC5`, `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0036-12` | While a reservation holds, another payer's pre-commit is 409. | `X9-HOLD-202`, `X9-HOLD-203`, `X9-MAP-024` | `S2.4-AC2`, `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0036-13` | The holder may retry its pre-commit, and the retry is judged again. | `X9-HOLD-301`, `X9-HOLD-304`, `X9-MAP-021` | `S2.5-AC1`, `S2.5-AC2`, `S2.5-AC3`, `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0036-14` | A refused retry from the holder releases the reservation in the same transaction. | `X9-HOLD-130`, `X9-HOLD-131`, `X9-HOLD-301`, `X9-MAP-023` | `S2.5-AC1`, `S2.5-AC2`, `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0036-15` | Winning a pre-commit on a lapsed reservation emits `payment.initiated` for the new holder. | `X9-HOLD-108`, `X9-HOLD-311` | `S2.5-AC5` |
| `R-ADR0036-16` | A post-commit on an `ACTIVE` QR Code reserves it: `payment.initiated`, then `payment.sent` on a chain; `payment.initiated` only on a bank rail. | `X9-HOLD-109`, `X9-HOLD-110`, `X9-HOLD-207`, `X9-HOLD-208` | `S2.4-AC4`, `S2.4-AC5` |
| `R-ADR0036-17` | Each notification records the QR Code's effective status when it arrived, and the payee can list the flagged ones per payer. | `X9-HOLD-207`, `X9-HOLD-215`, `X9-HOLD-217` | `S2.4-AC4`, `S2.4-AC7`, `S2.4-AC8` |
| `R-ADR0036-18` | A post-commit on a closed or held QR Code is registered and flagged; the status does not change. | `X9-HOLD-114`, `X9-HOLD-115`, `X9-HOLD-204`, `X9-HOLD-215` | `S2.4-AC2`, `S2.4-AC7` |
| `R-ADR0036-19` | After another payer's post-commit, a pre-commit from that payer is still 409. | `X9-HOLD-111` | `S2.4-AC4` |
| `R-ADR0036-20` | A bank rail never publishes `payment.sent`. | `X9-HOLD-116`, `X9-HOLD-205`, `X9-HOLD-206`, `X9-HOLD-216` | `S2.4-AC2`, `S2.4-AC3`, `S2.4-AC4`, `S2.4-AC7`, `S2.4-AC8` |
| `R-ADR0036-21` | `RECEIVED_IN_WRONG_STATUS` and `ACTION_CONTRADICTS_HASH` may appear together, on every event of the transition. | `X9-HOLD-118`, `X9-HOLD-120`, `X9-HOLD-211` | `S2.4-AC5` |
| `R-ADR0036-22` | `deviations` is absent when the payer followed the rules. | `X9-HOLD-113`, `X9-MAP-032` | `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0036-23` | The new fields are additive: `schemaVersion` is `1.1`. | `X9-EVT-050`, `X9-HOLD-207` | `S2.4-AC4`, `S4.4-AC2` |
| `R-ADR0036-24` | Once any hash is registered the reservation stops expiring, and the holder does not change. | `X9-HOLD-126`, `X9-HOLD-127`, `X9-HOLD-312`, `X9-HOLD-313`, `X9-HOLD-314` | `S2.5-AC6` |
| `R-ADR0036-25` | After a hash only the status-update API ends the reservation: `PAID`, `CANCELLED`, or `ACTIVE`, which clears the hash with it. | `X9-HOLD-129`, `X9-HOLD-140`, `X9-HOLD-307` | `S2.5-AC6` |
| `R-ADR0036-26` | After a hash no payer message ends it: the holder's `NOT_SENT` changes nothing and its retry is 409 (amended 2026-11-14). | `X9-HOLD-128`, `X9-HOLD-138`, `X9-HOLD-218`, `X9-HOLD-219` | — |
| `R-ADR0036-27` | `ttl-seconds: 0` means no window: a reservation ends only by a refused retry, `NOT_SENT`, or the status-update API. | `X9-HOLD-135`, `X9-HOLD-136`, `X9-HOLD-137`, `X9-HOLD-321`, `X9-HOLD-322` | `S2.5-AC2`, `S2.5-AC7` |
| `R-ADR0036-28` | A negative `ttl-seconds` refuses to start; 0 and positive values are taken as they are. | `X9-HOLD-323`, `X9-HOLD-324` | `S2.5-AC7` |
| `R-ADR0036-29` | A status-update to the status already held is a retry: 200, nothing changes (amended 2026-11-14). | `X9-LIFE-127`, `X9-LIFE-143`, `X9-MAP-011`, `X9-MAP-012`, `X9-MAP-013` | `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0036-30` | A status-update `PAYMENT_INITIATED` on a lapsed reservation reserves the QR Code again (amended 2026-11-14). | `X9-LIFE-142`, `X9-MAP-010` | `S4.1-AC1`, `S4.1-AC3` |
| `R-ADR0036-31` | A transition that keeps losing a concurrent write answers 409 "busy" with the current status, never 500. | `X9-HOLD-221` | — |
| `R-ADR0036-32` | Past `validUntil`, a pre-commit is refused by the payee's clock. | `X9-TIME-006` | `S1.3-AC3`, `S4.5-AC1` |
| `R-ADR0036-33` | Past `validUntil`, a post-commit is still accepted and registered. | `X9-TIME-018` | — |
| `R-ADR0036-34` | Every answer to a payer, refusals included, is a signed JWS. | `X9-HOLD-405` | `S4.3-AC2` |
| `R-ADR0036-35` | `NOT_SENT`: the holder's releases with `payment.failed`, a lapsed reservation's included; 409 from someone else; 400 on `ACTIVE`, `PAID`, `CANCELLED`. | `X9-HOLD-122`, `X9-HOLD-123`, `X9-HOLD-124`, `X9-HOLD-125`, `X9-HOLD-316` | — |
| `R-ADR0036-36` | `NOT_SENT` with a `transactionId` is a post-commit, flagged `ACTION_CONTRADICTS_HASH`. | `X9-HOLD-139`, `X9-HOLD-220`, `X9-MAP-044` | `S4.1-AC1`, `S4.1-AC3` |

## ADR-0027: EMV

The owner's decision of 2026-11-15, recorded in ADR-0027's EMV section: bad QR Codes are not
tolerated, on either build. The Go build's tests are in `internal/emv`; these are the Java build's
and the black-box suite's.

| Rule | What it says | Tests | Proves |
|---|---|---|---|
| `R-ADR0027-07` | Malformed EMV is refused, never tolerated: a bad, missing or non-hex CRC, a zero, non-numeric or overrunning length, a duplicate top-level tag, a truncated field or template. The decoder answers 400 naming `qrCode`, before any fetch; a payload request carrying it is refused as a content mismatch. | `X9-DEC-013`, `X9-DEC-014`, `X9-DEC-015`, `X9-DEC-016`, `X9-LOC-021` | — |
| `R-ADR0027-08` | A configured host with a character outside printable ASCII, or a space, stops the service at startup. | `X9-LOC-485` | — |

## ADR-0027: JWS verification

Three rules of ANSI X9.150-2026 that Java broke and Go's verifier (Story 8.5) keeps, fixed in Java
on 2026-11-15 so both builds give the same code; recorded in ADR-0027's verification steps. The Go
build's tests are in `internal/jws` and `internal/pki`.

| Rule | What it says | Tests | Proves |
|---|---|---|---|
| `R-ADR0027-09` | `x5t#S256` must be the thumbprint of the end-entity certificate, the first of the chain (§10.3.1, §10.7 step 7); one naming any other certificate of the chain is refused `UNTRUSTED_CHAIN`, as one naming none is. | `X9-SIG-069`, `X9-SIG-510`, `X9-LOC-304`, `X9-SIG-506` | — |
| `R-ADR0027-10` | `correlationId` must be a UUID in its canonical form, 8-4-4-4-12 hexadecimal digits in either case (§10.7 step 3.1); any other text is refused `BAD_HEADER`. | `X9-SIG-152`, `X9-SIG-526`, `X9-SIG-510`, `X9-LOC-304`, `X9-SIG-506` | — |
| `R-ADR0027-11` | A signed answer's body `statusCode` must be a JSON integer within 32 bits (A.4.5 note); a fraction, an exponent or a larger integer is `STATUS_MISSING`, never truncated. | `X9-SIG-153`, `X9-SIG-527` | — |

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
