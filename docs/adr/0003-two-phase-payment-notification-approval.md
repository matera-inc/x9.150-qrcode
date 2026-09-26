# ADR-0003 — Two-phase payment-notification approval

- **Status:** Accepted
- **Date:** 2026-09-26
- **Context:** `supporting-payment-notifications`

## Context

A payment notification from the payer's PSP is not always a report of something that happened. When it
arrives **before funds move** (ACH announcement, blockchain pre-commit) it is effectively a *request
for permission*, and the biller may have reasons to refuse: the amount does not match, the QR is
already being paid, or — in future — the source wallet fails screening.

## Decision

A QR may opt into **`mode: APPROVE`** (ADR-0006). For such a QR, X9.150 acts as the **coordinator of a
two-phase commit** between the payer's PSP and the biller:

1. **Prepare** — verify the JWS and CA allowlist, run the domain guards, then take the atomic
   `ACTIVE → PAYMENT_INITIATED` lock (ADR-0002).
2. **Vote** — hand the held request to the biller, which is long-polling
   `GET /api/v1/payment-approvals`, and wait for `POST /api/v1/payment-approvals/{paymentId}`.
3. **Commit or abort** — `ACCEPT` keeps the lock and emits `payment.initiated`; `REFUSE` aborts the QR
   back to `ACTIVE` and returns a 4xx to the payer.

**Only a pre-funds notification may be refused** (ADR-0004 defines how that is identified). A
post-commit notification is recorded and always answered 200 — refusing it would be a lie about money
that has already moved.

## Consequences

- **Prepare before asking, not after.** A second payer racing the same QR gets a synchronous 409 while
  the first is pending, so the biller never reasons about concurrency — "is this already being paid"
  is answered by the database, atomically.
- **No new status.** `PAYMENT_INITIATED` *is* the prepared state; refusal reuses the same conditional
  update as expiry.
- **The lock cannot leak.** Refusal aborts; no verdict times out and aborts; a crash mid-flight is
  reclaimed by the expiry sweep.
- **The approval endpoints are management-surface** (`/api/v1/…`), never `/pub` — they are the biller's
  control channel, and putting them on the payer-facing surface would invite the payer's side of the
  network to vote on its own payment.
- Holding a payer request is cheap because `spring.threads.virtual.enabled` is already on.
- **Open question:** `POST /payment-approvals` authorizes money movement, which is categorically
  different from the read-or-report endpoints the "open API, protect at the edge" posture was written
  for. Binding approval to a token minted at QR creation is the proposed answer.

## Fail closed

```yaml
x9.payments.approval:
  timeout: PT5S
  on-timeout: REFUSE       # REFUSE | ACCEPT
  on-no-listener: REFUSE
```

`REFUSE` is the default: an unanswered approval means the biller could not assert the payment is
wanted, and initiating anyway defeats the purpose of asking. The cost is stark and must be documented —
**a biller whose consumer is down cannot be paid.** A biller who prefers availability should choose
`NOTIFY` outright rather than `APPROVE` + `on-timeout: ACCEPT`, which is the same thing with extra
latency.

## Alternatives rejected

**X9.150 decides alone** (amount and status checks only): sufficient today, but forecloses the
screening cases the biller will want, and puts business policy in a protocol component.

**Ask the biller before taking the lock:** leaves a window where two payers are both pending approval
for the same QR, and pushes concurrency reasoning onto every biller implementation.

**Allow refusal after funds move:** unimplementable. The money is gone; a refusal would misrepresent
reality and strand a reconciliation.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
