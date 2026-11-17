# Black-box suite — the unhappy paths

Systematic coverage of **every way a payment notification can be wrong**, driven through the
published API of a running deployment. Nothing here imports the service.

This complements the Go tests rather than replacing them. A Go test proves a rule in isolation
and fast; this proves the rule survives serialisation, validation *order*, HTTP status mapping and
the signature layer — which is where several of this repo's real defects lived, each of them
invisible to a unit test that was passing at the time.

> **The Java build was retired at 3.0.0** ([ADR-0039](../../docs/adr/0039-java-retired-at-3-0-0.md)).
> The suite was written against Java first, then ran against both builds, and the differential run
> (below) diffed their answers on every pull request. That run ended with Java's retirement: the
> suite now runs against the Go build only. `differential.py`, the recording code and
> [TOLERATED-DIFFERENCES.md](TOLERATED-DIFFERENCES.md) stay as the record of how equivalence was
> proved, and the comparison can still be re-run against the `materainc/x9-qrcode:java-final`
> image with the suite of the git tag `java-final`, if a doubt ever needs it.

## Running it

```bash
make blackbox                                  # against http://localhost:8080
uv run others/blackbox/run.py                  # the same, with no virtualenv (PEP 723 header)
X9_BASE_URL=http://localhost:8070 uv run others/blackbox/run.py -k signatures
```

Two pinned dependencies (`requirements.txt`, and the same pins in `run.py`'s header): pytest,
and `cryptography`, because the suite signs as independent payers and the standard library cannot
make an RSA or EC signature (ADR-0034). HTTP stays on the standard library. `make blackbox-venv`
builds `.blackbox-venv`; `requirements-db-postgres.txt` adds the optional database inspector.

If nothing answers at `X9_BASE_URL`, the suite **skips with a reason** rather than failing —
but the publish gate treats a suite that could not run as a refusal to publish.

### In CI, and against any image

```bash
make image && make blackbox-image                 # the image `make image` built
X9_IMAGE=materainc/x9-qrcode:git-<sha> ./others/blackbox/deployment/run-against-image.sh
```

`run-against-image.sh` runs the whole suite in containers (`deployment/docker-compose.yml`):
PostgreSQL 16 on tmpfs; `db/local/bootstrap.sql` and then `db/changelog/` applied by the Liquibase
one-shot as the migration role, before the service starts; the image under test connected as the
application role, with the test clock on and the black-box truststore (`x9-blackbox-truststore.p12`)
copied in; no public host, so it runs on its default `x9-150.example.com`, which the golden EMV set
covers; and the suite in the service's network namespace, reading the database as the read-only
role, so the service reaches the suite's test payee and CRL server on `127.0.0.1`, and the suite
reaches the clock, which never leaves the service's loopback. Nothing is bind-mounted, so it works
on Colima. It sets `X9_REQUIRE_ALL=1` and `X9_DATABASE_URL`, so every test runs: a missing
capability, a service that never comes up, or zero tests collected fails the run. It refuses an
image that is not the Go build (whose entrypoint is `/x9-qrcode`). `X9_PLATFORM=linux/amd64` (or
`linux/arm64`) runs the service as that platform, the database and the suite staying native: the
publish gate checks each architecture this way.

Runs never share anything, so two can go at once. Each run takes its own compose project name and
tags its images with it: `X9_BLACKBOX_PROJECT` if you set it (lowercase letters, digits, `-`,
`_`), otherwise `x9-blackbox-` plus a random suffix, which the script prints first. On exit it
tears down only that project and removes only those tags; `X9_IMAGE` itself is never touched.

The verdict is **the whole suite, under `X9_REQUIRE_ALL=1`** (Story 10.8), and any failure fails
it. `X9-TIME-015` (retention, ADR-0029): the deployment keeps the default retentions (`P30D`,
`P400D`) and runs the job every 5 s, and `X9_EVENTS_RETENTION_DAYS`, `X9_QRCODE_RETENTION_DAYS`
and `X9_RETENTION_WAIT_SECONDS` describe that to the suite (`deployment/docker-compose.yml` sets
the service and the suite from the same values). It moves the test clock past both retentions, so
the job purges every earlier test's events and short-lived QR Codes: it is marked `runs_last` and
runs last in every session, whatever the file order (conftest.py; `X9-SUITE-531` checks it).

Until 3.0.0 some tests were marked `java_only` or `go_only`, and a few were strict expected
failures on Java where Java broke the standard or an ADR (`X9-LOC-421`, BUG-004). With one build
left, the markers are gone: every test runs, and asserts the standard.

The required-keys list that Epics 9 and 10 grew while the Go build was being written
(`deployment/go-required.txt`) is gone. `required_keys.py` stays as a tool for a partial run: point
`X9_REQUIRED_KEYS` at a list file of your own and only the tests it lists decide the exit code,
every other one reported as informative (entries are test keys or `tests/` node-id prefixes, `!`
excludes, an entry that matches nothing fails the run). No CI run sets it.

Before any test, the `api` fixture creates a QR Code and has payer A send it one pre-commit: the
**trust probe**, which turns a deployment that does not trust the test CA into one clear message
instead of a failure in every payer test.

The `blackbox-go` job (`.github/workflows/blackbox.yml`) builds the image with `make image` and runs
exactly this on every pull request that touches the code, `db/`, `api/` or `others/blackbox/`, and
fails it on any failure; on any other pull request it passes without running. It is a required
check. The publish gate (`make publish`) runs the same script against each architecture it is
about to push.

### Differential run: what Java answered against what Go answered (Stories 11.1, 11.2)

> **Ended at 3.0.0.** This section is the record of how the two builds were proved equivalent; the
> `differential` CI job is gone with the Java build. To re-run the comparison, check out the tag
> `java-final` (its `run-against-image.sh` still runs a Java image, on MongoDB) and record against
> `materainc/x9-qrcode:java-final` and a Go image, as below.

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

Until 3.0.0, in CI, the `blackbox` and `blackbox-go` jobs recorded and uploaded their recordings as
the artifacts `recording-java` and `recording-go`, and the `differential` job diffed them and
uploaded `differences.json`.

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

- **Keys** name the tests that show the difference. A Go test is keyed by the first words of its
  doc comment (`// X9-SIG-534 (...): ...`). Since 3.0.0 the file is a historical record and some
  of its keys named tests that existed only in the Java build, so `check_rule_coverage.py` no
  longer checks them.
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

- **trust the test CA.** Start it with `others/blackbox/deployment/x9-blackbox-truststore.p12`,
  which holds the test CA and the shipped demo certificate (so the deployment, still signing with
  the demo key, can verify itself when its decoder fetches its own payloads):

  ```bash
  X9_CERTIFICATE_TRUSTSTORE_LOCATION=$PWD/others/blackbox/deployment/x9-blackbox-truststore.p12
  X9_CERTIFICATE_TRUSTSTORE_PASSWORD=x9test123
  X9_CERTIFICATE_TRUSTSTORE_TYPE=PKCS12
  ```

  `build-truststore.sh` writes it, and the same two certificates as JKS
  (`x9-blackbox-truststore.jks`), which only the golden export uses, against the `java-final`
  image: the service refuses JKS.

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
| `X9_DATABASE_URL` | unset | Optional read-only database connection (`postgresql://…`). Database checks skip, with the reason, when it is unset or the driver (`psycopg`) is not installed. |
| `X9_REQUIRE_ALL` | unset | `1` turns every "capability missing" skip into a failure. CI sets it. |
| `X9_RECORD_DIR` | unset | Record every exchange of every test there (see "Differential run" above). `run-against-image.sh` copies the recording out of its container to this path. |
| `X9_PLATFORM` | unset | `run-against-image.sh` only: run the service as this platform (`linux/amd64`, `linux/arm64`). |
| `X9_EVENTS_RETENTION_DAYS`, `X9_QRCODE_RETENTION_DAYS` | `30`, `400` | The Go deployment's `events.retention` and `qrcode.retention`, in days, for `X9-TIME-015`. They describe the deployment; they do not configure it. |
| `X9_RETENTION_WAIT_SECONDS` | `90` | How long `X9-TIME-015` waits for a retention run: the deployment's `retention.interval` plus slack (`deployment/docker-compose.yml` runs the job every 5 s and sets 30). |
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
│   ├── db/            the optional read-only inspector: postgres.py
│   ├── recording.py   with X9_RECORD_DIR, every exchange written down per test (differential run)
│   ├── samples.py     request bodies a scenario starts from
│   └── checks.py      assert_refused: refused, and for the right reason
├── tests/             the scenarios
├── contract.py        a refusal case per maxLength/minLength/pattern/required/maximum/minimum, and the edge of each bound
├── contract-known-gaps.yaml   constraints the service does not enforce today, each with its reason
├── deployment/        the compose file, run-against-image.sh, the truststore (PKCS#12; JKS for the golden export)
├── golden/            JWS and EMV fixtures exported from Java, with their verdicts (Story 4.7)
├── conftest.py        fixtures: deployment, api, events, clock, db, pki, payer_a, payer_b, tamperer
├── required_keys.py   an optional required-keys list for a partial run (X9_REQUIRED_KEYS)
├── recorder.py        tells the recorder which test (or shared fixture) is running
├── differential.py    the diff of two recordings, Java against Go (Story 11.1; ended at 3.0.0)
├── TOLERATED-DIFFERENCES.md   every difference ADR-0028 accepted, with its matcher (Story 11.2; historical)
├── run.py             `uv run` entry point (PEP 723)
└── pytest.ini         markers: serial, runs_last
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
| **The transition map** (`test_transition_map.py`) | one test per row of ADR-0035's map, keyed `X9-MAP-NNN` (the row-to-test table is in [the ADR](../../docs/adr/0035-one-event-builder-and-a-written-transition-map.md#verification-against-java-story-41-2026-11-14)): the resulting status, every event for the QR Code exactly and in order, what did not change, and each event body from the last stored notification, else the first payment method. (Cells the Java build contradicted were strict expected failures until 3.0.0; none is left.) `test_every_map_row_has_a_test` checks the ADR's keys against the tests |
| **Time edges** (`test_time_edges.py`, serial) | plan §4.2 T1–T12 on the test clock, keyed `X9-TIME-NNN`: each edge refused 1 ms after it (exact: the clock never reads earlier than it was set) and accepted just before it (within 2 s, proved by timing the request; a slow attempt is retried, never read either way); the discount and the late fee to the millisecond, exactly at E and at `dueDate` through `dateForPayment`; `validUntil` of the code and of one method; the reservation's lapse and its cap; JWS freshness; T11, an expired QR Code kept and its location releasable; T12 retention, run last; and the payee's clock deciding when it runs ahead of the payer's |
| **Payment requests** (`test_payment_requests_pinned.py`) | Story 4.2, keys `X9-*-42N`: create refusals beyond `contract.py` (network and currency by name, peg groups, duplicates, `validUntil`/`dueDate` edges, contradictory tip offers, negative and 19-digit amounts, the tag-26 host limit); PATCH (fields never applied, no-ops, adding a currency, status moves keep the revision); the ETag and every `If-Match` form, stale revision and status, a lapsed reservation; location rebind by creditor and holder state, with no event from the release |
| **Status update and the event stream** (`test_status_update_and_events.py`) | what the map does not reach (Story 4.4): `PAID` after `validUntil` and after a lapsed reservation, `CANCELLED` final through every door, a status-update reservation no payer can adopt (until it lapses), a stale `If-Match` by revision and by status; and the stream: exactly one event per transition in per-QR order, no gap across pages, `hasMore`, `wait` held and bounded, replay from an old cursor safe by `eventId`. Safe beside other tests writing events: each filters by its own QR Codes, and long polls wait past the end of any stream |
| **Signatures** (`test_signatures.py`) | payers A and B on their own keys; wrong key, untrusted CA, revoked certificate, expired `iat + ttl`, `iat` in the future, missing or unknown `crit`, missing `x5c`, altered payload, `alg: none`, each a signed 401 naming its fixed `signatureProblem` code; B cannot take or release A's reservation; every answer verified against the payee's certificate, echoing the question's `correlationId` (fresh when the question could not be verified); the same signed question twice |
| **Golden sets** (`test_golden.py`, Story 4.7) | the fixtures in [`golden/`](golden/README.md), exported from Java for the Go build's `internal/jws` and `internal/emv`: 79 JWS files (valid, tampered, expired, future, `crit`, `typ`, `x5c`, revoked, untrusted, RSA and EC, Java's signed yes and refusals) and 24 EMV strings under four hosts, one of 37 characters. Offline: the set is whole, holds no private key, and a second implementation agrees. Against the deployment: each case, with the clock moved to its `verifyAt`, gets the manifest's verdict and `signatureProblem`; each EMV string is read and re-encoded to the same characters (the `test_java_*` names are from when the deployment was Java) |
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

The publish gate (`make publish`) runs `run-against-image.sh` against each architecture, after the image smoke test.

### Against TLS and Kubernetes

- **A self-signed certificate:** `SSL_CERT_FILE=/path/to/cert.pem make blackbox` with
  `X9_BASE_URL=https://host`. Python then trusts that certificate; TLS is still verified.
- **On Kubernetes, the suite falls back to `/actuator/health/readiness`** when `/actuator/health`
  is not 200. (The Java build's aggregate could be DOWN for a Kubernetes health indicator unrelated
  to X9.150; the Go build has none, but the fallback stays.)

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
