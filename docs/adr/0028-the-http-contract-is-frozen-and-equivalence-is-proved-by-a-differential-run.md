# 28. The HTTP contract is frozen, and equivalence is proved by a differential run

Date: 2026-11-13

## Status

Accepted 2026-11-13. Part of [0022](0022-rewrite-in-go-as-a-standalone-product.md).

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
  compared against the *stored* status on `If-Match`. That was a **Java bug**: once a
  reservation lapsed, every conditional request answered 412. Fixed in Java by Story 2.2
  (`QRCodeEntity.entityTag(at)` builds the tag for GET and for every `If-Match` comparison).
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

### Amendment, 2026-11-14: a second deliberate Java fix, the signed answer to a payment notification

**Open until now:** this ADR allowed one change to the Java contract before the freeze, the ETag.
`POST /pub/api/v1/payment-notification` answered an accepted notification with an empty, unsigned
200 and a refusal with plain JSON, while the note under ANSI X9.150 A.4.5 asks for a signed
`{"statusCode": integer}`. The outbound sender passed the payee's answer through unverified.

**Closed (Story 2.3, FR33):** the response body of that endpoint changes, in Java first, so that the
differential run sees both builds agree.

- Every answer is a compact JWS, `Content-Type: application/jose`, signed with the payee's X9 key.
  A yes has body `{"statusCode": 200}`. A refusal keeps its HTTP status (400, 401, 404, 409, 422)
  and its error fields (`type`, `title`, `status`, `detail`, `violations`, and any other property
  such as `currentStatus`), with `statusCode` added.
- The answer's `correlationId` echoes the notification's once the notification's signature has
  verified; a notification that cannot be verified is refused under a fresh `correlationId`. The
  answer's `iat` is the payee's clock at the decision.
- `OutboundPaymentNotificationResult` gains `signature` (`VERIFIED` / `ABSENT` / `INVALID`) and
  `signatureProblem`, additively. A 200 that is not verified is still reported as `accepted`: an
  unsigned yes.

So the Java build gets **two** deliberate fixes before it is frozen: the ETag and the signed answer.

### Note, 2026-11-15 (owner): Java's known breaks are not compared

Where Java is known to break X9.150 or an ADR (an **Open** entry in
[`known-bugs.md`](../../known-bugs.md), or a black-box test marked as an expected failure on Java),
that behaviour is excluded from the Java↔Go differential comparison, and Go follows the spec.

### Known differences between the builds (owner, 2026-11-15)

Answers the Go build gives and the Java build does not, each where Go follows the standard, an ADR
or the contract. Epic 11's `others/blackbox/TOLERATED-DIFFERENCES.md` takes them over, with their
test keys, when it is written; until then this list is where they are kept. A black-box test of one
of them is `go_only`, or a strict expected failure on Java.

| # | Where | Java | Go | Why | From |
|---|---|---|---|---|---|
| 3 | `GET /pub/api/v1/events?after=` past the end of the log | an empty page (the cursor is compared as a string) | **409 "cursor ahead of log"**, carrying the current `last_seq` | A cursor ahead of the log means the consumer is reading the wrong deployment or a restored database; an empty page would hide it ([ADR-0031](0031-same-image-lighter-and-3-0-is-a-configuration-migration.md)) | Story 10.1 |
| 4 | `GET /pub/api/v1/events?after=` that is neither empty, a decimal `seq`, nor a ULID | compared as a string: an empty page, or whatever sorts after it | **400 naming `after`** | A malformed cursor is the consumer's bug, and it should hear about it | Story 10.1 |
| 5 | Any unexpected failure | Spring Boot's error body (only the signed notification answer uses `internal-error`) | **every 500 is the `internal-error` problem**, with no stack, SQL or internal detail | One shape for "our fault" | Story 9.2 |
| 6 | A JSON request body over 1 MiB | read | **400 `malformed-json`** | The largest legitimate body is a few kilobytes; the cap bounds memory | Story 9.2 |
| 7 | JSON edge cases Jackson lets through: a lone surrogate escape (`"\ud800"`), a date written as an array (`[2030,1,10]`) | read | **400 `malformed-json`** | Go's decoder is stricter; a body a strict parser refuses is not one a payer can rely on elsewhere | Story 9.2 |
| 9 | `POST /api/v1/payment-requests` (create) | no `ETag` header | **`ETag: "0-ACTIVE"`** | The first PATCH can send `If-Match` without a GET first ([ADR-0016](0016-a-revision-is-a-version-of-the-request-not-of-its-status.md)) | Story 9.6 |
| 10 | A database statement timeout while serving the signed payload | the generic 400 (every exception is caught) | **503** | "Try again" is not "your request is wrong" ([ADR-0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md)) | Story 10.3 |
| 11 | `GET /pub/.well-known/certificate/{name}` with a name other than `x9-key.pem` | 200 with the certificate, whatever the name | **404** | Only the name every `x5u` gives is published | Story 10.2 |
| 12 | `POST /api/v1/signature/generate` with the nil UUID as `Correlation-Id` | signed | **400**, the missing-`correlationId` refusal | The nil UUID identifies no exchange | Story 10.2 |
| 13 | Number spelling in the body `signature/generate` signs | Jackson's (`1e2` becomes `100.0`) | **as sent** (`1e2`) | Both are the same JSON number; a token from either build verifies on the other | Story 10.2 |
| 14 | A blank `transactionId` (`""`, spaces) on an outbound notification | signed verbatim | **left off the wire** | A blank is never a reference, and the phase is read from its absence ([ADR-0004](0004-phase-inferred-from-transaction-id.md)) | Story 10.6 |
| 15 | The payee's answer to an outbound notification, over 1 MiB | read | **502** | A signed `{"statusCode": n}` is a few kilobytes; the cap bounds memory | Story 10.6 |

Numbers 1 and 2 are the intended differences above. Row 8 (an unknown field in `fednow`, `rtp`
or `ach` on create or PATCH: ignored by Java, refused by Go) left this list on 2026-11-15, when the
Java build closed the bank rails too, and then every level of what it generates (INTERPRETATION
I-16); the two builds now refuse the same unknown fields in the same words. Formerly: row 8 was a
difference only until the Java
build closes the bank rails as well; then it leaves this list.

## Alternatives rejected

- **Byte-identity checked by schema validation.** It catches nothing that matters here.
- **Reproducing Jackson's indentation.** It costs effort for bytes no conformant client reads.
- **Generating the server from OpenAPI.** It brings back a generated DTO layer.
- **Shipping `errorCode` and `Idempotency-Key` during the port.** Every regression would become
  ambiguous.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
