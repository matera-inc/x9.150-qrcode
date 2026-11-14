# 35. One event builder, and a written transition map

Date: 2026-11-13

## Status

Accepted 2026-11-13. Part of [0022](0022-rewrite-in-go-as-a-standalone-product.md).
Completes [0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md).

## Context

Events are the integration point that matters most. In Java, which transition emits what, and with
which body, is spread across `QRCodeEntity` (`pay`, `cancel`, `initiatePayment`, `reactivate`,
`emitForNotification`, `emit`). If two Go packages each built "their" events, they would disagree
on the body. One might take the amount from the payer's notification and another from the QR Code;
one might emit on a repeat announcement and another not.

Java's actual rules (`QRCodeEntity`, the payer-notification block, after Story 2.4):

- **The body** comes from the **last stored notification** if there is one. Otherwise it comes from
  the first payment method and the payment details.
- **A repeat announcement** by the holder refreshes the reservation and emits nothing.
- **A post-commit** never changes a closed QR Code, and reserves an `ACTIVE` one (ADR-0036).
- **Every event a payer's notification produces** carries `receivedInStatus` and, when the payer
  broke the protocol, `deviations` (ADR-0036; `schemaVersion` `1.1`).
- **`reactivate`** emits nothing.

## Decision

**One function builds every event: `domain.BuildEvent(type, row, lastNotification, now,
reason)`.** No other code constructs a `payment_event`. The body follows Java's rule above, field
for field. The event contract is unchanged except for ADR-0036's additive `deviations` and
`receivedInStatus` (`schemaVersion` `1.1`).

**The transition map is normative.** The suite pins every row:

| Trigger | Allowed from (status read) | Status after | Event |
|---|---|---|---|
| create | — | `ACTIVE` | none |
| PATCH | `ACTIVE` or `PAYMENT_INITIATED` (stored; `PatchQRCodeUseCase.java:55-57`) | unchanged | none (audit row, [0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)) |
| status-update `PAYMENT_INITIATED` | `ACTIVE` (effective) | `PAYMENT_INITIATED`, unattributed reservation | `payment.initiated` |
| status-update `PAID` | `ACTIVE` or `PAYMENT_INITIATED` (stored, ADR-0020) | `PAID` | `payment.cleared` |
| status-update `CANCELLED` | `ACTIVE` or `PAYMENT_INITIATED` (stored) | `CANCELLED` | `payment.cancelled` |
| status-update `ACTIVE` (reactivate) | `PAYMENT_INITIATED` (effective) | `ACTIVE` | **none** |
| pre-commit, any rail (no `transactionId`), new holder | `ACTIVE` (effective; a lapsed reservation counts) | `PAYMENT_INITIATED`, held by the sender | `payment.initiated` (also on a takeover of a lapsed reservation) |
| pre-commit, any rail, same holder (retry) | `PAYMENT_INITIATED` (effective), held by the sender | unchanged, window refreshed (never restarted once a hash is registered) | **none** |
| pre-commit, any rail, once a hash is registered (holder included) | `PAYMENT_INITIATED` (effective), hash registered | refused, 409 | none |
| pre-commit, any rail, same holder, **refused** by the gate | `PAYMENT_INITIATED` (effective), held by the sender | `ACTIVE`, reservation cleared, in the same transaction (400 to the payer) | blockchain: `payment.failed`; bank rail: none |
| pre-commit, any rail, another payer | `PAYMENT_INITIATED` (effective), held by someone else | refused, 409 | none |
| pre-commit, any rail | `PAID` or `CANCELLED` | refused, 400 | none |
| post-commit, bank rail (`transactionId` present) | `ACTIVE` (effective) | `PAYMENT_INITIATED`, held by the sender, window stopped | `payment.initiated`, flagged `RECEIVED_IN_WRONG_STATUS` |
| post-commit, blockchain | `ACTIVE` (effective) | `PAYMENT_INITIATED`, held by the sender, window stopped | `payment.initiated` then `payment.sent`, both flagged `RECEIVED_IN_WRONG_STATUS` |
| post-commit, any rail | `PAYMENT_INITIATED`, held by the sender | unchanged, window stopped | blockchain: `payment.sent`; bank rail: none |
| post-commit, any rail | `PAYMENT_INITIATED`, held by someone else | unchanged, the holder keeps it, window stopped | blockchain: `payment.sent`, flagged; bank rail: none |
| post-commit, any rail | `PAID` or `CANCELLED` | unchanged (never reopens) | blockchain: `payment.sent`, flagged; bank rail: none |
| blockchain `PAYMENT_INITIATED` **with** a hash | any | as the post-commit row for that status | as that row, plus `ACTION_CONTRADICTS_HASH`; a warning names the payer |
| blockchain `SENT` without a hash | any | refused, 400 | none |
| blockchain `NOT_SENT` (holder) | `PAYMENT_INITIATED` (effective), or a lapsed reservation of the sender's | `ACTIVE` | `payment.failed` |
| blockchain `NOT_SENT` (holder), once a hash is registered | `PAYMENT_INITIATED` (effective), hash registered | unchanged; `ACTION_CONTRADICTS_HASH` in the warning | none |
| blockchain `NOT_SENT` **with** a `transactionId` | any | as the post-commit row for that status | as that row, plus `ACTION_CONTRADICTS_HASH` |
| blockchain `NOT_SENT` | held by someone else: 409; `ACTIVE` with no reservation of the sender's, `PAID`, `CANCELLED`: 400 | unchanged | none |
| location released by a rebind | not live ([0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)) | unchanged, new random location | none |
| reservation lapse | — (on read only) | reads as `ACTIVE`; never once a hash is registered, nor when `x9.reservation.ttl-seconds` is `0` (ADR-0036) | **none** (ADR-0019) |

Phase 1 verifies the table against the Java code and the suite. Any row Java contradicts is
corrected here before Go is built.

**Notification storage.** Each accepted notification is appended to `qr_code_notification` **before**
the event is built, in the same transaction. "Last stored notification" means the newest row for the
QR Code by its UUIDv7 `id`, read **after** that insert, so `payment.sent` carries the hash it
reports (as Java assigns the notification before emitting, `QRCodeEntity.java:645-650`). Optional
fields come from `notification_json`, so an absent tip stays absent.

**Same holder** means `payer.info` present on both and equal, and the same certificate subject;
without `payer.info` the sender is never the holder (ADR-0036).

**Every transition sets `revised_at`;** only PATCH moves `revision` ([0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)). The `request_json` never holds
notification data ([0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)).

## Consequences

- Every package emits through one door, so the bodies cannot drift.
- `payment.cleared` after a Solana `SENT` carries the payer's amount, tip and hash, exactly as in
  Java. Consumers already reconcile on that (ADR-0014).
- Adding a transition means adding a row here and a test in the suite.

## Alternatives rejected

- **Each package builds its own events.** Two owners of one shape.
- **Bodies always from the QR Code row.** It changes what consumers receive today.
- **Emitting on a lapse or a reactivate.** Contrary to ADR-0019 and to today's stream.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
