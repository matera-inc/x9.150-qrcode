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

The `blackbox` job (`.github/workflows/blackbox.yml`) builds the image with buildpacks on every
pull request and runs exactly this. It is separate from the `build` job. The publish gate
(`make publish`) still runs `make blackbox` against each architecture it is about to push.

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
│   ├── samples.py     request bodies a scenario starts from
│   └── checks.py      assert_refused: refused, and for the right reason
├── tests/             the scenarios
├── contract.py        generates a refusal case per maxLength/minLength/pattern/required in openapi.yaml
├── contract-known-gaps.yaml   constraints the service does not enforce today, each with its reason
├── deployment/        the truststore a deployment under test runs with, and how it is built
├── conftest.py        fixtures: api, events, clock, db, pki, payer_a, payer_b, tamperer
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
| **Signatures** (`test_signatures.py`) | payers A and B on their own keys; wrong key, untrusted CA, revoked certificate, expired `iat + ttl`, `iat` in the future, missing or unknown `crit`, missing `x5c`, altered payload, `alg: none`, each a signed 401 naming its fixed `signatureProblem` code; B cannot take or release A's reservation; every answer verified against the payee's certificate, echoing the question's `correlationId` (fresh when the question could not be verified); the same signed question twice |
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
