# 0021 — A reservation knows who holds it

**Status:** Accepted
**Date:** 2026-10-03

## Context

[ADR-0003](0003-two-phase-payment-notification-approval.md) gave a QR Code a reservation so two payers could
not both believe they were paying one bill, and [ADR-0019](0019-a-reservation-expires-and-nothing-sweeps.md)
gave that reservation a window so a payer who vanished could not lock a bill forever.

Neither recorded **who** was holding it. The rule was enforced on the status alone: any announcement
on a QR Code that was not `ACTIVE` was refused. That protects against the second payer and punishes
the first.

> I tested initiating the QR Code two times from the same payer (initiated, wait 5 s, initiated
> again) simulating an idempotency from the same payer due to a crash. It failed.

Reproduced on `git-e524b54`, with the payer's own identifier on both calls:

```
1st announcement  -> 200
2nd announcement  -> 400  "Cannot initiate notify a blockchain payment for a QR Code that is not ACTIVE."
```

**The commonest reason to receive one announcement twice is not a second payer. It is one payer,
twice** — an HTTP timeout, a crash between sending and recording, an at-least-once queue. The
service was least usable at the moment the caller's software was already in trouble, and the
correct remedy (retry) was the thing being refused.

Two further defects shared the same root. `NOT_SENT` — the only message by which a payer can give a
bill back — answered `200` and released nothing, because without a holder there was nobody whose
reservation it could be. And the payment event stream carried no payer at all, so a payee
reconciling from it could see a payment and not who made it.

## Decision

**A reservation records the party that took it**, and only that party may refresh it, report on it,
or give it back.

**Identity is `payer.info` paired with the subject of the certificate that signed the JWS.** Both
must match. The order matters: `payer.info` (§3.1) is a field in a body and the signer chose its
contents, so on its own it would let any signer claim to be any payer. The certificate subject is
not self-asserted — its chain was validated against the trusted roots — so pairing them means
`payer.info` can only distinguish between customers of an institution already authenticated. **It
narrows an identity; it never establishes one.**

Deliberately **not** the blockchain `from` address: a PSP settling USDC sends every customer's
payment from one hot wallet, so that address identifies the PSP and says nothing about which of its
customers this is.

| situation | answer |
|---|---|
| nobody holds it | the announcement takes it |
| held by this same party | `200`, window refreshed, nothing else changes |
| held by somebody else | `409`, naming the conflict |
| `PAID` / `CANCELLED` | refused as before |
| `NOT_SENT` from the holder | released to `ACTIVE`, `payment.failed` published |
| `NOT_SENT` or `SENT` from anybody else | `409` |

`409` rather than `400` because the request is well formed and it is the state that says no. The
caller should come back later, not go looking for a mistake they did not make.

**A repeat publishes nothing.** A second `payment.initiated` on the stream would be
indistinguishable from a second payer, which is the one thing a consumer most needs to tell apart.
Same reasoning as the payee-side `X -> X` no-op in [ADR-0020](0020-the-payee-states-the-outcome-of-its-own-receivable.md).

**A post-payment report is gated on the holder, not on the clock.** A payer whose window has lapsed
may still report `SENT`: the money moved, and refusing the report does not un-move it — it only
means this service never hears about a payment that happened. The window exists to stop a vanished
payer locking a bill, not to decide what is true about money.

That last point is not hypothetical. A payment routed through a custody provider can return
*before* its transaction hash exists, with the hash arriving later from a ledger subscription that
has no deadline at all. **No window covers that, however wide**, so a rule keyed on the window
would refuse those reports as a matter of course. This is why widening the 90 seconds was rejected:
it narrows the odds on a case that has no bound.

**The payment event carries `payerInfo`.** The stream is the public contract
([ADR-0001](0001-events-leave-by-pull-not-push.md)); if the identity of the announcing
party has to be fetched from the QR Code document, the stream is not self-sufficient.

## Consequences

A payer may safely retry an announcement. A payer may give a bill back. A payee reconciling from
the stream can see who paid. Two payers still cannot hold one bill.

**A PSP that sends no `payer.info` gets bank-level granularity.** Identity falls back to the
certificate alone, so two customers of that PSP read as the same party and either may refresh or
release the other's reservation. That is the best this service can do with what it was given, and
it is the concrete reason for a PSP to send the field — without it, its own customers tread on each
other. Recorded in `official-spec/BEST-PRACTICES.md`.

**Unidentified matches nobody, including another unidentified party.** Two anonymous announcements
are not evidence of being the same payer, and a reservation taken through the payee's own
status-update API belongs to no payer at all, so no payer may adopt it.

**One case is still not served:** a report arriving after the reservation lapsed *and* another payer
has since taken the QR Code. That overwrites who was holding it, so the late reporter matches
nobody and is refused. A history of holders would answer it, at the cost of unbounded growth on
every document for a case nobody has yet hit. The refusal at least says what happened.

## Alternatives rejected

**Match on `payer.info` alone.** It is a field in a signed body. Any PSP could claim to be any payer
by sending their identifier — and that identifier is visible to every counterparty that has ever
received one of that payer's notifications.

**Match on the certificate alone.** Two customers of one PSP would be the same party, so either
could take over the other's reservation. That is the exact collision a reservation exists to
prevent, occurring in the one place it is most likely: inside a single institution, where two
payers share a signer.

**Match on the blockchain `from` address.** One hot wallet, every customer. It identifies the PSP.

**Widen the 90-second window instead.** Does not address it. The asynchronous settlement path has no
time bound, so a wider window only narrows the odds — and the failure it leaves behind is a payment
that happened and was never recorded.

**Sweep lapsed reservations and clear the holder.** Reintroduces the background job ADR-0019
rejected, and would destroy exactly the record a late report needs.

**Keep a history of every party that has held the QR Code.** Answers the last open case above, at
the cost of unbounded growth on every document, for a case that has not yet occurred. Revisit if it
does.
