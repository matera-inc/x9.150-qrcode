# 20. The payee states the outcome of its own receivable

Date: 2026-10-03

## Status

Accepted. Mostly a record of what the implementation already did — writing it down turned up two
places where it did not.

## Context

Two parties talk to this service and they are not symmetric.

A **payer** makes claims. *"I am going to pay", "I have paid, here is the hash."* A claim is about
something the payee cannot see, made by someone with an interest in it being believed, so it gets
checked: the amount against the bill, the rail against what was published, the destination against
what was advertised, the signature against a trusted CA.

A **payee** marking a bill paid is not making a claim. They own the receivable, the money has
arrived in an account only they can see, and in the interesting cases they have just finished
analysing an exception by hand. X9.150 never touches money
([ADR-0014](0014-we-transport-and-sequence-the-consumer-reconciles.md)) and therefore cannot see
what they saw. It is not in a position to second-guess them.

Today the implementation already behaves this way, and in one case **by accident**: `validUntil` is
simply never consulted on the status-update path, so an expired QR Code can still be marked paid.
Correct, and currently nobody knows it is deliberate. The next person to notice would be right to
read it as an oversight and add the check.

## Decision

**When the payee says a QR Code is paid, that is an order, not a request to be validated.** Be as
strict as you like with payers; the payee is stating the outcome of their own receivable.

Measured against `git-c1b6772`, and now pinned by tests:

| from | `PUT /status-update → PAID` | |
|---|---|---|
| `ACTIVE` | **accepted** | |
| `PAYMENT_INITIATED` | **accepted** | including a reservation that has lapsed |
| past `validUntil` | **accepted** | expiry is not a status; the payee may still settle it |
| `CANCELLED` | **refused** | see below |
| `PAID` | refused | open, see below |

**`CANCELLED` is the exception, and it is not a contradiction.** A withdrawn bill stays withdrawn.
The QR Code's life ended when the biller ended it, and no later payment reopens it. The payee's
authority is over the *outcome* of a live receivable, not over whether a receivable exists — that
is the biller's, and they already exercised it.

**Any future precondition on `pay()` must argue against this principle**, rather than be added
because preconditions feel prudent.

**X → X is accepted, on every status.** Asking for the status a QR Code already has is a repeated
call, not a transition: HTTP retries, at-least-once queues and a human clicking twice all produce
it, and every one means *"make sure it is X"* — which it already is. All four were refused, with
three different codes. It is a true no-op: no event, no revision, no `revisedAt`, because something
emitting a second `payment.cleared` would make a duplicated request indistinguishable from a second
payment. A repeated `PAID` therefore keeps the reference recorded first; that is what a no-op means.

**Payee-facing only.** A payer announcing twice is two payers reaching for one bill and is still
refused. That lives on the notification endpoint, not this one — which is exactly why the rule
change broke a test (`X9-LIFE-052`) that had been asserting a payer-protection property through the
payee's API. Its intent survives as `X9-LIFE-042` and `X9-LIFE-002`, where it belongs.

**Payers stay strict, and that is not in tension.** Payload retrieval and payment notifications are
refused for a QR Code that is `PAID`, `CANCELLED` **or expired**. The asymmetry is the point: the
same lapsed QR Code a payer may no longer fetch, the payee may still mark paid.

The expired case was a real hole. The payload endpoint's expiry path was only reached once
MongoDB's TTL index removed the document — long after `validUntil` — so a lapsed QR Code went on
serving its payload, and a payer scanning a week-old code was handed bank details and an amount as
though they were still on offer. `validUntil` is now checked explicitly.

## Consequences

**A payment landing against a cancelled bill is real money the payee holds, and this service will
record nothing about it.** The notification is refused outright. That is defensible — x9 is not a
ledger and the lifecycle genuinely ended — but the reconciliation then lives entirely outside the
standard, and nobody should later expect to find it in the QR Code's history.

Worth separating two things that are about to be decided together by default: `CANCELLED` being
**final** does not require the attempt to be **unrecorded**. Recording a refused notification
without changing status would give the payee that history while leaving the lifecycle ended. Not
decided here.

**Re-ordering `PAID` on an already-paid code is open.** The recommendation on the table: the same
`endToEndId` is an idempotent no-op, because a retry after an ambiguous timeout must be safe; a
*different* one is refused, naming the reference already recorded, so the payee learns which
payment they had settled and that a refund is owed. Recording it as paid again would silently
overwrite the first reference, and there is no concept of a second payment to put it in.

**The expiring-reservation work must not gate this.** Whatever
[ADR-0019](0019-a-reservation-expires-and-nothing-sweeps.md)'s `effectiveStatus` grows into, the
payee's order is not subject to it.

**"Only the party that announced may re-announce or release" is about payers.** The payee is not a
party to that constraint and must not be caught by it when it lands.
