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
- 502 means no answer was received from the payee (ADR-0015); the payee may still have received the
  notification (note of 2026-11-15 below).

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

### Note, 2026-11-15 (owner): one change to a frozen error text, the outbound 502

Java's error texts are frozen by this ADR. One is changed, in both builds at once: the `detail` of
the `notification-undeliverable` 502, answered when this service sends a payment notification to a
payee and receives no whole answer.

- **Was:** "The payee could not be reached, so nothing was delivered. Retry."
- **Now:** "The payee's answer was not received; the payee may have received the notification.
  Retrying is safe."

**Why:** the old text was false whenever the payee had the notification and its answer was lost,
came too late or was unreadable. A payer told "nothing was delivered" could act on a notification the
payee had in fact received. Retrying stays safe because a payee must tolerate the same notification
twice. The title, the `type` URI and the status are unchanged, so the differential run still sees the
two builds agree.

### Known differences between the builds (owner, 2026-11-15; moved 2026-11-16)

The table of answers the Go build gives and the Java build does not now lives in
[`others/blackbox/TOLERATED-DIFFERENCES.md`](../../others/blackbox/TOLERATED-DIFFERENCES.md)
(Story 11.2), entries 1 to 22 keeping the numbers they had here (8 is retired). In short: the Go
build refuses what Java lets through where the standard, an ADR or the contract says so (a cursor
ahead of the log or malformed, a body or a payee answer over 1 MiB, JSON edge cases, the nil UUID
as a `correlationId`, a certificate name other than `x9-key.pem`, a decoder URL with a non-numeric
port or a redirect), answers our faults as ours (every 500 is `internal-error`, a database timeout
is a 503, a payee's unreadable payload is the payee's 400), reads another bank's payload tolerantly,
signs what it was sent as it was sent, keeps an expired QR Code instead of deleting it, sends an
`ETag` on create and PATCH, and records the holder's `NOT_SENT` once a hash is registered. Each
entry gives the reason, the test keys that show it and the matcher the differential run applies; a
difference no entry matches fails the build, and so does an entry that no longer matches anything.
The first full diff (Story 11.1) added the `ETag` on PATCH to entry 9, `X9-MAP-043` to entry 16,
and entries 23, 24 and 26 (the Java `BUG-004`, the free wording of `signatureDetail`, which
concurrent payer wins). The owner accepted every remaining entry and the comparison of `violations`
as a set on 2026-11-16. Four differences were fixed instead of listed: the Go build's
`payer.info: null`, the Java build's `null` members in the payer-side answers, its 500 for a signed
message that is not a notification, and the decoder's `bill.paymentTiming`, which both builds now
present as `immediate` only when another bank's bill has neither it nor an `invoice.dueDate`
(owner, 2026-11-16; entry 25 retired). The same day the owner added entry 27 (a ULID cursor
later than every migrated one, which Go reads as ADR-0026 says and Java as the end of the stream):
nothing is pending.

## Alternatives rejected

- **Byte-identity checked by schema validation.** It catches nothing that matters here.
- **Reproducing Jackson's indentation.** It costs effort for bytes no conformant client reads.
- **Generating the server from OpenAPI.** It brings back a generated DTO layer.
- **Shipping `errorCode` and `Idempotency-Key` during the port.** Every regression would become
  ambiguous.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
