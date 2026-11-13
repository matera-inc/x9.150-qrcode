# 34. The black-box suite is Python, in this repository, under its licence

Date: 2026-11-13

## Status

Accepted 2026-11-13. Implements [0030](0030-a-black-box-suite-defines-done-and-migration-writes-only-runtime-valid-rows.md) and
uses [0033](0033-the-clock-can-be-moved-in-test-deployments-only.md).

## Context

[0030](0030-a-black-box-suite-defines-done-and-migration-writes-only-runtime-valid-rows.md) makes an
outside-the-service suite the definition of done for the rewrite. PLAN §4 catalogues what it must
cover: create refusals, PATCH limits, JWS tampering, the time edges, concurrency, and events.

Two suites exist today:

- **`others/blackbox/`:** pytest, 52 tests on payment-notification unhappy paths, with pytest as
  the only dependency (HTTP through the standard library). It signs payer messages by calling
  the service's own `POST /api/v1/signature/generate`.
- **`others/acceptance/acceptance.py`:** a standard-library script, 8 sections (EMV CRC, JWS,
  events, ETag…).

Both run by hand (`make blackbox`, `make acceptance`) and as a publish gate, not in CI.

Two gaps stop them carrying the catalogue:

- **Payer messages are signed by the service under test,** with its own key. Refusal cases need
  signatures the service would never produce: a revoked or untrusted certificate, an expired
  `iat + ttl`, a missing or unknown `crit`. The standard library cannot produce RSA or EC
  signatures.
- **Nothing is verified below HTTP.** "A refused call left nothing behind" and "a PATCH wrote one
  audit row" cannot be observed through the API.

A proprietary JSON-scenario runner was considered. Being proprietary, it would put two licences
in one repository.

## Decision

**The suite is Python, lives in this repository, and is under the Matera Source License like
everything else here.** Anyone who downloads the product can read, run and extend the suite that
defines its behaviour, and can test their own integration with it.

**pytest is the backbone, but not a cage.** Tests are pytest. Dedicated Python code that
complements them is welcome where pytest is the wrong shape: a protocol helper, a scenario
generator, a concurrency driver, a two-instance cycle.

**Layout:**

```
others/blackbox/
├── x9client/          the only code that knows the protocol
│   ├── api.py         HTTP client; ETag / If-Match; problem+json parsing
│   ├── jws.py         independent payer signer on the test CA (PKCS#12/PEM), X9.150 profile
│   │                  (ms iat/ttl, correlationId, crit, x5c), and a tamperer for every refusal case
│   ├── emv.py         TLV + CRC16 decode
│   ├── clock.py       test-clock control (ADR-0033): move_to(), now(), reset()
│   ├── events.py      cursor reader with dedupe
│   └── db/            optional build-specific inspectors: mongo.py (Java), postgres.py (Go)
├── contract.py        generates field refusal cases from openapi.yaml (maxLength, minLength, pattern, required)
├── tests/             one module per catalogue group (PLAN §4.1 A–H); time edges marked `serial`
└── conftest.py
```

**Rules:**

- **Assert through the API first.** Database checks only cover what HTTP cannot show: the audit
  log, "nothing was written", "exactly one event". They run only when `X9_DATABASE_URL` is set,
  through the **read-only** role of [0032](0032-x9150-can-live-in-the-banks-own-database.md), and are skipped with a stated reason otherwise. Build-specific
  checks are marked `java_only` or `go_only`.
- **The contract writes the boring tests.** Every `maxLength`, `minLength`, `pattern` and `required`
  in `openapi.yaml` produces its refusal case automatically. A new field gets its tests without
  anyone typing them.
- **Every scenario asserts what did not happen** (no status change, no event, no reservation), not
  only the status code. This is the existing suite's house rule, kept.
- **Time scenarios run serially,** in their own stage, and reset the clock (ADR-0033).
- **Load is not this suite's job.** Correctness under contention is (threads). Throughput and
  latency belong to k6 (PLAN §9).

**Dependencies.** The suite's "pytest only" rule is relaxed deliberately. Each addition is pinned,
and is optional where it can be:

| Dependency | Needed for | Optional? |
|---|---|---|
| `pytest` | everything | no |
| `cryptography` | independent JWS signing and tampering | no; signature refusals cannot be tested without it |
| `psycopg` | PostgreSQL inspector (Go) | yes, extra `db-postgres` |
| `pymongo` | MongoDB inspector (Java, until it is frozen) | yes, extra `db-mongo` |

HTTP stays on the standard library. A PEP 723 header lets `uv run` resolve the dependencies with
no virtualenv ceremony, and `make blackbox` keeps working.

**Runs in CI** against the built image with PostgreSQL in compose, not only at publish. The publish
gate stays.

**`acceptance.py` is folded in** section by section. It is deleted once its last section exists as
tests.

## Consequences

- **One licence and one language for the suite, and nothing hidden.** The proof of the product's
  behaviour ships with the product.
- **No upstream dependency on the critical path:** phase 1 starts on the existing 52 tests.

- **Java events become visible about 1 s after the transition** (the drain), so Java-side
  database checks on events retry briefly. On Go they are visible at commit (ADR-0025), so they
  can be checked at once.

## Alternatives rejected

- **A proprietary JSON-scenario runner.** It is mature, and adding a test means
  writing data. But it is proprietary (two licences in one repository, and opaque to the users the
  suite is meant to serve).
- **Keep "pytest only".** Signature refusals and below-HTTP checks would stay untestable, and they
  are where several of this repository's real defects lived.
- **Off-the-shelf API testers** (Postman/Newman, Karate, Hurl). None signs X9.150 JWS or moves a
  service clock without a plugin, which would be ours to maintain anyway.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
