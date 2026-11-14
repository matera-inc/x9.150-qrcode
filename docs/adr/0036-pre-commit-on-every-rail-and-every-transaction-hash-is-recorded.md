# 36. Pre-commit on every rail, and every transaction hash is recorded

Date: 2026-11-14

## Status

Proposed 2026-11-14. **Keeps** [ADR-0004](0004-phase-inferred-from-transaction-id.md) and brings the
Solana code back in line with it. Completes [ADR-0003](0003-two-phase-payment-notification-approval.md)
and [ADR-0021](0021-a-reservation-knows-who-holds-it.md), and **supersedes in part** ADR-0021: its
bank-level fallback when `payer.info` is absent.

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

**"Recorded" means registered with the payment system.** On a blockchain, each hash is published as
a `payment.sent` event through the outbox ([ADR-0001](0001-events-leave-by-pull-not-push.md)). The
event tells the payment system: *if this hash arrives, it is for this QR Code.* The hash is also
stored with the notification, but storage alone is not registration: the event is what reaches the
payment system.

- **A hash is never overwritten or dropped.** A second or later post-commit publishes its own
  `payment.sent`, beside the first. In Go each one is also its own row in `qr_code_notification`
  ([ADR-0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)); the Java QR Code
  document keeps only the latest notification, but every event survives in the event log.
- **X9.150 never marks a QR Code paid on a payer's report.** The payment system that receives the
  funds matches each hash against what arrived, and calls the status-update API. Which party does
  the matching changes; the mechanics do not.
- **More than one hash for one QR Code is the signal of a duplicate payment.** It should not happen
  when payers follow the protocol, and keeping every hash is what lets the payment system detect it
  when it does.

### The same payer only when `payer.info` proves it

Two notifications come from the same payer only when **both** carry `payer.info`, the two values are
equal, **and** the certificate subjects are equal. **Without `payer.info` there is no means to tell
two payers apart, so they are assumed to be different.**

This replaces ADR-0021's fallback, which matched on the certificate alone when `payer.info` was
absent, so two customers of one PSP read as one party and could retry or release each other's
reservation. Now:

- a payer without `payer.info` may still win a pre-commit and hold the reservation;
- it cannot retry that pre-commit (409), nor release it with `NOT_SENT` (refused): nothing shows the
  retry or the release comes from the payer that holds it;
- its post-commit is registered like any other payer's, flagged `RECEIVED_IN_WRONG_STATUS`, and
  changes nothing;
- an unattributed reservation (taken through the status-update API) still matches nobody.

ACH already requires `payer.info`. On FedNow, RTP and Solana it stays optional on the wire, and
sending it is what lets a payer retry, release and report cleanly. INTERPRETATION and
BEST-PRACTICES say so.

### Pre-commit on every rail

- A pre-commit goes through the acceptance gate at the payee's clock, and takes the reservation.
- While a reservation holds, another payer's pre-commit is refused with 409.
- The holder may retry its pre-commit (same payer, as defined above), and the retry is judged again.
- A refused retry from the holder releases the reservation: the QR Code returns to `ACTIVE` in the
  same transaction.
- Winning a pre-commit on a lapsed reservation emits `payment.initiated` for the new holder.

### A post-commit on an `ACTIVE` QR Code reserves it

A payer that skipped the pre-commit has still paid. When its post-commit reaches a QR Code that is
`ACTIVE` (effective status, so a lapsed reservation counts), it is accepted, never judged on amount
or time, and in one transaction:

- the QR Code moves to `PAYMENT_INITIATED`, held by that payer (`payer.info` and certificate
  subject, ADR-0021), so no other payer can now start paying it;
- `payment.initiated` is published, then, on a blockchain, `payment.sent` registers the hash;
- on a bank rail only `payment.initiated` is published: the payment itself carries the QR Code id
  (`QRCD`), and bank rails never emit `payment.sent`.

A contradictory `PAYMENT_INITIATED` carrying a hash takes this same path.

### Every notification records the QR Code's status when it arrived

Each accepted notification is stored with **the QR Code's effective status at the instant it was
received** (`received_in_status`). For a post-commit the right status is `PAYMENT_INITIATED`, held by
the same payer. **Any other status is bad behaviour by the payer:**

| Status at receipt | What the payer did |
|---|---|
| `ACTIVE` | It skipped the pre-commit, or paid after its reservation lapsed |
| `PAYMENT_INITIATED`, held by another payer | It paid while someone else held the QR Code |
| `PAID` | It paid a QR Code that was already paid |
| `CANCELLED` | It paid a QR Code the payment system had cancelled |

The row also stores the `deviations` codes below, so a post-commit received while another payer
held the QR Code (status `PAYMENT_INITIATED`, yet wrong) is visible on the row too. Together they
serve the payee: it can count misbehaviour per payer (`payer.info` and certificate subject are on the
same row) and take the numbers to the payer, so the payer fixes its side.

### A post-commit on a closed or held QR Code is registered, and changes nothing

A post-commit that reaches a QR Code already `PAID` or `CANCELLED`, or one held by another payer, is
accepted, never judged on amount
or time, and answered with a signed acknowledgement. Its hash is registered in the outbox as a
`payment.sent` with the flag below. **The status does not change:** a closed QR Code never reopens, and the holder keeps its
reservation. A pre-commit from that payer is still refused with 409, so an honest payer is still
stopped before it pays.

On a bank rail it publishes no `payment.sent` (bank rails never do); the notification row still
records it, with its status at receipt.

This may save the payment system hours: money arrives for a bill it already closed, and the event
tells it which QR Code, which payer, and that the payer was wrong.

### The registration says when the payer broke the rules

An event published for a post-commit carries an optional **`deviations`** list, and the status the
QR Code had when the notification arrived (**`receivedInStatus`**). The hash is registered either way;
these fields only describe how it arrived.

| Code | When |
|---|---|
| `RECEIVED_IN_WRONG_STATUS` | The post-commit reached a QR Code that was not `PAYMENT_INITIATED` held by this payer. `receivedInStatus` says which status it found. |
| `ACTION_CONTRADICTS_HASH` | `blockchain.action = PAYMENT_INITIATED`, but the message carries a hash. |

- **Both may appear together,** on every event of the transition (`payment.initiated` and
  `payment.sent` when a post-commit reserves an `ACTIVE` QR Code).
- **`deviations` is absent when the payer followed the rules.** It is never an empty list.
- **The fields are additive.** Consumers already ignore fields they do not know
  ([ENDPOINTS.md](../../ENDPOINTS.md)), so `schemaVersion` moves from `1.0` to `1.1`, not to a new major.
- **The codes are a closed list owned by this ADR.** A new code is added here, never invented at a
  call site.
- The same codes are logged with the warning.

### Open point

**Does a reservation lapse after a post-commit?** Today it keeps the pre-commit's window (90 s by
default; Java's `SENT` does not restamp it). Once the window passes the QR Code reads `ACTIVE`, and a
second payer can be approved although money has already moved.

The rule above on `payer.info` makes this sharper: a holder that sent no `payer.info` cannot be
recognised when it reports its own payment, so its post-commit changes nothing, and its reservation
lapses after 90 s with the money already sent.

Proposal: once **any** hash is registered for a QR Code that is `ACTIVE` or `PAYMENT_INITIATED`,
whoever sent it, the reservation no longer lapses (`reserved_until` takes the "no lapse" sentinel).
The holder does not change. It ends only when the payment system marks the QR Code `PAID`,
`CANCELLED` or `ACTIVE`.


## Consequences

- One rule on every rail: the evidence decides the phase. The Solana code changes to follow ADR-0004
  (Story 2.4 of the rewrite breakdown), so a contradictory message is no longer refused by the
  acceptance gate.
- A misbehaving payer is named in the log and in the stored record, and its money is still
  traceable.
- The two-payer race is closed on every rail, FedNow and RTP included.
- A payer that skips the pre-commit is not refused: its post-commit reserves the QR Code and
  registers its hash. FedNow/RTP payers that send only a post-commit today keep working, and their
  courtesy notification now takes the QR Code out of circulation.
- The payment system can treat a deviating registration differently (hold it for review, for
  example) without X9.150 refusing anything.
- ADR-0035's transition map gains rows: post-commit on `ACTIVE` (bank rail: `payment.initiated`;
  blockchain: `payment.initiated` then `payment.sent`), and the contradictory `PAYMENT_INITIATED`.
- The change ships in Java before the freeze (Stories 2.4 and 2.5), and Go ports it through the
  ADR-0035 transition map.

## Alternatives rejected

- **Let `blockchain.action` decide the phase on blockchains** (an earlier draft of this ADR). It
  refuses a contradictory message, so the payee loses the hash of money that has already moved.
- **Keep ADR-0021's certificate-only fallback.** Two customers of one PSP could retry, release or
  report for each other's reservation: the collision the reservation exists to prevent.
- **Refuse a post-commit on a closed or held QR Code** (what Java does, with 400 or 409). The money has moved
  anyway; refusing only hides it from the payment system.
- **Refuse a second or later hash.** It hides the one signal that a QR Code was paid twice.
- **Keep only the latest hash.** Same loss, more quietly.
- **Refuse a post-commit on an `ACTIVE` QR Code** (an earlier draft of this ADR). The money has
  moved; refusing leaves the QR Code open to a second payer and the payment system without the hash.
- **Mark the QR Code paid on a post-commit.** X9.150 cannot see funds arrive; only the payment
  system that receives them can.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
