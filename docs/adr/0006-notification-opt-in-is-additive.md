# ADR-0006 — Notification opt-in is an additive optional field, not a new API version

- **Status:** Accepted
- **Date:** 2026-09-26
- **Context:** `supporting-payment-notifications`

## Context

Payment notifications are not universally needed. On several rails the QR id travels inside the
payment message itself, so the payee reconciles directly from it:

| Rail | Carries the QR id | Notification needed? |
|---|---|---|
| FedNow, RTP | ISO 20022; the payee can act on the pacs.008 (ADR-0008) | No — courtesy only |
| Solana | MEMO field | No, when MEMO is used |
| ACH, EVM pre-commit | Nothing until funds move | **Yes** |

So the QR's creator must be able to say whether it wants notifications, and (per ADR-0003) whether it
wants to *approve* them. The question was whether adding that requires a `/v2`.

## Decision

**No new API version.** Add one **optional** field, `paymentNotification.mode`, defaulting to `NOTIFY`.

| Create request | Meaning | Status |
|---|---|---|
| `paymentNotification` **absent** | No notifications; a notification for this QR is rejected | **Already the behavior** |
| `kind: DEFAULT` | Notifications accepted and recorded, no biller vote | Already the behavior |
| `kind: DEFAULT` + `mode: APPROVE` | Two-phase approval (ADR-0003) | **New** |
| `kind: EXTERNAL` | The creditor hosts its own endpoint | Already the behavior |

## Rationale

**The opt-in already exists and does not need inventing.** `paymentNotification` is already optional on
the create request (`PaymentRequestAdditionalInfo`, no `required` entry), and
`QRCodeEntityValidator.validatePaymentNotification` already rejects a notification for a QR without
one. **Absent already means "I do not want payment notifications."** Only the approval dimension is new.

**The API's own published policy settles the versioning question.** The OpenAPI description lists,
among the changes it deems backward-compatible: *"Inclusion of new optional parameters"*,
*"Introduction of new optional fields in API"*, and *"Addition of new elements to enumerations"*. A new
version would be required only for a removal, a retype, or a new **required** field.

## Consequences

- **No migration.** Callers who never sent `paymentNotification` get exactly what they get today.
  Callers sending `kind: DEFAULT` keep notify-without-vote, because `mode` defaults to `NOTIFY`.
- **Nothing existing is re-interpreted** — the field only adds a behavior that could not be expressed.
- A test asserts the backward-compatibility claim rather than assuming it.

## Alternatives rejected

**A `/v2` of the create API:** unjustified by the change, and doubles the surface to maintain and
document for one optional field.

**A new `kind` enum value (`APPROVE`):** also permitted by the policy, but `kind` answers *"who hosts
the callback endpoint"* while approval answers *"does the biller get a vote"*. They are orthogonal, and
merging them raises an immediately unanswerable question — what is `EXTERNAL` + `APPROVE`?

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
