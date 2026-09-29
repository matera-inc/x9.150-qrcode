# ADR-0002 — Atomic state transitions with an embedded outbox

- **Status:** Accepted
- **Date:** 2026-09-26
- **Context:** `supporting-payment-notifications`

## Context

Two payment notifications for the same QR could both succeed, producing a double payment. Today
`PaymentNotificationQRCodeUseCase` reads the entity, mutates it and saves it — a read-modify-write
guarded only by `@Version` optimistic locking, whose lost race surfaces as an HTTP 500.

Separately, nothing downstream is told that a payment started, and any "update state then publish"
scheme can lose the publish or publish without the state change.

The standard asks for this transition explicitly, and names the reason:

> "On receipt of a payment notification, The Payee's PSP **SHOULD** update status to
> 'PAYMENT_INITIATED' to prevent duplicate payment." — ANSI X9.150-2026 §A.9

So duplicate-payment prevention is not only the adopter's requirement; it is the standard's stated
purpose for the transition. (The standard is not in this repository; see
[`official-spec/README.md`](../../official-spec/README.md) to purchase it.)

## Decision

Every state transition is **one `findAndModify` on one document** that sets the state *and* pushes its
event in the same update:

```js
filter: { _id: qrId, status: "ACTIVE" }
update: { $set: { status: "PAYMENT_INITIATED", payment_initiation: {…} },
          $inc: { revision: 1 },
          $push: { outbox: <payment.initiated> },
          $unset: { ttl: "" } }
```

The winner gets the document; concurrent callers get `null` and a **synchronous 409**. Settle, release
and the expiry sweep use the same form, matching on `paymentId`.

## Consequences

- Exactly-once initiation, enforced by the database rather than by application logic.
- An event **cannot exist without its state change**, or the reverse.
- **No multi-document transaction is introduced.** Single-document atomicity suffices.
- Delivery is at-least-once; consumers deduplicate by `eventId`.
- `PAYMENT_INITIATED` doubles as the "prepared" state for ADR-0003.
- ~~`revision` must be `$inc`-ed manually — Spring Data's `@Version` only auto-increments on
  `save()`.~~ **Superseded by [ADR-0016](0016-a-revision-is-a-version-of-the-request-not-of-its-status.md)
  (2026-09-29):** the field a transition guards on and the number a biller reads are now separate.
  The lock token is `lockVersion`; `revision` counts data changes only, so a status transition must
  NOT increment it.

## Two hazards this forces us to handle

1. **`save()` would erase the outbox.** It replaces the whole document, so a replace racing a `$push`
   drops events and one racing a `$pull` resurrects them. The update path becomes a field-level `$set`
   that excludes `outbox` and `payment_initiation`, still guarded by `revision`.
2. **The TTL reaper would delete documents holding events.** `ttl` is set to `validUntil` with
   `expireAfter = "30S"`, so a QR vanishes 30 s after expiry — outbox included. Every `$push` therefore
   `$unset`s `ttl`, and the `$pull` that empties the array restores it.

## Alternatives rejected

**Optimistic locking alone** (today): a lost race is an opaque 500, not a meaningful rejection, and it
cannot carry an event atomically.

**A separate outbox collection:** requires a multi-document transaction to stay atomic with the state
change, which needs a replica set and re-introduces exactly the coupling single-document updates avoid.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
