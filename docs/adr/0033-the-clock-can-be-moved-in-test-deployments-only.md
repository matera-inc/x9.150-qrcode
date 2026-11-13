# 33. The clock can be moved in test deployments, and only there

Date: 2026-11-13

## Status

Accepted 2026-11-13. Serves [0030](0030-a-black-box-suite-defines-done-and-migration-writes-only-runtime-valid-rows.md).
Applies to **both** builds, because the suite must pass against Java first.

## Context

Many of the rules worth testing are about time:

- discount and late-fee windows (`FormulaService`);
- QR Code and payment-method `validUntil`;
- the 90-second reservation lapse (ADR-0019);
- JWS `iat + ttl`;
- retention.

The scenarios that matter cross a boundary between two calls: fetch a payload just before a
discount stops applying, then notify a payment just after.

**There is a subtlety in today's code.** Eligibility for a discount ends at the instant
`dueDate − daysBefore` (`FixedDiscountLateFeeLinearInterestFormulaService.java:59`). The `validUntil`
advertised for the discounted amount is 23:59:59 UTC of that day (line 91). The two can be about 21
hours apart. The suite has to test both edges, not assume one.

**The ways around a real clock fail:**

- **Waiting for real time:** one attempt a day.
- **`libfaketime`:** it reaches the JVM but not a static Go binary.
- **Linux time namespaces:** they never shift the wall clock.

**Java reads "now" in four places:**

- `DateTimeUtils.nowUTC()` (15 call sites);
- two `Clock.systemUTC()` reads in the JWS service;
- `OffsetDateTime.now` in `RetrieveQRCodePayloadInput.java:24`.

## Decision

**Each build reads business time from one clock. In a test deployment that clock can be offset over
HTTP. Nowhere else can it be enabled.**

**One clock.** Every decision that depends on "now" asks the same clock:

- business rules, in all four Java places;
- JWS `iat` and `ttl`, issuing and verifying;
- reservation stamps;
- timestamps written to the database, computed in the application;
- retention cut-offs.

Request logging and lease timers may use the real clock. In Go, lint forbids the package-level
`time.Now`, `time.Since`, `time.Until`, `time.After`, `time.Tick` and `time.NewTimer` outside
`internal/clock`, except where those helpers measure real durations and say so.

**The clock is shifted by an offset, so it keeps ticking.** It is never frozen. Long-polls,
timeouts and reservation lapses then behave naturally.

**The control surface:**

- `PUT /test/clock` with `{"at": "…"}` or `{"offsetSeconds": n}`;
- `DELETE /test/clock`;
- `GET /test/clock` returns `{"now", "offsetSeconds"}`. The suite's payer signer uses it for `iat`.

**Guards:**

- **Off by default.** It is enabled by `X9_TESTING_CLOCKCONTROL=true`.
- **Never on the public port.** The routes live on a separate listener, with an address
  configured by `X9_TESTING_CLOCKLISTEN`: `127.0.0.1:8099` by default; CI may bind it to the test
  network. They are not in `openapi.yaml`.
- **Refuses to start** with the flag on unless the signing certificate's SHA-256 fingerprint is one
  of the bundled demo or test-CA fingerprints **compiled into the binary**. A subject name or a
  configuration value does not satisfy it.
- **Visible:** a startup `WARN`, and an `X-Test-Clock-Offset` header on every API response while the
  offset is non-zero.

**Java gets the same surface in plan phase 1,** for all four reads. It is the last functional change
before Java is frozen, together with the ETag fix ([0028](0028-the-http-contract-is-frozen-and-equivalence-is-proved-by-a-differential-run.md)).

**MongoDB's TTL monitor runs on the real clock.** So on Java a shifted clock never deletes an
expired document. Scenarios that depend on deletion (expired → 404) are asserted on Java by waiting
real time, or marked Java-only. They are not driven by the test clock.

## Consequences

- Every time-window rule becomes testable at its exact edges, in CI, in seconds.
- Time scenarios run serially in their own stage. Each ends by resetting the clock.
- A production deployment cannot move its clock. Real keys make start-up fail.

## Alternatives rejected

- **A per-request clock header.** It lets any caller choose the time a QR Code is judged at.
- **Routes on the API port behind the flag alone.** One misconfiguration away from production.
- **A frozen clock.** Long-polls and lapses stop working.
- **Unit tests only.** They prove a formula, not the behaviour across two HTTP calls.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
