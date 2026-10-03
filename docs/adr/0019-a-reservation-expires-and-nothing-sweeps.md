# 19. A reservation expires, and nothing sweeps

Date: 2026-10-03

## Status

Accepted. Extends [ADR-0002](0002-atomic-transitions-with-embedded-outbox.md), which introduced the
reservation without an end to it.

## Context

A payer announcing a payment takes the QR Code to `PAYMENT_INITIATED` so two payers cannot pay one
bill. Nothing ended that state except the payer coming back.

When the payer does not come back — the app is closed, the custody provider refuses, the phone dies
— **the bill is unpayable by anybody until `validUntil`**: hours or days for a code that was held
for seconds. The payer cannot free it either; a second announcement on a reserved QR Code is
refused, and a payer has no access to the payee's management API by design. An adopter hit exactly
this in a real run.

`NOT_SENT` is the prompt release and is being fixed separately, but it only helps a payer who is
still running. A reservation must also end on its own.

## Decision

**A reservation carries the instant it stops counting, and the lapse is applied when somebody
reads — never by a sweep.**

- On entering `PAYMENT_INITIATED`, stamp `initiatedExpiresAt = min(now + ttl, validUntil)`.
- On leaving it, clear the stamp.
- `QRCodeEntity.effectiveStatus(at)` returns `ACTIVE` for a `PAYMENT_INITIATED` whose stamp has
  passed, and the stored status otherwise.
- **Every decision about status goes through `effectiveStatus`.** Nine sites did not.
- `x9.reservation.ttl-seconds`, default **90**.

**The expiry instant is stamped, not the start.** Deciding whether a reservation holds is then one
comparison, with no arithmetic and no need to know what the window was when the stamp was made — so
shortening or lengthening the setting does not retroactively move reservations already taken.

**Nothing is swept.** No background job, no race between a sweeper and an arriving payment, no
writes to documents nobody asked about. The stored status keeps saying what was last *reported*;
time is applied at the point of reading.

**The window never outlives the QR Code.** A reservation cannot outlive the thing reserved, so the
stamp is capped at `validUntil`.

**An unstamped reservation is treated as holding.** Documents written before this field existed
carry no instant, and releasing a reservation we cannot date would be guessing against the payer who
announced it. The unknown case fails towards keeping their claim.

## Consequences

**The single way this can be half-applied is a status decision that reads the stored field.** Then
the deployment contradicts itself — the payload endpoint serves a code the notification endpoint
still believes is reserved. There were **two doors** into the reserved state, not one: the biller's
status-update API and a payer's notification, and only the first was found by reading the obvious
method. Both now stamp through one private helper.

**A slow but honest payer can lose their reservation mid-payment.** The window lapses, somebody else
announces, and the first payer's `SENT` is then refused — money has moved and we cannot record it.
That is the price of the feature. It argues for a generous default, and it pairs with recording
*who* announced: once the reservation carries the announcing party, a late `SENT` from that party
can be accepted while the code is still re-announceable by others. Not in this change.

**Nothing is emitted when a reservation lapses**, and that is deliberate. The event stream reports
what was *notified* ([ADR-0014](0014-we-transport-and-sequence-the-consumer-reconciles.md)) and
nobody notified anything — it lapsed by the clock. A consumer that needs it can compute it: the TTL
is published configuration and `payment.initiated` carries the time. Worth knowing, because an
adopter will reasonably assume reservations end with an event.

**Reactivating a lapsed reservation is refused**, correctly: it already reads as `ACTIVE`, so there
is nothing to reactivate.
