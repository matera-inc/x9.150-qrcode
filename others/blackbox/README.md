# Black-box suite — the unhappy paths

Systematic coverage of **every way a payment notification can be wrong**, driven through the
published API of a running deployment. Nothing here imports the service.

This complements the JUnit tests rather than replacing them. JUnit proves a rule in isolation and
fast; this proves the rule survives serialisation, validation *order*, HTTP status mapping and the
signature layer — which is where several of this repo's real defects lived, each of them invisible
to a unit test that was passing at the time.

## Running it

```bash
make blackbox                                  # against http://localhost:8080
uv run others/blackbox/run.py                  # the same, with no virtualenv (PEP 723 header)
X9_BASE_URL=http://localhost:8070 uv run others/blackbox/run.py -k signatures
```

Two pinned dependencies (`requirements.txt`, and the same pins in `run.py`'s header): pytest,
and `cryptography`, because the suite signs as independent payers and the standard library cannot
make an RSA or EC signature (ADR-0034). HTTP stays on the standard library. `make blackbox-venv`
builds `.blackbox-venv`; `requirements-db-mongo.txt` and `requirements-db-postgres.txt` add the
optional database inspectors.

If nothing answers at `X9_BASE_URL`, the suite **skips with a reason** rather than failing —
but the publish gate treats a suite that could not run as a refusal to publish.

### In CI, and against any image

```bash
make image && make blackbox-image                 # the image `make image` built
X9_IMAGE=materainc/x9-qrcode:git-<sha> ./others/blackbox/deployment/run-against-image.sh
```

`run-against-image.sh` runs the whole suite in containers (`deployment/docker-compose.yml`):
MongoDB as a replica set; the image under test with the clock on and the black-box truststore
copied in; and the suite in the service's network namespace, so the service reaches the suite's
test payee and CRL server on `127.0.0.1`, and the suite reaches the clock, which never leaves the
service's loopback. Nothing is bind-mounted, so it works on Colima. It sets `X9_REQUIRE_ALL=1` and
`X9_DATABASE_URL`, so every test runs: a missing capability, a service that never comes up, or
zero tests collected fails the run.

Runs never share anything, so two can go at once (for example the Java image and the Go image
side by side). Each run takes its own compose project name and tags its two images with it:
`X9_BLACKBOX_PROJECT` if you set it (lowercase letters, digits, `-`, `_`), otherwise
`x9-blackbox-` plus a random suffix, which the script prints first. On exit it tears down only
that project and removes only those two tags; `X9_IMAGE` itself is never touched.

```bash
X9_IMAGE=x9-qrcode:java ./others/blackbox/deployment/run-against-image.sh &
X9_IMAGE=x9-qrcode:go   X9_BLACKBOX_PROJECT=x9-blackbox-go ./others/blackbox/deployment/run-against-image.sh
```

The `blackbox` job (`.github/workflows/blackbox.yml`) builds the image with buildpacks on every
pull request and runs exactly this. It is separate from the `build` job. The publish gate
(`make publish`) still runs `make blackbox` against each architecture it is about to push.

#### Against the Go image (Story 9.1)

```bash
make go-image && X9_IMAGE=x9-qrcode:go make blackbox-image
```

The script tells the builds apart by the image's entrypoint (`/x9-qrcode` is the Go binary;
`X9_BUILD=java|go` overrides it) and runs a Go image on `deployment/docker-compose.go.yml`
instead: PostgreSQL 16 on tmpfs; `db/local/bootstrap.sql` and then `db/changelog/` applied by the
Liquibase one-shot as the migration role, before the service starts; the image connected as the
application role, with the test clock on and the black-box truststore as **PKCS#12**
(`x9-blackbox-truststore.p12`, the same two certificates: the Go build refuses JKS); no public
host, so it runs on its default `x9-150.example.com`, which the golden EMV set covers; and the
suite reading the database as the read-only role. That `postgresql://` URL is what makes the suite
take the run as the Go build.

Since Story 10.8 the Go run is judged as the Java run is: **the whole suite, under
`X9_REQUIRE_ALL=1`**, and any failure fails it. The one exception on each side is decided in the
test itself, never in a list beside it:

- a `java_only` test is skipped on Go, and a `go_only` one on Java, with its reason;
- `X9-TIME-015` (retention, ADR-0029) is a strict expected failure on Go naming **Epic 12**: the
  day the retention job lands, it passes, the strict mark turns the run red, and the mark comes out;
- a behaviour where Go follows the standard or an ADR and Java does not is a `go_only` test, or a
  strict expected failure on Java (`X9-LOC-421`, BUG-004), listed in ADR-0028's "Known differences
  between the builds".

The required-keys list that Epics 9 and 10 grew while the Go build was being written
(`deployment/go-required.txt`) is gone. `required_keys.py` stays as a tool for a partial run: point
`X9_REQUIRED_KEYS` at a list file of your own and only the tests it lists decide the exit code,
every other one reported as informative (entries are test keys or `tests/` node-id prefixes, `!`
excludes, an entry that matches nothing fails the run). No CI run sets it.

Before any test, the `api` fixture creates a QR Code and has payer A send it one pre-commit: the
**trust probe**, which turns a deployment that does not trust the test CA into one clear message
instead of a failure in every payer test. Both builds take payer notifications (the Go build since
Story 10.4), so the probe runs the same on each.

The `blackbox-go` job, beside `blackbox`, builds the image with `make go-image` and runs exactly
this on every pull request that touches the Go code, `db/` or `others/blackbox/`, and fails it on
any failure; on any other pull request it passes without running.

### Differential run: what Java answered against what Go answered (Stories 11.1, 11.2)

ADR-0028 proves the two builds equivalent by a diff, not a claim: the same suite against each, the
answers recorded, normalised and compared.

```bash
X9_IMAGE=x9-qrcode:java X9_RECORD_DIR=/tmp/rec/java ./others/blackbox/deployment/run-against-image.sh
X9_IMAGE=x9-qrcode:go   X9_RECORD_DIR=/tmp/rec/go   ./others/blackbox/deployment/run-against-image.sh
python3 others/blackbox/differential.py /tmp/rec/java /tmp/rec/go --json /tmp/rec/differences.json
```

**Recording** (`x9client/recording.py`, wired by `recorder.py`) is off unless `X9_RECORD_DIR` is
set; then the suite's HTTP client appends every exchange, in order, to one JSON-lines file per test
node id: the method, the path with server-minted ids masked (and `rawPath` unmasked), the request
headers that change an answer (`If-Match`, `Content-Type`, `Accept`), the request body, and the
answer's status, `ETag`, `Content-Type`, `Location` and body as received. What a session-scoped
fixture asks (the trust probe, the JWK set, the event reader's start) goes to a file of its own,
`<session>::<fixture>`, so which test happens to build it first never shifts anyone's exchanges.
`_tests.<worker>.jsonl` lists each test's outcome and build markers. A private key never reaches the
client; one in a body would be replaced before writing. `run-against-image.sh` copies the recording
out of the suite's container when the run ends.

**The diff** (`differential.py`, standard library only) pairs the two recordings by test and, inside
a test, by position, aligned on the request so that an exchange only one build made is reported by
its index and the rest still pair. Before comparing it normalises both sides. Normalising removes
only what changes from one run of the *same* build to the next, never a difference between the
builds; one rule is a decision rather than noise, and is written here so that nobody mistakes it
for one: **`violations` are compared as a set**, because the Java build's order of violations
changes from run to run (Bean Validation reports them from a hash set). The owner accepted this on
2026-11-16, and openapi.yaml says it to clients: the order of `violations` is not significant, and
an entry is matched by its field path and message, not its position. The rules: JSON compared as
values (whitespace and key order ignored); server-minted ids, `correlationId`s, event ids, cursors,
times and dates masked (keeping whether an offset is `Z` or numeric); a JWS compared as its decoded
header minus `iat`, `correlationId`, `x5c` and the certificate thumbprint, and its decoded payload,
with its signature masked; a header's `crit` and a problem's `violations` compared as sets (Java's
order of violations changes from run to run);
an EMV string's location id and CRC masked, inside `qrCodeB64` too; an event-stream page reduced to
the events of the test's own QR Codes, grouped by QR Code (the order promised is per QR Code;
across QR Codes it is timing), and a run of polls counted once, as its last page holding every event the run read (each once:
which poll an event landed in is timing). Then status,
`ETag`, `Content-Type`, `Location` and the body must match. It prints counts by kind, the most
recurring differences and every difference, and `--json` writes them with the test node id, the
exchange index, the field and both values. A test only one build recorded is named with why
(`go_only`, `java_only`, skipped). Its own tests are `tests/test_differential.py` (`X9-SUITE-516`
to `530`).

**The tolerated list** ([TOLERATED-DIFFERENCES.md](TOLERATED-DIFFERENCES.md), Story 11.2) is every
difference ADR-0028 accepts: per entry, the two answers, the reason, the test keys that show it,
and one fenced `json` block, the matcher the diff applies. The diff reads it by default
(`--tolerated FILE` for another, `--no-tolerated` for none) and **exits 1** on a difference no entry
matches, on a test only one build recorded that no entry names, and on an entry selector that
matched nothing in the run (**stale**: the difference has gone away, so the entry must go too, or
its matcher was mistyped). It exits 2 on a list it cannot read, naming the entry. An entry marked
`"status": "pending"` is a difference the owner has not ruled on: it is tolerated, so that the run
is not red on it, and every report prints it under **PENDING, NEEDS THE OWNER** until it is decided.

In CI the `blackbox` and `blackbox-go` jobs record, upload their recordings as the artifacts
`recording-java` and `recording-go`, and the `differential` job diffs them and uploads
`differences.json`. When `blackbox-go` found nothing to run (a pull request that touches neither
build, `db/` nor the suite: a Java-only change does run it), there is no Go recording, and
`differential` says so and passes.

#### Reading a failing differential run

The text report, in order: the counts by kind; the tests only one build recorded (each with
`[entry N]` once tolerated); **Tolerated differences**, one line per entry with how many rows it
matched (`STALE: differences[0] matched nothing` names a dead selector); the pending entries, in
capitals; the line **Not tolerated: …**; then the recurring differences no entry tolerates, and every
one of them, as `test #javaIndex/goIndex kind request field: java … | go …`. The JSON report
(`--json`, the `differences` artifact in CI) has the same rows, each with `tolerated` set to the
entry number or `null`, and `tolerated.entries` with each selector's count.

For each untolerated row, look at the two values first, then at the exchange itself in the
recordings (`<recording>/<test file name>.jsonl`, the line with that `index`):

1. **One build is wrong** (it breaks X9.150, an ADR or the contract): fix it, in Go; in Java only
   when the fix is small (ADR-0028 froze it). Then the row disappears.
2. **The difference is per run** (an id, a time, an order nobody promises) and the normaliser
   missed it: teach the normaliser (`normalise`, `MASKED_MEMBERS`, `UNORDERED_MEMBERS`) with a test in
   `test_differential.py`, and say so in the rules above.
3. **The difference is intended:** add an entry, below. Never widen an entry's matcher to swallow a
   row it was not written for.

A stale entry means the difference is gone: remove the entry (or its selector), unless it is a
per-run difference, which `"mayNotOccur": true` on the selector says.

#### Adding an entry

Append to TOLERATED-DIFFERENCES.md, numbered after the last one (numbers are never reused):

````markdown
## 28. What differs, in a few words

**Java:** what Java answers. **Go:** what Go answers.

**Reason:** why Go is right, or why the owner accepts it, naming the standard, ADR or story.

**Keys:** `X9-AREA-NNN`

```json
{"entry": 28, "differences": [
  {"test": "::test_the_test_name(\\[|$)", "request": "GET /api/v1/payment-request/<id>",
   "field": "body\\.some\\.member", "java": "<absent>", "go": null}
]}
```
````

- **Keys** must be tests that exist (`docs/check/check_rule_coverage.py` checks every key the file
  names). A Go test is keyed by the first words of its doc comment (`// X9-SIG-534 (...): ...`).
- A selector names its `test` (a regular expression searched in the pytest node id) and as little
  else as it needs, as narrowly as it can: `request` and `field` are regular expressions the whole
  value must match; `java` and `go` are the normalised values exactly (`"<absent>"` for a member one
  side does not have); `javaPattern`/`goPattern` match a value as text; `present: true` requires
  both sides to have a value; `kind` is `status`, `body`, `header:ETag` and so on.
- `oneBuild` selectors name a `go_only` or `java_only` test (`test`, `recordedBy`, `why`).
- An entry no black-box exchange can show is proved by a Go test instead: `"proof": "go-test"`, and
  its key names that test. `"proof": "normalised"` is for what the normaliser removes (whitespace).
- `"status": "pending"` for a difference the owner has not ruled on yet; take it off when they do.

### The deployment under test

The suite never asks the service to sign for it (no call to `/api/v1/signature/generate`). Its
payers, A (RSA, `PS512`) and B (EC, `ES256`), are issued at run time by the repository's bundled
**test CA** (`x9-test-ca.p12`, non-production, its key is public), along with a revoked payer and
one from a CA nobody trusts. So the deployment must:

- **trust the test CA.** Start it with `others/blackbox/deployment/x9-blackbox-truststore.jks`,
  which holds the test CA and the shipped demo certificate (so the deployment, still signing with
  the demo key, can verify itself when its decoder fetches its own payloads):

  ```bash
  X9_CERTIFICATE_TRUSTSTORE_LOCATION=file:$PWD/others/blackbox/deployment/x9-blackbox-truststore.jks
  X9_CERTIFICATE_TRUSTSTORE_PASSWORD=x9test123
  X9_CERTIFICATE_TRUSTSTORE_ALIAS=x9-test-ca
  ```

  The Go build refuses JKS: give it `x9-blackbox-truststore.p12`, the same two certificates as
  PKCS#12 (same password, `X9_CERTIFICATE_TRUSTSTORE_TYPE=PKCS12`). `build-truststore.sh` writes
  both.

- **reach the suite's CRL.** The service checks revocation of every CA-issued certificate, and a
  CRL it cannot fetch is not forgiven: the payer is refused as undeterminable. The suite serves the
  CRL itself; `X9_SUITE_HOST` is this machine's address *as the service sees it*: `127.0.0.1`
  (the default) when both run on one host or share a network namespace, `host.docker.internal`
  for a container started with `--add-host host.docker.internal:host-gateway`.

The same address is how the service reaches the **test payee** (`x9client.peer`) that its
outbound notifications are sent to; `X9_SUITE_PEER_PORT` fixes its port (default: any free one).

If either is missing, the first payer is refused with 401 and the suite stops there with that
diagnosis, instead of failing every test for one reason. **Never** give a production deployment
this truststore: it trusts keys anybody can download.

| Variable | Default | Meaning |
|---|---|---|
| `X9_BASE_URL` | `http://localhost:8080` | The deployment's API. |
| `X9_CLOCK_URL` | `http://127.0.0.1:8099` | Its test-clock listener ([RUNNING.md](../../RUNNING.md), "Moving the clock"). Time tests skip with "test clock not enabled" when nothing answers there. |
| `X9_DATABASE_URL` | unset | Optional read-only database connection (`mongodb://…` for Java, `postgresql://…` for Go). Database checks skip, with the reason, when it is unset or the driver (`pymongo` / `psycopg`) is not installed. |
| `X9_REQUIRE_ALL` | unset | `1` turns every "capability missing" skip into a failure. CI sets it. |
| `X9_BUILD` | from `X9_DATABASE_URL`, else `java` | `java` or `go`: `go_only` tests skip on Java and `java_only` tests on Go, with the reason. Not a missing capability, so `X9_REQUIRE_ALL` does not fail them. A mark can also depend on it (`builds.under_test()`): `X9-LOC-421` is a strict expected failure on Java only. |
| `X9_RECORD_DIR` | unset | Record every exchange of every test there, for the differential run (see "Differential run" above). `run-against-image.sh` copies the recording out of its container to this path. |
| `X9_REQUIRED_KEYS` | unset | A list file of your own: only the tests it lists decide the exit code; every other one is informative. For a partial run while porting; no CI run sets it. |

## Layout

```
others/blackbox/
├── x9client/          the only code that knows the protocol (ADR-0034)
│   ├── api.py         HTTP on the standard library; ETag / If-Match; problem+json
│   ├── emv.py         TLV decoding and the CRC16 check
│   ├── clock.py       the movable test clock: move_to(), now(), reset()
│   ├── events.py      cursor reader of the event stream, deduplicating by eventId
│   ├── jws.py         independent payers on the test CA, the tamperer, and the answer verifier
│   ├── peer.py        a test payee that answers this service's outbound notifications right and wrong
│   ├── ocsp.py        an OCSP responder for the test CA, and payers with any mix of revocation sources
│   ├── db/            optional read-only inspectors: mongo.py (Java), postgres.py (Go)
│   ├── recording.py   with X9_RECORD_DIR, every exchange written down per test (differential run)
│   ├── samples.py     request bodies a scenario starts from
│   └── checks.py      assert_refused: refused, and for the right reason
├── tests/             the scenarios
├── contract.py        a refusal case per maxLength/minLength/pattern/required/maximum/minimum, and the edge of each bound
├── contract-known-gaps.yaml   constraints the service does not enforce today, each with its reason
├── deployment/        the compose files (Java, Go), the truststore as JKS and PKCS#12
├── golden/            JWS and EMV fixtures exported from Java, with their verdicts (Story 4.7)
├── conftest.py        fixtures: deployment, api, events, clock, db, pki, payer_a, payer_b, tamperer
├── builds.py          which build is under test (java or go), for marks decided at import
├── required_keys.py   an optional required-keys list for a partial run (X9_REQUIRED_KEYS)
├── recorder.py        tells the recorder which test (or shared fixture) is running
├── differential.py    the diff of two recordings, Java against Go (Story 11.1), against the list
├── TOLERATED-DIFFERENCES.md   every difference ADR-0028 accepts, with its matcher (Story 11.2)
├── run.py             `uv run` entry point (PEP 723)
└── pytest.ini         markers: serial, java_only, go_only
```

Tests read like scenarios: `api.notify(qr, amount=BILL + 1)`, `events.for_qr(qr)`,
`clock.move_to(valid_until + 1 s)`. Anything about bytes on the wire belongs in `x9client`.

## The house rule: refused for the *right* reason

```python
assert_refused(api.notify(qr, amount=BILL, tip=200), naming="payment.amount")
```

Asserting only `status >= 400` is how a check passes because the fixture was never built, or
because an unrelated rule fired first. Both have happened here:

- three refusal checks in the acceptance suite passed against a broken build because the QR Code
  could not be created, the notification went out with no `qrcodeId`, and *"is it refused?"* was
  answered yes by the wrong rule;
- a hand-over section reported a **smaller total** rather than a failure, because its checks sat
  behind an `if` and disappeared when the setup did not hold.

So every refusal here names the field it is about, and every group carries at least one
**acceptance** case — without one, a service that refused absolutely everything would score full
marks.

## What is covered

| Group | Cases |
|---|---|
| Transport | unsigned body, empty body, a payload edited after signing |
| Identity | unknown QR Code |
| Fixed amount | a penny short, a penny over, zero, negative, and the exact bill (control) |
| Editable amount | below the range, above it, and three amounts inside it |
| **Tip** | bill + tip accepted; tip taken out of the merchant's share refused; tip on a bill refusing tips; below / above the published range; absurd; zero; negative; whole transfer is tip; tip larger than the transfer; presets do not bind without a range; percentage taken against the merchant's share; percentage basis on an editable amount |
| Currency & rail | a currency not offered, a destination address never published, blockchain data on a bank rail |
| Lifecycle | second pre-payment, payment on a CANCELLED code, post-payment with no pre-payment (reserves, flagged), and a refused notification leaving the QR Code ACTIVE |
| **A payee's answers** (`test_payee_answers.py`) | the service notifies the test payee (`x9client.peer`), which captures the exact JWS and answers in each of the 17 ways of Story 3.5 (signed yes, wrong or missing `correlationId`, a replayed yes, empty, unsigned, not a JWS, altered, untrusted, revoked, expired or future `iat`, `alg: none`, unknown `crit`, a signed status that differs from the HTTP one, a plain or signed 400, no answer); each is reported as `signature` VERIFIED / ABSENT / INVALID with its fixed `signatureProblem`, and changes nothing here. Every tamperer defect is named by its code |
| **The transition map** (`test_transition_map.py`) | one test per row of ADR-0035's map, keyed `X9-MAP-NNN` (the row-to-test table is in [the ADR](../../docs/adr/0035-one-event-builder-and-a-written-transition-map.md#verification-against-java-story-41-2026-11-14)): the resulting status, every event for the QR Code exactly and in order, what did not change, and each event body from the last stored notification, else the first payment method. Cells Java contradicts are strict expected failures carrying the row key. `test_every_map_row_has_a_test` checks the ADR's keys against the tests |
| **Time edges** (`test_time_edges.py`, serial) | plan §4.2 T1–T12 on the test clock, keyed `X9-TIME-NNN`: each edge refused 1 ms after it (exact: the clock never reads earlier than it was set) and accepted just before it (within 2 s, proved by timing the request; a slow attempt is retried, never read either way); the discount and the late fee to the millisecond, exactly at E and at `dueDate` through `dateForPayment`; `validUntil` of the code and of one method; the reservation's lapse and its cap; JWS freshness; T11 per build (Java's TTL deletion waits real time, ~90 s); T12 retention `go_only`; and the payee's clock deciding when it runs ahead of the payer's |
| **Payment requests** (`test_payment_requests_pinned.py`) | Story 4.2, keys `X9-*-42N`: create refusals beyond `contract.py` (network and currency by name, peg groups, duplicates, `validUntil`/`dueDate` edges, contradictory tip offers, negative and 19-digit amounts, the tag-26 host limit); PATCH (fields never applied, no-ops, adding a currency, status moves keep the revision); the ETag and every `If-Match` form, stale revision and status, a lapsed reservation; location rebind by creditor and holder state, with no event from the release |
| **Status update and the event stream** (`test_status_update_and_events.py`) | what the map does not reach (Story 4.4): `PAID` after `validUntil` and after a lapsed reservation, `CANCELLED` final through every door, a status-update reservation no payer can adopt (until it lapses), a stale `If-Match` by revision and by status; and the stream: exactly one event per transition in per-QR order, no gap across pages, `hasMore`, `wait` held and bounded, replay from an old cursor safe by `eventId`. Safe beside other tests writing events: each filters by its own QR Codes, and long polls wait past the end of any stream |
| **Signatures** (`test_signatures.py`) | payers A and B on their own keys; wrong key, untrusted CA, revoked certificate, expired `iat + ttl`, `iat` in the future, missing or unknown `crit`, missing `x5c`, altered payload, `alg: none`, each a signed 401 naming its fixed `signatureProblem` code; B cannot take or release A's reservation; every answer verified against the payee's certificate, echoing the question's `correlationId` (fresh when the question could not be verified); the same signed question twice |
| **Golden sets** (`test_golden.py`, Story 4.7) | the fixtures in [`golden/`](golden/README.md), exported from Java for the Go build's `internal/jws` and `internal/emv`: 79 JWS files (valid, tampered, expired, future, `crit`, `typ`, `x5c`, revoked, untrusted, RSA and EC, Java's signed yes and refusals) and 24 EMV strings under four hosts, one of 37 characters. Offline: the set is whole, holds no private key, and a second implementation agrees. Against Java: each case, with the clock moved to its `verifyAt`, gets the manifest's verdict and `signatureProblem`; each EMV string is read by Java and re-encoded to the same characters |
| **Payers** (`test_payers.py`, Story 4.3) | the payload: unknown location, content not byte-equal, every signature defect, revoked by CRL and by OCSP (`x9client/ocsp.py`), unreachable revocation sources soft-failing, no source at all, an expired method dropped, PAID and CANCELLED. Notifications: rail and currency in another case echoed, no post-commit marks PAID, no `paymentNotification` or `kind: EXTERNAL`, every answer signed, 32 concurrent pre-commits reserving once. The payer-side endpoints: a payee's no reported as 200, an unreachable payee 502, a `transactionId` refused before sending. The module docstring maps the rest of plan groups C, E and H to the tests that already pinned them |

## One suite: `acceptance.py` was folded in

`others/acceptance/acceptance.py` (a standard-library script that signed through the service under
test) and `make acceptance` are gone (Story 3.4). Each of its sections is now a test module here,
signed by independent payers:

| Section of `acceptance.py` | Module |
|---|---|
| 0. The deployment is up | `test_deployment.py` |
| 1. A generated QR Code is correct (EMV, CRC, tag 26, `qrCodeB64`, location) | `test_qr_code.py` |
| 2. A payer can fetch the signed payload | `test_payload.py` |
| 3. A payer notifies the payee (two phases) | `test_two_phase.py::TestTwoPhases` |
| 4. The event stream reports it | `test_two_phase.py::TestTheEventStream` |
| 5. Settlement is the payment system's call | `test_settlement.py` |
| 6. It refuses what it cannot honour, by name | `test_create_refusals.py` |
| 7. Public signing metadata is served | `test_public_metadata.py` |
| 8. Conditional requests and what a revision counts | `test_conditional_requests.py` |
| 9. A tip is money on top of the bill | `test_tip_offers.py` |
| 10. A patch names every currency, or none of them | `test_patch_currencies.py` |
| 11. A location handed over goes to somebody | `test_location_handover.py` |

The publish gate (`make publish`) runs `make blackbox` and nothing else.

### Against TLS and Kubernetes

- **A self-signed certificate:** `SSL_CERT_FILE=/path/to/cert.pem make blackbox` with
  `X9_BASE_URL=https://host`. Python then trusts that certificate; TLS is still verified.
- **On Kubernetes, `/actuator/health` may be DOWN for a reason unrelated to X9.150.**
  `SPRING_CLOUD_KUBERNETES_ENABLED=false` does not unregister the Kubernetes health indicator; it
  calls the API server without RBAC, fails, and drags the aggregate to DOWN while MongoDB and the
  liveness and readiness states are UP. The suite falls back to `/actuator/health/readiness`; the
  flag that removes the indicator is `MANAGEMENT_HEALTH_KUBERNETES_ENABLED=false`.

## The contract writes the boring tests

`contract.py` reads `openapi.yaml` and generates one case for every `maxLength`, `minLength`,
`pattern` and `required` in the JSON request schemas (create, PATCH, status-update, the payer-side
pre/post-payment, signature validation, the EMV decoder): a sample body the service accepts, with
one field broken. `tests/test_contract.py` asserts 400, the field's path in the violations, and
that nothing changed. Add a field with a `maxLength` to the contract and its refusal test exists.

```bash
python3 others/blackbox/contract.py        # list the cases, send nothing
```

A constraint the service does not enforce is listed in `contract-known-gaps.yaml` with the reason;
it still runs, as a strict expected failure, so it turns red the day it starts being enforced.
Every run ends with one line: `contract.py: N cases generated, N executed (G known gaps, F failed),
S skipped`.

## One test pins behaviour rather than endorsing it

`test_on_an_editable_amount_the_percentage_is_against_the_face_amount` records that with a face
amount of 1000 editable to 500..2000 and a tip range of 0..25%, a payer who chooses 2000 and tips
400 — 20% of what they are actually paying — is **refused**, because 400 is 40% of the face amount.

Whether the ceiling should follow the biller's reference figure or the payer's chosen one is a
business question and is **not settled**. The test exists so the answer cannot change by accident.

## Proving it can fail

Against `git-1ddaf6c` all 34 pass. Against `git-8e445b4`, which predates the tip rules entirely,
**10 fail** and each names its reason — including the one that matters most:

```
test_a_tip_taken_out_of_the_merchants_share_is_refused
    AssertionError: expected a refusal, got 200
```

That is the money-losing case: the bill satisfied on paper, the merchant short by exactly the tip.
