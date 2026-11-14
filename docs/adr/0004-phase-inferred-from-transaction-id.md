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
