# 41. A pre-commit needs time to settle before validUntil

Date: 2026-11-18

## Status

Accepted 2026-11-18 (owner). Amends the "`validUntil` is inclusive" bullet of
[INTERPRETATION I-13](../../official-spec/INTERPRETATION.md#i-13--the-payees-clock-decides-and-the-due-dates-last-instant-is-on-time)
for the pre-commit. Ships in the release after 3.0.1.

## Context

A payer's pre-commit ("I'll pay") is judged by the acceptance gate on the payee's clock at receipt
(I-13), against the QR Code's `validUntil` and the matched payment method's. Until now both were
inclusive: a pre-commit arriving at exactly `validUntil` was approved.

But an approval is not a payment. X9.150's pre-commit arrives **before** the money moves; the payer
starts the transfer only once it holds the payee's signed yes. A payer approved a few milliseconds
before `validUntil` starts a transfer that cannot settle while the QR Code is valid, and the payee
then holds an approval for a QR Code that has expired by the time the money arrives.

**Pix contrast.** In Pix the payment is the first message: money arriving after a charge's
expiry has already moved and must be tolerated, so the receiver has nothing to refuse in advance.
X9.150 gives the payee a moment before the money moves, so the payee can play safe (owner,
2026-11-18). The post-commit, which reports money already moved, keeps being recorded however late
(I-11): this decision changes only what the payee approves.

## Decision

**A pre-commit is refused when `now + margin > validUntil`**, for the QR Code's `validUntil` and
for the matched payment method's, judged on the payee's clock at receipt.

- **The margin is configurable:** `notification.settlement-margin-seconds`
  (`X9_NOTIFICATION_SETTLEMENT_MARGIN_SECONDS`, chart value
  `application.x9.notification.settlement-margin-seconds`), **default 1 second**. `0` turns it off
  and restores the inclusive edge. A negative value stops the service at start, naming the key.
- **The refusal is the existing signed 400**, on field `validUntil` (the QR Code) or
  `paymentMethods.validUntil` (the method). Two messages keep the expired text stable for clients:
  past `validUntil` it still says the QR Code (or the method) expired; inside the margin it says
  "Too little time is left to settle: this QR Code expires at …, and payments are approved only
  until 1 second before it."
- **A holder's pre-commit retry** is gated too, as it already is for expiry: a refused retry
  releases the holder (ADR-0036).
- **Untouched:** the post-commit (never re-judged on time, I-11); `NOT_SENT`; the edges of a
  discount or late fee (I-14), which stay exact instants; the status-update API, which is the
  payee's own control. No metric is added.

## Consequences

- With the default, a pre-commit sent in the last second of a QR Code's life is refused. A payer
  whose clock is behind the payee's already bore that risk (I-13); the margin widens it by one
  second.
- A deployment that wants the previous behaviour sets the margin to `0`.
- The black-box time-edge test that accepts a pre-commit just before `validUntil` now judges it
  before `validUntil − 1 s`, and a pre-commit inside the margin is shown refused with nothing
  reserved.

## Alternatives rejected

- **No margin (keep `validUntil` inclusive).** It approves transfers that cannot settle in time.
- **A margin per rail.** FedNow, RTP and Solana settle in seconds; ACH does not settle within any
  margin worth having, and its `expectedDate` is a separate matter. One setting is enough until a
  deployment shows otherwise.
- **Shorten the published `validUntil` instead.** It would change what the payee signed and
  showed the payer; the payload must keep saying what the payee means.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
