# 36. The leg is explicit on blockchains, and only the leg-1 winner may report

Date: 2026-11-14

## Status

Proposed 2026-11-14. **Supersedes in part** [ADR-0004](0004-phase-inferred-from-transaction-id.md)
(on blockchains only). Completes [ADR-0003](0003-two-phase-payment-notification-approval.md) and
[ADR-0021](0021-a-reservation-knows-who-holds-it.md).

## Context

ADR-0004 lets `transactionId` decide the leg on every rail. It treats `blockchain.action` as a
**corroborating** signal only: a `PAYMENT_INITIATED` that carries a transaction id is post-commit,
and is recorded without a veto. The reason given was that X9.150 must never invite a refusal for
money that has already moved.

Since then, three things have changed:

- **The code never followed that rule on Solana.** `PaymentNotificationQRCodeUseCase.resolveIntent`
  keys Solana on `blockchain.action`, so a `PAYMENT_INITIATED` with a transaction id goes through the
  acceptance gate as leg 1.
- **Leg 1 is now required on every rail** (owner, 2026-11-13). It is what stops two payers from
  paying the same QR Code at the same time. A payer who skips it holds no signed yes, and its
  payment is its own to resolve. ADR-0004's guarantee therefore protects the payer that won leg 1,
  not everyone.
- **On blockchains the leg 1 / leg 2 protocol is the reconciliation mechanism.** Most chains carry
  no memo, and where one exists (Solana) some custody providers do not expose it. A protocol carrying
  that much weight should not rest on an inference when the message can state its leg explicitly.

## Decision

**What decides the leg:**

| Rail | Leg 1 | Leg 2 |
|---|---|---|
| Blockchains (Solana, and any chain interpreted later) | `blockchain.action = PAYMENT_INITIATED` | `blockchain.action = SENT` (or `NOT_SENT`, the release) |
| Bank rails (FedNow, RTP, ACH) | no `transactionId` | `transactionId` present (End-to-End ID, Trace Number) |

On bank rails ADR-0004 stands. On blockchains `blockchain.action` is the authority.

**A contradictory message is refused, never reinterpreted.** On a blockchain:

- `SENT` without a transaction id → 400 (unchanged);
- `PAYMENT_INITIATED` with a transaction id → 400, naming the contradiction.

No state changes, nothing is recorded, and the answer is signed like any refusal. A payer who has
already moved money sends `SENT`, which is leg 2 and is judged by the rules below.

**Leg 1 on every rail:**

- leg 1 goes through the acceptance gate at the payee's clock, and takes the reservation;
- while a reservation holds, another payer's leg 1 is refused with 409;
- the holder may retry leg 1, and the retry is judged again;
- a refused retry from the holder releases the reservation: the QR Code returns to `ACTIVE` in the
  same transaction;
- winning leg 1 on a lapsed reservation emits `payment.initiated` for the new holder.

**Leg 2 only from the leg-1 winner:**

- leg 2 is accepted only from the payer that holds, or last held, the reservation (ADR-0021), and
  only while no other payer has since won leg 1;
- leg 2 is recorded and never judged again on amount or time;
- leg 2 from anyone else, including a payer who never sent leg 1, is refused with 409 or 400.

## Consequences

- One rule per rail family, stated by the message itself where the message can state it.
- The two-payer race is closed on every rail, FedNow and RTP included.
- ADR-0004's guarantee becomes "X9.150 never refuses leg 2 from the payer it authorized". The
  authorized payer holds a signed yes for any later dispute.
- FedNow/RTP payers that send only an after-payment notification today start receiving 400. Those
  rails carry the QR Code id in the pacs.008 (`QRCD`), so the payee still reconciles.
- The change ships in Java before the freeze (Stories 2.4 and 2.5 of the rewrite breakdown), and Go
  ports it through the ADR-0035 transition map.

## Alternatives rejected

- **Keep `transactionId` as the authority on blockchains** (ADR-0004 as written). That would
  reverse the shipped Solana behaviour, and it lets an inference override an explicit statement.
- **Treat a contradictory `PAYMENT_INITIATED` as leg 2** (ADR-0004's disagreement rule). It records a
  payment from a message that also claims none was made. Refusing it costs one resend as `SENT`.
- **Accept leg 2 from anyone, as evidence.** It reopens the race leg 1 closes: a payer could skip the
  reservation and still be recorded as having paid.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
