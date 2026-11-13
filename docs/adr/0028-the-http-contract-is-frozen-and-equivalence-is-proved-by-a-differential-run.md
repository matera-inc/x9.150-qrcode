# 28. The HTTP contract is frozen, and equivalence is proved by a differential run

Date: 2026-11-13

## Status

Proposed. Part of [0022](0022-rewrite-in-go-as-a-standalone-product.md).

## Context

`openapi.yaml` (contract `0.6.0-rc`) is consumed by payer PSPs (`/pub`), billers (`/api/v1`) and
event consumers.

"The same contract" is more than the schema. Java's output also comes from framework defaults that a
Go struct with JSON tags does not reproduce:

- **Jackson's mapper** (`ObjectMapperFactory.java:66-67`, primary bean
  `RestBaseConfiguration.java:31`):
  - `INDENT_OUTPUT`;
  - `NON_NULL`, which **keeps** `0`, `false` and `[]`, all of which Go's `omitempty` drops.
- **400 bodies** carry `violations` with Bean Validation's default messages
  (`GlobalControllerAdvice.java:70-73`).
- **Jackson matches JSON keys case-sensitively,** while Go's `encoding/json` matches them
  case-insensitively.
- **`If-Match` parsing** strips `W/` and quotes.
- **The ETag is `"<revision>-<STATUS>"`.** It is built from the *effective* status on GET but
  compared against the *stored* status on `If-Match`. That is a **Java bug today**: once a
  reservation lapses, every conditional request answers 412.
- **Limits:** request headers up to 30 KB (`application.yml:10`).

A schema-validating test catches none of these.

## Decision

**The external contract does not change in the rewrite, and equivalence is proved, not asserted.**

**Equivalence = a differential run.** The black-box suite
([0034](0034-the-black-box-suite-is-python-in-this-repository-under-its-licence.md)) runs against
Java and against Go on the same inputs, and **normalised responses are diffed**. Normalising means
re-serialising JSON (ignoring whitespace and key order) and masking ids, times and signatures.

- **The status code, the headers that matter** (`ETag`, `Content-Type`, `Location`) **and every
  normalised body must match.**
- Each tolerated difference is written in `others/blackbox/TOLERATED-DIFFERENCES.md`, with its
  reason.
- A difference not on that list fails the build.

**Behaviours the Go build reproduces:**

- `NON_NULL` semantics: a value is omitted only when null, never because it is zero, false or empty;
- the `violations` array with the same field paths and messages;
- **case-sensitive** key matching on input;
- `If-Match` parsing;
- the 30 KB header limit;
- problem `type` URIs unchanged.

**Pretty-printed output is not reproduced.** Whitespace is not part of a JSON contract, and signed
payloads are verified over the bytes actually signed. This is tolerated difference 1.

**The ETag is fixed in both builds.** One function, `ETag(row, now)`, uses the effective status, and
`If-Match` compares against the same function. The Java fix lands in phase 1 so that the suite can
pin it.

**Additions wait until after cutover:** `errorCode` in problems, `Idempotency-Key`, and any other
contract improvement. They ship later, under the contract's own versioning policy.

**Outside the contract:** the test clock ([0033](0033-the-clock-can-be-moved-in-test-deployments-only.md))
lives on a separate listener and is never in `openapi.yaml`.

**Intended differences, listed and tolerated:**

1. **Whitespace.**
2. **An expired QR Code answers with its expiry reason instead of 404.** Java deletes the document
   30 s after `validUntil`; Go retains it ([0029](0029-time-is-applied-on-read-and-retention-is-the-only-job.md)).
   Asserted per build.

**Where a house rule conflicts with the contract, the contract wins:**

- amounts are JSON numbers (X9.150 §2.1);
- 502 means payee unreachable (ADR-0015).

## Consequences

- Integrators notice nothing, and the differential run is the evidence.
- The Java build gets one deliberate fix (the ETag) before it is frozen.
- Contract improvements found during the rewrite are queued, not mixed in.

## Alternatives rejected

- **Byte-identity checked by schema validation.** It catches nothing that matters here.
- **Reproducing Jackson's indentation.** It costs effort for bytes no conformant client reads.
- **Generating the server from OpenAPI.** It brings back a generated DTO layer.
- **Shipping `errorCode` and `Idempotency-Key` during the port.** Every regression would become
  ambiguous.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
