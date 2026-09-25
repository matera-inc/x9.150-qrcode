# QR Code State Machine

The lifecycle of a QR Code (payment payload) is modeled by `QRCodeStatusEnum`
(`ACTIVE`, `INITIATED`, `PAID`, `CANCELLED`) and the transition methods on
`QRCodeEntity` (`pay`, `cancel`, `reactivate`, `notifyPayment`), guarded by
`QRCodeEntityValidator`.

## States

| State | Meaning | Terminal |
|-------|---------|----------|
| **ACTIVE** | Created and available for payment (initial state). | No |
| **INITIATED** | A payment is in flight — announced/initiated but not yet settled. | No |
| **PAID** | Payment settled. | **Yes** |
| **CANCELLED** | Payload cancelled by the biller. | **Yes** |
| **PARTIALLY_PAID** *(proposed, not implemented)* | Part of the amount due is settled; the rest is still payable. See [Proposed: partial payment](#proposed-partial-payment-partially_paid). | No |

## Core lifecycle

```mermaid
stateDiagram-v2
    [*] --> ACTIVE : create()

    ACTIVE --> INITIATED : notifyPayment(…, INITIATED)
    INITIATED --> ACTIVE : reactivate()

    ACTIVE --> PAID : pay(paymentDetails)
    INITIATED --> PAID : pay(paymentDetails)

    ACTIVE --> CANCELLED : cancel()
    INITIATED --> CANCELLED : cancel()

    PAID --> [*]
    CANCELLED --> [*]

    note right of INITIATED
        Payment in flight. notifyPayment(data)
        records the notification and bumps the
        revision WITHOUT changing status.
    end note
```

### Transition rules (guards)

| Transition | Method | Allowed from | Notes |
|-----------|--------|--------------|-------|
| create → ACTIVE | `create()` | — | Factory; initial status is always ACTIVE |
| → PAID | `pay(paymentDetails)` | ACTIVE, INITIATED | `paymentDetails` **required** |
| → CANCELLED | `cancel(paymentDetails)` | ACTIVE, INITIATED | `paymentDetails` **must be null** |
| → ACTIVE | `reactivate(paymentDetails)` | **INITIATED only** | `paymentDetails` **must be null** |
| record / → INITIATED | `notifyPayment(data[, status])` | see below | Records notification, bumps revision |

`PAID` and `CANCELLED` are terminal — `pay()`, `cancel()` and `notifyPayment()`
all reject a QR Code that is not ACTIVE or INITIATED.

## Payment-notification effects (by network)

`PaymentNotificationQRCodeUseCase` dispatches on `$.payment.network`, and
`QRCodeEntityValidator` enforces the preconditions:

| Network | Precondition | Status effect | `transactionId` |
|---------|--------------|---------------|-----------------|
| **FedNow / RTP** (instant) | QR = ACTIVE | records (status unchanged) | ISO 20022 End-to-End ID |
| **ACH** | QR = ACTIVE; payer info + expectedDate present; no blockchain data | ACTIVE → **INITIATED** | optional |
| **Blockchain** (Polygon/Solana/Ethereum/Bitcoin) | QR supports a crypto network; blockchain data present | see below | see below |

> FedNow / RTP note: the payment notification is **not required for reconciliation**
> on these rails — the QR Code ID travels inside the ISO 20022 payment message
> itself, so the Payee's PSP can reconcile directly from that message. When a
> notification *is* sent for FedNow/RTP it is essentially a **courtesy** and/or a
> way to convey **segregation between the principal amount and the tip** (see
> `$.payment.amount` vs `$.payment.tipAmount`).

> Settlement note: reaching **PAID** today happens through the biller lifecycle
> endpoint `PUT /api/v1/payment-request/{id}/status-update` (`pay()`). A payment
> notification on its own moves a QR to `INITIATED` (ACH, blockchain pre-commit)
> or records details without changing status; whether a blockchain post-commit
> (`SENT`) notification should auto-transition `INITIATED → PAID` is under review.

## Blockchain: pre-commit vs post-commit

For blockchain networks the notification's `blockchain.action` distinguishes the
on-chain stage, and this maps directly to whether a transaction reference exists:

- **Pre-commit** = **no txHash yet**. `action = PAYMENT_INITIATED`. The transaction
  has not been committed to the chain, so there is no transaction hash. Requires the
  QR to be `ACTIVE`; moves it to `INITIATED`.
- **Post-commit** = **txHash present**. `action = SENT`. The transaction has been
  committed to the chain and therefore has a transaction hash. Requires the QR to be
  `INITIATED`, and the transaction hash **must** be supplied.
- `action = NOT_SENT` — the payment did not proceed; requires the QR to be `INITIATED`.

### ⚠️ Where the txHash goes

There is **no dedicated `txHash` field**. For blockchains, the on-chain
**transaction hash is carried in the network-agnostic `$.payment.transactionId`
field** (ANSI X9.150-2026 §2.5, "Payment Transaction ID"). The same field carries
the ISO 20022 End-to-End ID for FedNow/RTP and the Trace Number for ACH.

So the pre/post-commit rule, stated precisely:

> **Blockchain pre-commit** ⇔ `$.payment.transactionId` is absent (`action = PAYMENT_INITIATED`).
> **Blockchain post-commit** ⇔ `$.payment.transactionId` is present (`action = SENT`) — this is the txHash.

```mermaid
stateDiagram-v2
    direction LR
    [*] --> ACTIVE
    ACTIVE --> INITIATED : PAYMENT_INITIATED (pre-commit, no transactionId/txHash)
    INITIATED --> INITIATED : SENT (post-commit, transactionId = txHash REQUIRED)
    INITIATED --> INITIATED : NOT_SENT
    INITIATED --> ACTIVE : reactivate()
    INITIATED --> PAID : pay() via status-update

    note right of INITIATED
        SENT/NOT_SENT are recorded on the QR
        (revision bumped) without changing status
        in the current implementation.
    end note
```

## Proposed: partial payment (`PARTIALLY_PAID`)

> **Status: proposed — not implemented.** Nothing in this section exists in the code yet; the
> sections above describe current behaviour. The decision and its reasoning are in Matera Workspace
> **ADR-028** (`workspace/docs/adr/ADR-028-x9150-payment-notifications-partial-payment.md`).

**Why.** A QR Code can offer several currencies, for example USD and USDC. A payer may settle part of
the bill in one currency and leave the rest. This service is the only one that serves the payload to
the payer, so it must know what is still owed: the next fetch of `/pub/api/v1/loc/{id}` has to stamp
the **remaining** amount, not the full one.

### Amounts

- `amountPaid`: the sum of settled payments, in the **invoice currency's minor units**, plus the
  list of settled payments behind it.
- `amountRemaining = amountDue − amountPaid`. It's derived, not stored.
- A USDC settlement (scale 6) credits `floor(usdcMinor / 10^4)` US cents (scale 2): never more than
  was received. The up-to-0.009999 USDC left over is a known overpayment, not a debt.
- Payload retrieval stamps `amountRemaining` (converted per currency, see
  `PLAN-NON-USD-PEGGED-CURRENCIES.md`) in every payment method.

### Lifecycle with partial payment

```mermaid
stateDiagram-v2
    [*] --> ACTIVE : create()

    ACTIVE --> INITIATED : notifyPayment(…, INITIATED)
    INITIATED --> ACTIVE : reactivate()

    ACTIVE --> PARTIALLY_PAID : pay(amount < remaining)
    INITIATED --> PARTIALLY_PAID : pay(amount < remaining)
    PARTIALLY_PAID --> PARTIALLY_PAID : pay(amount < remaining)

    ACTIVE --> PAID : pay(amount ≥ remaining)
    INITIATED --> PAID : pay(amount ≥ remaining)
    PARTIALLY_PAID --> PAID : pay(amount ≥ remaining)

    ACTIVE --> CANCELLED : cancel()
    INITIATED --> CANCELLED : cancel()
    PARTIALLY_PAID --> CANCELLED : cancel() — open question

    PAID --> [*]
    CANCELLED --> [*]

    note right of PARTIALLY_PAID
        amountPaid > 0 and amountRemaining > 0.
        Still payable: notifications are accepted
        and the payload stamps amountRemaining.
    end note
```

### Proposed transition rules

| Transition | Method | Allowed from | Notes |
|-----------|--------|--------------|-------|
| → PARTIALLY_PAID | `pay(paymentDetails)` with settled amount **<** `amountRemaining` | ACTIVE, INITIATED, PARTIALLY_PAID | Adds to `amountPaid`; appends to the settled-payments list |
| → PAID | `pay(paymentDetails)` with settled amount **≥** `amountRemaining` | ACTIVE, INITIATED, PARTIALLY_PAID | Any excess is recorded as overpayment |
| record / → INITIATED | `notifyPayment(data[, status])` | ACTIVE, INITIATED, **PARTIALLY_PAID** | A notification never changes `amountPaid` (see below) |
| → CANCELLED | `cancel(paymentDetails)` | ACTIVE, INITIATED; PARTIALLY_PAID **open** | Cancelling after a partial payment needs a refund flow and must keep `amountPaid` |

`paymentDetails` for `pay()` gains the **settled amount and currency**. Today it's all-or-nothing.

### A notification is a claim; settlement moves the amount

A payment notification is the payer PSP's claim. It records the payment in flight, but only a
**settled** amount, reported through `PUT /api/v1/payment-request/{id}/status-update` → `pay()`,
changes `amountPaid`. A failed or forged notification therefore can't make a customer owe less.

For blockchain payments, Workspace confirms settlement. It matches the tx hash in a post-commit
(`SENT`) notification's `$.payment.transactionId` against the deposit it saw on chain, then calls
status-update with the settled amount.

### Open questions (from ADR-028)

1. May a `PARTIALLY_PAID` QR be cancelled? If so, the paid part needs a refund flow.
2. On a merchant "refresh", should it reissue for `amountRemaining` only, or keep the same QR with
   refreshed rates?
3. How is a deposit matched when no notification arrives (no tx hash to join on)?

## Source references

- States: `x9-qrcode-domain/.../vo/enumerated/QRCodeStatusEnum.java`
- Transitions: `x9-qrcode-domain/.../entity/QRCodeEntity.java` (`pay`, `cancel`, `reactivate`, `notifyPayment`)
- Guards: `x9-qrcode-domain/.../entity/validator/QRCodeEntityValidator.java`
- Notification dispatch: `x9-qrcode-application/.../usecase/paymentnotification/PaymentNotificationQRCodeUseCase.java`
- Transaction ID field: ANSI X9.150-2026 §2.5

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
