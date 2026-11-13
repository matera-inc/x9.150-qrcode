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

Java's actual rules (`QRCodeEntity.java:512-700`):

- **The body** comes from the **last stored notification** if there is one. Otherwise it comes from
  the first payment method and the payment details.
- **A repeat announcement** by the holder refreshes the reservation and emits nothing.
- **`SENT`** emits without changing status.
- **`reactivate`** emits nothing.

## Decision

**One function builds every event: `domain.BuildEvent(type, row, lastNotification, now,
reason)`.** No other code constructs a `payment_event`. The body follows Java's rule above, field
for field. The event contract and its `schemaVersion` are unchanged.

**The transition map is normative.** The suite pins every row:

| Trigger | Allowed from (status read) | Status after | Event |
|---|---|---|---|
| create | — | `ACTIVE` | none |
| PATCH | `ACTIVE` or `PAYMENT_INITIATED` (stored; `PatchQRCodeUseCase.java:55-57`) | unchanged | none (audit row, [0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)) |
| status-update `PAYMENT_INITIATED` | `ACTIVE` (effective) | `PAYMENT_INITIATED`, unattributed reservation | `payment.initiated` |
| status-update `PAID` | `ACTIVE` or `PAYMENT_INITIATED` (stored, ADR-0020) | `PAID` | `payment.cleared` |
| status-update `CANCELLED` | `ACTIVE` or `PAYMENT_INITIATED` (stored) | `CANCELLED` | `payment.cancelled` |
| status-update `ACTIVE` (reactivate) | `PAYMENT_INITIATED` (effective) | `ACTIVE` | **none** |
| bank-rail notification (FedNow/RTP) | `ACTIVE` (effective) | unchanged (courtesy) | none |
| bank-rail notification (ACH) | `ACTIVE` (effective) | `PAYMENT_INITIATED` | `payment.initiated` if the status changed |
| Solana `PAYMENT_INITIATED`, new holder | `ACTIVE` (effective) | `PAYMENT_INITIATED` | `payment.initiated` |
| Solana `PAYMENT_INITIATED`, same holder | `PAYMENT_INITIATED` (effective) | unchanged, window refreshed | **none** |
| Solana `SENT` (holder) | `PAYMENT_INITIATED`, or lapsed (ADR-0019) | unchanged | `payment.sent` |
| Solana `NOT_SENT` (holder) | `PAYMENT_INITIATED` (effective) | `ACTIVE` | `payment.failed` |
| location released by a rebind | not live ([0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)) | unchanged, new random location | none |
| reservation lapse | — (on read only) | reads as `ACTIVE` | **none** (ADR-0019) |

Phase 1 verifies the table against the Java code and the suite. Any row Java contradicts is
corrected here before Go is built.

**Notification storage.** Each accepted notification is appended to `qr_code_notification` **before**
the event is built, in the same transaction. "Last stored notification" means the newest row for the
QR Code by its UUIDv7 `id`, read **after** that insert, so `payment.sent` carries the hash it
reports (as Java assigns the notification before emitting, `QRCodeEntity.java:645-650`). Optional
fields come from `notification_json`, so an absent tip stays absent.

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
