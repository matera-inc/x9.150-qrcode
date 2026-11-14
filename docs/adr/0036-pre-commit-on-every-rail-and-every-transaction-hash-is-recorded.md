# 36. Pre-commit on every rail, and every transaction hash is recorded

Date: 2026-11-14

## Status

Proposed 2026-11-14. **Keeps** [ADR-0004](0004-phase-inferred-from-transaction-id.md) and brings the
Solana code back in line with it. Completes [ADR-0003](0003-two-phase-payment-notification-approval.md)
and [ADR-0021](0021-a-reservation-knows-who-holds-it.md).

## Context

A payment notification arrives at one of two moments, and this ADR names them by that moment:

- **pre-commit**: before the money moves. The payer asks "may I pay this?", and the payee may say no.
- **post-commit**: after the money moves. The payer reports the transaction id (the txHash on a
  blockchain), so the payee can tell which QR Code was paid.

Earlier drafts called these "leg 1" and "leg 2". "Leg" usually names one of the separate transactions
that make up a saga, which is not what happens here: these are two moments of one payment. The phase
names are used from now on.

ADR-0004 decides the phase from evidence: **a transaction id exists only once the transaction is
committed.** So `transactionId` absent means pre-commit, present means post-commit, and
`blockchain.action` only corroborates.

Three things have since been found or decided:

- **The Java code does not follow ADR-0004 on Solana.**
  `PaymentNotificationQRCodeUseCase.resolveIntent` decides the phase from `blockchain.action`. A
  `PAYMENT_INITIATED` that carries a transaction hash is therefore judged as a pre-commit, and can be
  refused, although the money has already moved.
- **Pre-commit is required on every rail** (owner, 2026-11-13). Its reservation is what stops two
  payers paying the same QR Code at once. Java has no pre-commit on FedNow and RTP today, because it
  requires an End-to-End ID on every notification there.
- **On blockchains, post-commit is how the payee learns which QR Code was paid.** Most chains carry
  no memo, and where one exists (Solana) some custody providers do not expose it. Losing a hash means
  losing the payee's only link between the funds and the bill.

## Decision

### The phase comes from the evidence, on every rail

| Rail | Pre-commit | Post-commit |
|---|---|---|
| Blockchains (Solana, and any chain interpreted later) | no `transactionId` (`action = PAYMENT_INITIATED`) | `transactionId` present: the txHash (`action = SENT`) |
| Bank rails (FedNow, RTP, ACH) | no `transactionId` | `transactionId` present: End-to-End ID, Trace Number |

ADR-0004 stands as written. `blockchain.action` corroborates the phase, and it decides only one thing
on its own: `NOT_SENT`, the payer giving a reserved QR Code back, which by its nature carries no
transaction id (ADR-0021).

### A contradictory message is the payer's fault, and its hash is still recorded

> ⚠️ **A `PAYMENT_INITIATED` that carries a transaction hash is bad behaviour by the payer.** It
> says "may I pay?" while showing that the money has already moved. Either the payer skipped the
> question, or it labelled its report wrongly. A payer that follows the protocol never sends it.
>
> **It is still treated as a post-commit, and its hash is recorded.** The money has moved. If the
> funds arrive in the right amount and the hash matches, the system that received them has
> everything it needs to mark the QR Code paid. Refusing the report would throw away the one piece
> of evidence that links those funds to this QR Code.

So, on a blockchain:

- `PAYMENT_INITIATED` **with** a transaction hash is a post-commit. It is recorded exactly as
  received, including the `action` the payer declared, so the contradiction stays visible. It is
  logged as a warning naming the payer. It is never judged again on amount or time.
- `SENT` **without** a transaction hash is refused with 400. There is nothing to record, and a
  committed transaction with no hash cannot exist.

### Every transaction hash is recorded

- **A hash is never overwritten or dropped.** A second or later post-commit is recorded beside the
  first. In Java each one is published as its own `payment.sent` event (the QR Code document keeps
  only the latest notification); in Go each one is its own row in `qr_code_notification`
  ([ADR-0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)).
- **X9.150 never marks a QR Code paid on a payer's report.** The payment system that receives the
  funds matches each hash against what arrived, and calls the status-update API. Which party does
  the matching changes; the mechanics do not.
- **More than one hash for one QR Code is the signal of a duplicate payment.** It should not happen
  when payers follow the protocol, and keeping every hash is what lets the payment system detect it
  when it does.

### Pre-commit on every rail

- A pre-commit goes through the acceptance gate at the payee's clock, and takes the reservation.
- While a reservation holds, another payer's pre-commit is refused with 409.
- The holder may retry its pre-commit, and the retry is judged again.
- A refused retry from the holder releases the reservation: the QR Code returns to `ACTIVE` in the
  same transaction.
- Winning a pre-commit on a lapsed reservation emits `payment.initiated` for the new holder.

### Post-commit from the payer that won the pre-commit

**To be confirmed next (open).** As proposed: a post-commit is accepted from the payer that holds, or
last held, the reservation (ADR-0021), and is recorded and never judged again. What happens to a
post-commit from anyone else, including a payer that never sent a pre-commit, is the next decision.
It must be squared with the rule above that every hash is recorded.

## Consequences

- One rule on every rail: the evidence decides the phase. The Solana code changes to follow ADR-0004
  (Story 2.4 of the rewrite breakdown), so a contradictory message is no longer refused by the
  acceptance gate.
- A misbehaving payer is named in the log and in the stored record, and its money is still
  traceable.
- The two-payer race is closed on every rail, FedNow and RTP included.
- FedNow/RTP payers that send only a post-commit today must send a pre-commit first. Those rails
  carry the QR Code id in the pacs.008 (`QRCD`), so the payee can still reconcile without it.
- The change ships in Java before the freeze (Stories 2.4 and 2.5), and Go ports it through the
  ADR-0035 transition map.

## Alternatives rejected

- **Let `blockchain.action` decide the phase on blockchains** (an earlier draft of this ADR). It
  refuses a contradictory message, so the payee loses the hash of money that has already moved.
- **Refuse a second or later hash.** It hides the one signal that a QR Code was paid twice.
- **Keep only the latest hash.** Same loss, more quietly.
- **Mark the QR Code paid on a post-commit.** X9.150 cannot see funds arrive; only the payment
  system that receives them can.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
