# Black-box suite — the unhappy paths

Systematic coverage of **every way a payment notification can be wrong**, driven through the
published API of a running deployment. Nothing here imports the service.

This complements the JUnit tests rather than replacing them. JUnit proves a rule in isolation and
fast; this proves the rule survives serialisation, validation *order*, HTTP status mapping and the
signature layer — which is where several of this repo's real defects lived, each of them invisible
to a unit test that was passing at the time.

## Running it

```bash
make blackbox                      # against http://localhost:8080
X9_BASE_URL=http://localhost:8070 pytest others/blackbox
```

Zero dependencies beyond pytest itself, deliberately: a suite that fails to install is a suite
that silently does not run. `make blackbox-venv` builds `.blackbox-venv` if you would
rather not install pytest globally.

If nothing answers at `X9_BASE_URL`, the suite **skips with a reason** rather than failing —
but the publish gate treats a suite that could not run as a refusal to publish.

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
│   ├── db/            optional read-only inspectors: mongo.py (Java), postgres.py (Go)
│   ├── samples.py     request bodies a scenario starts from
│   └── checks.py      assert_refused: refused, and for the right reason
├── tests/             the scenarios
├── conftest.py        fixtures: api, events, clock, db
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
| Lifecycle | second pre-payment, payment on a CANCELLED code, post-payment with no pre-payment, and a refused notification leaving the QR Code ACTIVE |

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
