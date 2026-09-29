# 16. A revision is a version of the request, not of its status

Date: 2026-09-29

## Status

Accepted. Corrects [ADR-0002](0002-atomic-transitions-with-embedded-outbox.md), which specified
`$inc: { revision: 1 }` on a status transition.

## Context

`revision` was the Mongo `@Version` field. One field was doing two jobs:

- the **optimistic-lock token**, which by definition must move on every write, and
- the **payment request's version**, which is what the number means to a biller reading the API.

Those requirements disagree. A QR Code marked `PAID` had its revision incremented, so a bill that
nobody had edited was reported as being at version 2, 3, 4 — one per thing that had happened to it.

A payment request marked `PAID` is **the same request with a new status**, not a new version of the
request. A new version exists when a caller changes what is being asked for: the amount due, the due
date, a payment method. That is an edit; a status transition is an event.

## Decision

Two fields.

- **`lockVersion`** — `@Version`, internal, never exposed. Moves on every save. This is what
  optimistic locking needs and all it is for.
- **`revision`** — the payment request's own version. Incremented only on the PATCH path, which is
  the only way a caller changes the request's data. A new request starts at `0`.

`(id, revision)` therefore identifies a version of the request, which is what the `qrcode_history`
collection is keyed on.

**Status history is history of the entity, not of a version**, and already exists: the payment event
stream records `payment.initiated`, `payment.sent`, `payment.failed`, `payment.cleared` and
`payment.cancelled` against the QR Code id. A separate status-history collection would duplicate it.

## Consequences

**A conditional request can no longer be keyed on `revision` alone.** That is the cost, and it is
not small: a caller meaning "cancel only if nobody has started paying" would be handed a token that
a pre-payment leaves untouched — the exact race the conditional update exists to close.

So conditional requests use an **`ETag`** covering the revision *and* the status, returned on `GET`
and echoed in `If-Match`. It is opaque; callers must not build it. `revision` stays a clean business
number and the conditional request keeps catching both kinds of change.

**`isNew()` had to stop keying on `revision`.** A brand-new request legitimately starts at `0`, and
reading newness from that made Spring Data take a first save for an update. It keys on `lockVersion`
now, which is absent until a document has actually been stored.

**The lock token must be carried back after a save**, or a second save in the same request presents
a token the store has already moved past and is refused as a concurrent modification by the very
request that made the first one.

## Alternatives considered

- **Leave it.** Rejected: the number is in the public contract and was reporting something other
  than what it is named for.
- **Expose both numbers.** Rejected: a caller does not need the lock token, and giving them one
  invites them to build conditional requests out of it.
- **`expectedStatus` in the body instead of an ETag.** Rejected: it would miss a PATCH that changed
  the amount between the read and the write. The ETag covers both with one mechanism.
