# ADR-0004 — Payment phase is inferred from the absence of a transaction id

- **Status:** Accepted
- **Date:** 2026-09-26
- **Context:** `supporting-payment-notifications`

## Context

ADR-0003 may refuse a notification only when funds have not yet moved, so the implementation must know
which phase a notification represents.

**ANSI X9.150 has no explicit pre-commit / post-commit marker.** An event type distinguishing the two
phases was proposed during the standard's definition and **rejected by the committee**. Conformant
implementations must therefore infer the phase.

### Amendment, 2026-11-14: what each phase is for, per rail, and where the code departs

**Open until now:** this ADR said how to tell the two phases apart, but not why both exist on every
rail, nor what links a payment to its QR Code when the rail cannot.

**Closed:**

- **Pre-commit (no `transactionId`) is always used, on every rail.** Its reservation is what stops two
  payers from paying the same QR Code at the same time. **Post-commit (with a `transactionId`)** carries
  the reference that links the payment to the QR Code, and is recorded, never judged again. This
  is the rule below — *vetoable ⇔ no `transactionId`* — with its purpose stated.
- **What carries the link, per rail.** FedNow / RTP and ACH carry the QR Code id in the payment
  itself (`QRCD` in the pacs.008 and in the ACH addenda, ANSI X9.150-2026 A.6 and A.7), so their
  post-commit is a courtesy. Solana carries it only in an optional memo that some custody providers do
  not expose, and most chains have no memo at all, so on blockchains **the post-commit is the link**, and the
  pre-commit / post-commit protocol is the reconciliation mechanism for every chain, memo or not.

**Where the code departs from this ADR today** (recorded, not changed here):

- **FedNow / RTP have no pre-commit:** every notification must carry an End-to-End ID, so the table's
  "courtesy, after the fact" is the only path, and no reservation is ever taken on these rails.
- **ACH post-commit is judged as a pre-commit:** every ACH notification goes through the acceptance gate, Trace
  Number or not.
- **Solana is keyed on `blockchain.action`,** not on the transaction id: a `PAYMENT_INITIATED`
  carrying a transaction id is still treated as a pre-commit, contrary to the disagreement rule below.

All three are closed by Story 2.4 of the rewrite breakdown
([docs/rewrite/EPICS-AND-STORIES.md](../rewrite/EPICS-AND-STORIES.md)), under
[ADR-0036](0036-pre-commit-on-every-rail-and-every-transaction-hash-is-recorded.md) (proposed),
which **keeps** this ADR's rule on every rail:

- **`blockchain.action` stays a corroborating signal.** The Solana code is brought back in line with
  the disagreement rule below.
- **A `PAYMENT_INITIATED` carrying a hash is bad behaviour by the payer,** and its hash is still
  recorded as a post-commit, with a warning. Funds that arrive in the right amount with a matching
  hash are enough for the payment system to mark the QR Code paid.
- **Every hash is recorded,** a second one included, so the payment system can detect a duplicate
  payment.

## Decision

Infer it from `$.payment.transactionId`:

> **A transaction id exists only if the transaction was committed.** For a blockchain that id *is* the
> txHash, which cannot exist before the transaction reaches the chain.
>
> **Vetoable ⇔ no `transactionId` ⇔ funds have not moved.**

| Rail | First notification | `transactionId` | Vetoable? |
|---|---|---|---|
| Blockchain pre-commit | `action = PAYMENT_INITIATED` | absent | **Yes** |
| Blockchain post-commit | `action = SENT` | present (txHash) | No |
| ACH | pre-settlement announcement | optional | **Yes** |
| FedNow, RTP | courtesy, after the fact | mandatory (ISO 20022 E2E id) | No |

`blockchain.action` is a **corroborating** signal, not the authority. On disagreement:

- `SENT` **without** a transaction id → **reject**; a committed transaction with no hash is not a state
  that can exist.
- `PAYMENT_INITIATED` **with** a transaction id → treat as **post-commit** (the hash is the physical
  truth), record it, offer no veto, and log the disagreement for the operator.

## Consequences

- One rule covers every rail, including rails added later.
- The guarantee ADR-0003 rests on holds regardless of how the payer labelled the message: **X9.150
  never invites a refusal for money that has already moved.**
- A payer that mislabels `action` cannot trick the system into vetoing a settled payment.
- **Do not design as if a phase marker might appear.** It was proposed and refused; this inference is
  the conformant approach, not a workaround awaiting a fix.
- `STATE-MACHINE.md` already documents *that* the inference is made; it must also record *why*, so the
  question is not re-litigated.

## Alternatives rejected

**Trust `blockchain.action` as authoritative:** it is payer-supplied and can disagree with physical
reality; treating it as truth would let a mislabelled message open a veto on settled funds.

**Add our own phase field to the notification:** would deviate from the standard's wire contract and
break interoperability with any conformant payer.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
