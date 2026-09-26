# Plan — Payment-Notification Hardening (atomic initiation, embedded outbox, pull-based events)

Status: **Proposed** (design/plan only — not yet implemented)
Branch: `supporting-payment-notifications`
Scope: `x9-qrcode-domain` (events, transition guards), `x9-qrcode-application` (new ports, use cases),
`x9-qrcode-infrastructure` (conditional Mongo updates, outbox drain, events API, dynamic public host),
plus OpenAPI, Helm chart, Compose, playground and docs.

## 0. How this is documented

This plan is the **execution document** — phases, deliverables, sequencing — and it dies when the work
ships. The **decisions** behind it, with their rejected alternatives, live as ADRs in
[`docs/adr/`](docs/adr/README.md) and outlive it. Behaviour, once built, graduates into the reference
docs (`STATE-MACHINE.md`, `ENDPOINTS.md`, a new `EVENTS.md`, `openapi.yaml`).

| Decision | ADR |
|---|---|
| Events leave by pull, not push; no broker client in the service | [ADR-0001](docs/adr/0001-events-leave-by-pull-not-push.md) |
| Atomic single-document transitions with an embedded outbox | [ADR-0002](docs/adr/0002-atomic-transitions-with-embedded-outbox.md) |
| Two-phase payment-notification approval | [ADR-0003](docs/adr/0003-two-phase-payment-notification-approval.md) |
| Phase inferred from the absence of a transaction id | [ADR-0004](docs/adr/0004-phase-inferred-from-transaction-id.md) |
| Amounts stay `int64` minor units | [ADR-0005](docs/adr/0005-amounts-stay-int64.md) |
| Notification opt-in is additive; no `/v2` | [ADR-0006](docs/adr/0006-notification-opt-in-is-additive.md) |
| Configurable CA allowlist | [ADR-0007](docs/adr/0007-configurable-ca-allowlist.md) |
| Status-update as the ISO 20022 (pacs.008) entry point | [ADR-0008](docs/adr/0008-status-update-is-the-iso20022-entry-point.md) |

Where this plan and an ADR disagree, **the ADR wins** — it is the accepted decision; the plan is how we
get there. Open questions (§12) are decisions **not yet made**, and each becomes an ADR when it is.

---

## 1. Goal

The first production adopter will integrate X9.150 into a banking platform. X9.150 stays **fully
independent**: it knows nothing about the adopter, shares no database, and publishes facts any system
can consume. This plan turns that into six generic product capabilities:

1. A payment can be initiated **exactly once** — concurrent notifications get a synchronous rejection.
2. An abandoned initiation **cannot lock a QR forever**.
3. State change and event emission are **one atomic act** (embedded transactional outbox).
4. The emitted events are a **public, versioned contract**.
5. Events leave the system by **pull, not push** — a cursor-paged, long-polling API over an
   append-only event log. No broker inside X9.150 (§3.7).
6. All of it is **proven by tests**, and the docs match.

Two further requirements were added during planning and are carried here:

7. The public callback URL must be resolved **at payload-response time**, so an ephemeral
   Cloudflare tunnel hostname works without a restart and without stale data in the database (§9).
8. The result must deploy cleanly on **k3s** from the Docker Hub image, while still running under
   plain Docker Compose (§10).

### 1.1 Deliberate deviations from the brief

| Brief | This branch | Why |
|---|---|---|
| Publisher targets: Kafka, webhook, none | **`none` only** — the brief's own fallback (`GET /events?after=<cursor>`) promoted to the primary and only mechanism | The brief already sanctions it: *"Kafka is an integration option, not a requirement. X9.150 must run with Mongo alone."* X9.150 records facts and serves them; consumers pull with their own cursor and push to whatever they run (§3.7). A reference bridge ships as a separate companion artifact (**Q14**), so the messaging story is demonstrated without a broker client inside a payment service. |
| `TODO.md` proposes Spring Cloud Stream binders (Kafka *and* RabbitMQ) | **No broker abstraction at all** | Spring Cloud Stream's binder abstraction solves transport portability — a problem X9.150 no longer has, because it ships no transport. A consumer that pulls chooses its own stack with zero configuration here. **`TODO.md`'s "payment expected event" entry is superseded by this plan and must be rewritten.** |
| "No replica set needed" | Replica set stays **required**, but no *new* multi-document transaction is introduced | The app already requires a replica set for `MongoTransactionManager` (README, RUNNING.md, HIGH-AVAILABILITY.md, both Compose files, Testcontainers). Dropping that is a separate change with its own doc blast radius. The brief's real requirement — outbox and state change atomic **without** a distributed transaction — is met by single-document updates. Removing the replica-set requirement altogether is filed as open question **Q1**. |

---

## 2. Where the code is today (baseline)

| Concern | Today | File |
|---|---|---|
| Notification handling | `findById` → mutate entity → `save()` (read-modify-write) | `x9-qrcode-application/.../paymentnotification/PaymentNotificationQRCodeUseCase.java:32` |
| Concurrency control | `@Version` optimistic locking only; a lost race throws `OptimisticLockingFailureException` → HTTP 500 | `.../mongodb/model/QRCodeMongoPersistenceModel.java:47` |
| Persistence writes | Full-document replace through `MongoRepository.save()` | `.../mongodb/QRCodeMongoRepository.java:32` |
| Events | **None.** Nothing is published on any transition | — |
| Status enum | `ACTIVE`, `PAYMENT_INITIATED`, `PAID`, `CANCELLED` | `domain/vo/enumerated/QRCodeStatusEnum.java:13` |
| Transitions | `pay()`, `cancel()`, `reactivate()`, `notifyPayment()` | `domain/entity/QRCodeEntity.java` |
| Guards | `validatePaymentNotification*` | `domain/entity/validator/QRCodeEntityValidator.java:99` |
| Error mapping | Every exception in the notification endpoint is wrapped in `BusinessRuleException` → **HTTP 400** | `.../web/controller/PublicEndpointsController.java:92` |
| Notification URL in payload | Already computed per request from config (kind `DEFAULT`), or the creditor's own stored URL (kind `EXTERNAL`) | `.../retrievepayload/RetrieveQRCodePayloadUseCase.java:103` |
| Scheduling | `@EnableScheduling` already on | `.../configuration/SchedulingConfiguration.java` |
| Doc TTL reaper | `ttl` field = `validUntil`, TTL index `expireAfter = 30S` — **QR documents are deleted 30s after `validUntil`** | `QRCodeMongoPersistenceModel.java:44`, `QRCodeMongoDocumentMapper.java:54` |

### 2.1 Defects found while reading the baseline (fix in this branch)

- **`Base`, `XRP` and `Arc` notifications are silently accepted and do nothing.**
  `PaymentNotificationQRCodeUseCase.processPaymentNotification` switches over `NetworkEnum` with no
  `default` branch and no case for `BASE`, `XRP`, `ARC`. A switch *statement* over an enum need not be
  exhaustive, so those three fall through: `notifyPayment()` is never called, no validation runs, the
  unchanged entity is saved and the endpoint returns **200 OK**. (`QRCodeEntityValidator` does have a
  `default -> throw`, but it is only reached from inside `notifyPayment()`, which is never called.)
  The switch is being rewritten for atomic initiation anyway — make it exhaustive with an explicit
  `default -> throw`.
- **Status naming drift in docs.** `STATE-MACHINE.md` documents the state as `INITIATED`; the code and
  `AGENTS.md` say `PAYMENT_INITIATED`. That doc is rewritten here (§11) — fix the naming.
- **A lost optimistic-lock race is a 500, not a 409.** Even before this work, two simultaneous
  notifications produce an `OptimisticLockingFailureException` that no `@ExceptionHandler` covers.

---

## 3. Target design

### 3.1 Document shape (`qrcodes` collection)

Two new top-level fields. Existing fields are untouched.

```jsonc
{
  "_id": "…", "status": "PAYMENT_INITIATED", "revision": 7,

  "payment_initiation": {                  // present only while status = PAYMENT_INITIATED
    "payment_id":     "UUID",              // server-assigned, the lock token
    "transaction_id": "XYZ.USBK.X9aTf72qLm.1", // payer's id when supplied (nullable)
    "network":        "ACH",
    "amount":         5000,
    "currency":       "USD",
    "initiated_at":   ISODate("…"),
    "expires_at":     ISODate("…")         // initiated_at + x9.payments.initiation-ttl
  },

  "outbox": [                              // FIFO; empty array is removed, never left as []
    { "event_id": "UUID", "type": "payment.initiated",
      "occurred_at": ISODate("…"), "attempts": 0,
      "payload": { /* the full public event, §3.6 */ } }
  ]
}
```

> **`expires_at` and `occurred_at` MUST be BSON dates, not strings.**
> The app registers `OffsetDateTime ↔ String` converters (`MongoDbConfiguration`), and
> `OffsetDateTime.toString()` drops trailing zero fields — `2026-09-26T10:00Z` vs
> `2026-09-26T10:00:05.100Z`. Lexicographic `$lt` on those strings is **wrong** (`'Z' > ':'`), which
> would silently break the expiry sweep. Map these two fields as `java.time.Instant` (as the existing
> `ttl` field already is) so `$lt` compares numerically.

### 3.2 New / changed types, by module

**`x9-qrcode-domain`** (pure Java, no Spring)

| Type | Kind | Purpose |
|---|---|---|
| `domain/event/PaymentEvent` | record | The public fact. Factories `initiated(...)`, `cleared(...)`, `expired(...)`, `failed(...)`. |
| `domain/vo/enumerated/PaymentEventTypeEnum` | enum | `PAYMENT_INITIATED("payment.initiated")`, `PAYMENT_CLEARED("payment.cleared")`, `PAYMENT_EXPIRED("payment.expired")`, `PAYMENT_FAILED("payment.failed")`, with `fromValue`. |
| `domain/vo/PaymentInitiationVO` | record | `paymentId`, `transactionId`, `network`, `amount`, `currency`, `initiatedAt`, `expiresAt`; self-validating; `isExpired(at)`. |
| `QRCodeEntity` | change | Hold `paymentInitiation`; `notifyPayment(...)` keeps validating, but the *status transition* is expressed as a command the repository applies conditionally (§3.3). Add `restore(...)` parameter. |
| `QRCodeEntityValidator` | change | Unchanged guards, plus: a QR already initiated by a **different** `paymentId` fails the precondition. |

**`x9-qrcode-application`** (depends only on domain)

| Type | Kind | Purpose |
|---|---|---|
| `app/repository/QRCodeRepository` | change | Add the four conditional operations (§3.3). |
| `app/repository/PaymentOutboxRepository` | new port | `List<PendingEvent> findPending(int limit)`, `void ack(QRCodeIdVO, UUID eventId)`, `void recordAttempt(...)`. |
| `app/service/PaymentEventPublisher` | new port | `void publish(PaymentEvent event)`. Kept as the extension seam for anyone wanting in-process push; **we ship only the log-backed implementation**. |
| `app/service/PaymentEventLog` | new port | `void append(PaymentEvent)`, `EventPage read(String afterCursor, int limit)` — backs `GET /events`. |
| `app/service/PublicHostProvider` | new port | `String currentHost()` — §9. |
| `app/exception/PaymentConflictException` | new | Carries the conflicting `paymentId`/status. Mapped to **409**. |
| `app/usecase/paymentnotification/PaymentNotificationQRCodeUseCase` | rewrite | §3.4. Output becomes a record (`paymentId`, `status`, `idempotentReplay`), not `Boolean`. |
| `app/usecase/relayoutbox/RelayPaymentEventsUseCase` | new | Pure orchestration of drain → publish → ack. |
| `app/usecase/expireinitiation/ExpirePaymentInitiationsUseCase` | new | Sweep. |
| `app/usecase/listevents/ListPaymentEventsUseCase` | new | `GET /events`. |

**`x9-qrcode-infrastructure`**

| Type | Purpose |
|---|---|
| `persistence/mongodb/QRCodeMongoRepository` | Implement the conditional ops via `MongoTemplate.findAndModify`. |
| `persistence/mongodb/PaymentOutboxMongoRepository` | Partial-index query + `$pull` ack. |
| `persistence/mongodb/PaymentEventLogMongoRepository` | `payment_events` collection, upsert by `_id = eventId`. |
| `persistence/mongodb/model/…` | `PaymentInitiation`, `OutboxEvent` nested models; `PaymentEventMongoPersistenceModel`. |
| `service/events/EventLogPaymentEventPublisher` | The only shipped implementation: appends to `payment_events`. |
| `web/controller/PaymentEventsController` | `GET /pub/api/v1/events` — cursor paging + long-poll (§4.1). |
| `service/events/PaymentEventRelayScheduler` | `@Scheduled` driver calling the relay use case. |
| `service/events/PaymentInitiationExpiryScheduler` | `@Scheduled` driver calling the sweep use case. |
| `service/host/StaticPublicHostProvider`, `CloudflareQuickTunnelPublicHostProvider` | §9. |
| `configuration/EventsConfiguration`, `property/EventsProperties`, `property/PaymentsProperties` | Wiring + config. |
| `web/controller/PaymentEventsController` | `GET /pub/api/v1/events`. |
| `web/controller/advice/…` | `CONFLICT` error type; handle `PaymentConflictException` and `OptimisticLockingFailureException`. |

### 3.3 The four conditional updates

All four are a single `findAndModify` — one document, one round trip, no transaction. Each `$set`s
state **and** `$push`es its event in the same update, so an event cannot exist without its state change
and vice versa. Each also `$inc`s `revision` (Spring Data's `@Version` only auto-increments on
`save()`, not on a field-level update) and sets `revised_at`.

```js
// (A) INITIATE — ACTIVE -> PAYMENT_INITIATED
filter: { _id: qrId, status: "ACTIVE" }
update: { $set:  { status: "PAYMENT_INITIATED",
                   payment_initiation: {…}, "payment_notification.data": {…},
                   revised_at: now },
          $inc:  { revision: 1 },
          $push: { outbox: <payment.initiated> },
          $unset:{ ttl: "" } }                      // see §3.8
// null  -> someone else holds it (or it is PAID/CANCELLED) -> 409, unless idempotent replay (§3.4)

// (B) SETTLE — -> PAID
filter: { _id: qrId, status: "PAYMENT_INITIATED", "payment_initiation.payment_id": pid }
//   biller-driven settle via PUT /status-update with no paymentId keeps the existing latitude:
//   { _id: qrId, status: { $in: ["ACTIVE", "PAYMENT_INITIATED"] } }
update: { $set: { status: "PAID", payment_details: {…}, revised_at: now },
          $unset: { payment_initiation: "", ttl: "" },
          $inc: { revision: 1 },
          $push: { outbox: <payment.cleared> } }

// (C) RELEASE — PAYMENT_INITIATED -> ACTIVE (failure / explicit reactivate)
filter: { _id: qrId, status: "PAYMENT_INITIATED", "payment_initiation.payment_id": pid }
update: { $set: { status: "ACTIVE", revised_at: now },
          $unset: { payment_initiation: "", ttl: "" },
          $inc: { revision: 1 },
          $push: { outbox: <payment.failed> } }

// (D) SWEEP — expired PAYMENT_INITIATED -> ACTIVE   (one doc at a time, looped)
filter: { status: "PAYMENT_INITIATED", "payment_initiation.expires_at": { $lt: now } }
update: same as (C) but $push: <payment.expired>
```

**Expiry is enforced twice, on purpose.** The brief allows either a sweep or an
expiry-aware initiation condition; doing both costs one extra conditional update and removes the
window where a payer is refused because the sweep is a minute behind. Rather than folding the
expired-lock case into filter (A) — which cannot work in one step, because the superseded `paymentId`
needed for the `payment.expired` event is not known until the document is read — **`initiate` first runs
(D) scoped to this `_id`, then runs (A)**. Step 1 is the identical operation the background sweep runs,
so there is exactly one code path for reclaiming, and each step is individually atomic. If two payers
race on a reclaim, one wins (A) and the other gets a 409 — the invariant holds either way.
### 3.4 The payment-notification protocol

This is the heart of the branch. A payment notification is **not a fact being reported** — for the
first notification it is a **request for permission**, and X9.150 brokers it between the payer's PSP
and the biller. The shape is a two-phase commit, with X9.150 as coordinator.

#### 3.4.1 Is a notification wanted at all?

Notifications are **not universally necessary**, because on several rails the QR id travels inside the
payment message itself and the payee reconciles directly from it:

| Rail | Carries the QR id | Notification needed? |
|---|---|---|
| FedNow, RTP | ISO 20022 message; the receiver can move the QR to `PAYMENT_INITIATED` on the **pacs.008** (the payment-feasibility test) | No — courtesy only |
| Solana | MEMO field | No, when MEMO is used |
| ACH, EVM chains (pre-commit) | Nothing until funds move | **Yes** — this is where the protocol earns its keep |

So **whoever creates the QR declares what they want**, per QR.

**The opt-in already exists — it does not need to be invented.** `paymentNotification` is an *optional*
object on the create request (`PaymentRequestAdditionalInfo`, no `required` entry), and
`QRCodeEntityValidator.validatePaymentNotification` already rejects a notification for a QR that has
none: *"paymentNotification is not configured for this QR Code"*. So **absent means "I do not want
payment notifications", today, with no change at all.** What is missing is only the *approval* dimension.

| Create request | Meaning | Status |
|---|---|---|
| `paymentNotification` **absent** | No notifications. A notification for this QR is rejected. | **Already the behavior** |
| `kind: DEFAULT` | Notifications accepted and recorded; **no biller vote**. | Already the behavior |
| `kind: DEFAULT` + `mode: APPROVE` | **Two-phase**: the pre-funds notification is held for the biller's verdict (§3.4.3). | **New** |
| `kind: EXTERNAL` | The creditor supplies its own endpoint; X9.150 does not handle the callback. | Already the behavior |

#### 3.4.1.1 Does this need a `/v2`? No.

Adding one optional field is a **backward-compatible change by the API's own published policy**. The
OpenAPI description lists, verbatim, among the changes it deems backward-compatible: *"Inclusion of new
optional parameters"*, *"Introduction of new optional fields in API"*, and *"Addition of new elements to
enumerations"*. A new version would be required only for a **removal**, a **retype**, or a **new
required field** — none of which this is. (The contract is additionally at `0.6.0-rc`, which reserves
even more latitude, but we do not need to invoke it.)

Two further reasons no migration is involved:

- **No default changes.** Existing callers who never sent `paymentNotification` keep getting exactly
  what they get today — no notifications. Existing callers sending `kind: DEFAULT` keep today's
  notify-without-vote behavior, because `mode` defaults to `NOTIFY`.
- **Nothing existing is re-interpreted.** The new field only *adds* a behavior that could not be
  expressed before.

**Recommendation: a new optional `mode` field, not a new `kind` enum value.** Both are sanctioned by
the policy, but `kind` answers *"who hosts the callback endpoint"* (`DEFAULT` = us, `EXTERNAL` = the
creditor) while approval answers *"does the biller get a vote"*. They are orthogonal, and folding them
into one enum immediately raises an unanswerable question — what is `EXTERNAL` + `APPROVE`? Keeping
them separate leaves `kind` meaning what it already means and makes the new dimension default cleanly.

```yaml
paymentNotification:
  kind: DEFAULT           # unchanged
  mode: APPROVE           # NEW, optional, defaults to NOTIFY
```

#### 3.4.2 Trust: which CAs may sign a notification

Today trust is a single truststore plus `x9.certificate.issuer-name: X9` (one string). The production
posture a bank wants is an **explicit allowlist of issuing CAs** — initially **DigiCert**, X9's PKI
partner — while the project must stay runnable by anyone with the bundled self-signed demo keystore.

```yaml
x9:
  certificate:
    trust:
      allowed-issuers:            # empty = accept anything the truststore chains to
        - "DigiCert"
      allow-self-signed: false    # true for local/dev; the demo keystore needs it
```

- Validation order is unchanged and still full X.509: chain to a trust anchor in the truststore,
  validity window, revocation — **then** the issuer allowlist as an additional narrowing filter.
- `allow-self-signed: true` is what makes the playground and the test suite work out of the box; it
  must log a loud startup WARN and be documented as non-production, exactly as the demo keystore is.
- A rejected issuer is a **401/403-class refusal**, distinct from a malformed or expired JWS, so an
  operator can tell "your CA is not on my list" from "your signature is broken".

#### 3.4.3 The two-phase exchange (policy = `APPROVE`)

```
Payer PSP                 X9.150 (coordinator)                Biller
    │                              │                             │ long-poll GET /payment-approvals
    │── POST notification #1 ─────►│                             │◄── held open
    │   (signed, no txHash)        │                             │
    │                              │ 1. verify JWS + CA allowlist│
    │                              │ 2. domain guards            │
    │                              │ 3. PREPARE: atomic          │
    │                              │    ACTIVE -> PAYMENT_INITIATED
    │                              │── hand the request over ───►│
    │            (request held)    │                             │ checks amount, currency,
    │                              │                             │ (future: wallet screening)
    │                              │◄── POST verdict accept ─────│
    │◄── 200 {paymentId} ──────────│ 4. COMMIT: keep the lock    │
    │                              │    + emit payment.initiated │
    │                              │                             │
    │══ funds move on the rail ════│                             │
    │                              │                             │
    │── POST notification #2 ─────►│ txHash present (SENT)       │
    │◄── 200 always ───────────────│ NO vote. Recorded, event    │
```

**The rules that make this safe:**

1. **Only a pre-funds notification can be refused**, identified by the absence of a transaction id
   (§3.4.6). It happens strictly *before* funds move, so a refusal costs nothing. On a refusal X9.150 **aborts**: the same conditional update that handles
   expiry returns the QR to `ACTIVE`, and the payer gets a 4xx explaining why.
2. **A post-commit notification is never refused.** It carries a transaction id, which means money
   has already moved; refusing it would be a lie about reality. It is recorded, it advances the QR, and it always answers 200 — even
   if the biller is offline, even if the amount disagrees (which becomes a reconciliation problem,
   not a protocol one).
3. **Prepare before asking, not after.** X9.150 takes the atomic `ACTIVE → PAYMENT_INITIATED` lock
   *before* handing the request to the biller. That way a second payer racing the same QR gets a
   synchronous 409 while the first is still pending, and the biller never has to reason about
   concurrency — "is this QR already being paid" is answered by the database, atomically, not by the
   biller's own check. `PAYMENT_INITIATED` **is** the prepared state; no new status is needed.
4. **The lock cannot leak.** Every way out of the pending state is already built: the biller refuses →
   abort to `ACTIVE`; the biller never answers → timeout (§3.4.4) → abort; the process dies mid-flight
   → the expiry sweep (§3.3 D) reclaims it and emits `payment.expired`.

#### 3.4.4 When the biller does not answer

A held payer request cannot wait forever — the payer PSP has its own timeout budget.

```yaml
x9.payments.approval:
  timeout: PT5S               # how long X9.150 waits for the biller's verdict
  on-timeout: REFUSE          # REFUSE | ACCEPT
  on-no-listener: REFUSE      # nobody is long-polling at all
```

**Recommendation: `REFUSE` for both (fail closed).** An unanswered approval means the biller could not
assert the payment is wanted; initiating anyway defeats the purpose of asking. The trade-off is stark
and must be documented: **with `APPROVE` and `REFUSE`, a biller whose consumer is down cannot be
paid.** `ACCEPT` (fail open) keeps payments flowing and degrades to `NOTIFY` semantics; a biller that
prefers availability over control should choose `NOTIFY` outright rather than `APPROVE` +
`on-timeout: ACCEPT`, which is the same thing with extra latency. `PT5S` must stay well inside the
payer PSP's timeout; it is a ceiling, not a target.

#### 3.4.5 Idempotence, at every step

| Step | Repeated call does |
|---|---|
| Notification #1 replayed (same `transactionId`) | Returns the **original verdict and `paymentId`**, emits no second event, does not re-ask the biller. The verdict is stored on `payment_initiation`. |
| Notification #2 replayed | 200, no duplicate event (deduped on `transactionId` + action). |
| Biller posts a verdict twice | First wins; the second returns the recorded outcome, never flips a decision. |
| Biller posts a verdict for an expired/aborted `paymentId` | `409 Gone/Conflict` with the current state — it must not resurrect an abandoned initiation. |
| Event delivery | At-least-once; consumers dedupe by `eventId`. |

The stored verdict is what makes replay cheap and what keeps a retrying payer from being asked twice.

#### 3.4.6 What makes a notification vetoable: the absence of a transaction id

**The standard has no explicit pre-commit / post-commit marker.** An event type distinguishing the two
phases was proposed during X9.150's definition and **rejected**, so conformant implementations must
*infer* the phase. The inference is unambiguous and physical:

> **A transaction id exists only if the transaction was committed.** For a blockchain that id *is* the
> txHash, which cannot exist before the transaction reaches the chain.

That gives one rule covering every rail, and it is exactly the boundary the veto needs:

> **Vetoable ⇔ no `$.payment.transactionId` ⇔ funds have not moved.**

| Rail | First notification | `transactionId` | Vetoable? |
|---|---|---|---|
| Blockchain pre-commit | `action = PAYMENT_INITIATED` | **absent** | **Yes** — nothing has moved |
| Blockchain post-commit | `action = SENT` | present (txHash) | No — money is on-chain |
| ACH | the pre-settlement announcement (`expectedDate` in the future) | optional | **Yes** — settlement has not occurred |
| FedNow, RTP | courtesy, after the fact | mandatory (ISO 20022 E2E id) | No — funds already moved |

`blockchain.action` is treated as a **corroborating** signal, not the authority. On disagreement:

- `action = SENT` **without** a transaction id → **reject**: a committed transaction without a hash is
  not a state that can exist. (The existing validator already requires the hash for `SENT`.)
- `action = PAYMENT_INITIATED` **with** a transaction id → treat as **post-commit** (the hash is the
  physical truth), record it, do **not** offer a veto, and log the disagreement for the operator.

This keeps the guarantee the protocol rests on: **X9.150 never invites a refusal for money that has
already moved**, regardless of how the payer labelled the message.

```
FEDNOW, RTP                     -> record only, status unchanged  [funds moved; courtesy]
ACH                             -> vetoable INITIATE              [#1, pre-settlement]
BITCOIN, ETHEREUM, SOLANA,
POLYGON, BASE, XRP, ARC:
   no transactionId             -> vetoable INITIATE              [#1, pre-commit]
   transactionId present        -> record, advance, never vetoed  [#2, post-commit]
   action = NOT_SENT            -> abort to ACTIVE, payment.failed
default                         -> BusinessRuleException          // fixes §2.1
```

Whether a two-phase-eligible notification actually asks the biller depends on the QR's policy
(§3.4.1): `APPROVE` asks, `NOTIFY` does not, `NONE` rejects the notification outright.

> **Known limitation — record-only rails are outside the double-payment guard.** FedNow and RTP
> notifications do not transition the QR, so a QR paid over RTP stays `ACTIVE` and **can still be
> initiated on another rail**. Pre-existing behavior, but never written down; it must be stated in
> `STATE-MACHINE.md`. Whether v1.0 should close it is **Q8**. Note this is exactly why a biller on
> those rails moves the QR itself from the pacs.008 rather than relying on a notification.

### 3.4.7 Status-update: the rail-agnostic way in (pacs.008)

Not every initiation arrives as a payment notification. On ISO 20022 rails the payee's own platform
learns of the payment from the **pacs.008** (the credit-transfer request, i.e. the feasibility test)
and should lock the QR then — long before any courtesy notification. That needs a plain
*"move this QR to `PAYMENT_INITIATED`, and fail if it isn't `ACTIVE`"* call.

**X9.150 must not understand ISO 20022, and does not need to.** The adopter parses the pacs.008 and
maps it to one call. The boundary stays clean: X9.150 owns the QR lifecycle; the adopter owns its rail.

#### This is what the standard asks for — and we do not implement it

Annex **A.9** names the ISO 20022 triggers directly:

> "On initial network acceptance of the credit transfer request (pacs.008), the Payee's PSP
> **SHOULD** update status to 'PAYMENT_INITIATED'"
>
> "On network confirmation of settlement (pacs.002), the Payee's PSP **SHOULD** update status to
> 'PAID.'"
>
> — ANSI X9.150-2026 §A.9

and gives the rationale — the same one as §3.3, arrived at independently from the adopter's brief:

> "On receipt of a payment notification, The Payee's PSP **SHOULD** update status to
> 'PAYMENT_INITIATED' to prevent duplicate payment." — ANSI X9.150-2026 §A.9

The remaining lifecycle rules (Annex A.9, **Table 3 field 8** `$.status`, §7), all already matched by
`QRCodeEntity`: only a `PAYMENT_INITIATED` payload may revert to `ACTIVE`, and

> "Once the payload status is 'PAYMENT_INITIATED', 'PAID', or 'CANCELLED', it **SHALL NOT** be
> revised." — ANSI X9.150-2026 §7

ACH is the exception: the standard defines **no network finality event** for it, so the transition to
paid is implementation-dependent and out of scope — which is why the notification path matters there
(§3.4). The standard is not in this repository; see
[`official-spec/README.md`](official-spec/README.md) to purchase it.

**Current state: the contract promises this and the code refuses it.**

| Layer | `PAYMENT_INITIATED` on status-update |
|---|---|
| `openapi.yaml` `StatusUpdate.status` enum | **Advertised** — the value is listed |
| `UpdateQRCodeStatusUseCase` | **Rejected** — `default -> throw "Status PAYMENT_INITIATED is not allowed."` |

So a caller following the published contract gets a `400`. This is a **contract/implementation
mismatch and a conformance gap**, not a new feature: `PAID`, `CANCELLED` and `ACTIVE` are wired;
`PAYMENT_INITIATED` was left out.

#### What to build

Route the missing enum case through the **same atomic conditional update as §3.3 (A)** — no second
mechanism, no separate code path:

```js
filter: { _id: qrId, status: "ACTIVE" }
update: $set status PAYMENT_INITIATED + payment_initiation{…}, $inc revision,
        $push outbox <payment.initiated>, $unset ttl
```

- **Returns the document → 200**; the caller gets the `paymentId`.
- **Returns null → 409**, carrying the current status, for exactly the cases asked for: already
  `PAYMENT_INITIATED`, `PAID`, or `CANCELLED`. That *is* the "is this QR still valid" check — answered
  atomically by the database rather than by a read-then-write the caller could race.
- **No biller vote.** The caller here *is* the payee's platform; asking it to approve its own call
  would be circular. The approval protocol (§3.4.3) governs notifications from the *payer's* side only.
- **Idempotent** on an optional caller-supplied `paymentId`: replaying the same one returns 200 with
  the same result instead of a 409, so a pacs.008 retry is safe.
- `pacs.002 → PAID` needs nothing new — the existing `pay()` path already covers it.

### 3.5 Drain (outbox -> event log)

Driven by `@Scheduled(fixedDelayString = "${x9.events.drain.interval}")`, single-threaded. Its only
sink is a **local Mongo collection** — no broker, no network call, nothing that can be down.

```
docs = find({ "outbox.0": { $exists: true } }).sort(_id).limit(batchSize)   // partial index
for doc in docs:
    for event in doc.outbox:              # array order == emission order
        eventLog.append(event)            # upsert _id = eventId -> idempotent by construction
        outbox.ack(doc._id, event.eventId)  # $pull {outbox: {event_id: …}}
        # last ack on a document also restores ttl (§3.8)
```

- **At-least-once, trivially safe.** A crash between the append and the `$pull` re-appends the same
  `eventId` on the next tick, and the upsert makes that a no-op. There is no partner system to be
  inconsistent with, which is the whole reason the drain got simpler.
- **The append assigns `seq`**, a ULID: monotonic, sortable, and the cursor consumers page by.
- **Per-QR ordering** holds: one QR's events live in one array and are drained in order.
- **No advisory lease needed.** The license already permits one actively-serving instance
  (`HIGH-AVAILABILITY.md` §3), and the upsert is idempotent even if that were violated.

**Partial index.** Spring Data annotations cannot express `partialFilterExpression`, so create it
programmatically in `MongoDbConfiguration` (`auto-index-creation: true` handles the annotated ones):

```java
mongoTemplate.indexOps("qrcodes").ensureIndex(
    new Index().on("outbox.0", ASC).named("outbox_pending_partial")
        .partial(PartialIndexFilter.of(Criteria.where("outbox.0").exists(true))));
```

### 3.6 Public event contract

> **Status: agreed with the adopter side.** The field set, the five type names, `int64` amounts and
> the consumer rules below were reviewed and accepted. Treat changes to this section as contract
> changes — additive within `1.x`, never a removal or a retype.


One JSON Schema per major version, published next to the OpenAPI contract and treated as public API —
**additive changes only within a major version**:
`x9-qrcode-infrastructure/src/main/resources/apis/events/payment-event-v1.schema.json`.

```jsonc
{
  "schemaVersion": "1.0",                 // MAJOR.MINOR of this contract
  "eventId":       "6f1c…",               // UUID, STABLE across re-publishes — the dedupe key
  "type":          "payment.initiated",   // | payment.cleared | payment.expired
                                          // | payment.failed  | payment.cancelled
  "occurredAt":    "2026-09-26T10:00:05Z",// UTC, Z-terminated
  "qrId":          "…",                   // partition/ordering key for any downstream bridge
  "qrRevision":    7,                      // monotonic per QR; see the gap note below
  "locationId":    "…",                   // the QR's location / presentment id
  "paymentId":     "…",                   // the initiation this event belongs to
  "transactionId": "XYZ.USBK.X9aTf72qLm.1", // nullable; ISO-20022 E2E id / ACH trace / txHash
  "network":       "ACH",
  "amount":        5000,                  // int64 minor units, never floating point
  "tipAmount":     0,
  "currency":      "USD",                 // ISO 4217 or digital-asset ticker, verbatim
  "invoiceNumber": "INV-2026-0042",       // nullable — the biller's own reference
  "orderNumber":   "ORD-77",              // nullable
  "expiresAt":     "2026-09-26T10:15:05Z",// payment.initiated only — the lock window
  "notificationId":"…",                   // nullable — handle to fetch details via the API
  "reason":        "initiation-ttl-elapsed" // expired/failed/cancelled only; nullable
}
```

**Amounts are `int64` minor units, as JSON numbers** — identical to `AmountVO` and to every amount
field in `openapi.yaml`. The event is a view of what the entity holds, so it uses the entity's
representation; diverging (a string form, or a `decimals` field) would make the same value look
different on the API and on the topic for no gain.

That `int64` range is a deliberate product boundary, not an oversight: X9.150 is currency-agnostic and
"repeats the informed value; the paying PSP interprets it" (`AGENTS.md`), so it neither converts
minor↔major nor carries an asset scale. A consumer that works in a wider representation converts on
receipt, from a value it already trusts.

> **Consumer caveat worth one line in the docs:** `int64` minor units exceed JavaScript's exact
> integer range (2^53 ≈ 9.0e15), so a JS/TypeScript consumer should parse the topic with a
> bigint-aware JSON reader rather than `JSON.parse` defaults. This is a parsing note for consumers,
> not a reason to change the wire format.

**Validated against the tracked contract** (`openapi.yaml`, the authoritative source per `AGENTS.md`;
the ANSI text itself is git-ignored and not sourced here): the canonical `Amount` schema is
`type: integer, format: int64` — "an integer in the smallest unit of the currency" — and every
monetary field reuses it, including `AmountDue`, the adjustment amount, and the discount / fixed /
per-day late-fee fields. `int64` is confirmed; the plan keeps it.
### 3.7 How events leave the system

**X9.150 does not push.** It records facts and serves them; anyone who wants them pulls, on their own
schedule, with their own cursor. No broker client, no serialization framework, no Schema Registry, and
no transport configuration inside a payment service.

```
QR document (outbox[])  --drain-->  payment_events  --HTTP-->  GET /pub/api/v1/events?after=…
                                     (append-only)              long-poll, cursor-paged
```

This is exactly the brief's `none` target ("events stay queryable, e.g. `GET /events?after=<cursor>`"),
promoted from fallback to the primary and only mechanism — which the brief explicitly permits:
*"Kafka is an integration option, not a requirement. X9.150 must run with Mongo alone."*

**Why this is the better shape for this product**

| | Pushing to Kafka from inside X9.150 | Pull API |
|---|---|---|
| Independence | X9.150 holds a broker client, broker config, and broker failure modes | X9.150 knows nothing about any consumer's stack |
| Running it | Needs a broker to be useful | Mongo alone, as the README already promises |
| Multiple consumers | One publisher, one topic, our retention policy | N consumers, each with its own cursor and pace |
| Adding a transport | A code change inside the payment service | Someone writes a consumer. No change here at all |
| Failure surface | Publish latency and outages inside the request/drain path | A local Mongo write |
| Licensing | A relay that must be single-active | Nothing extra |

**Consumers build their own bridge, and we ship one as an example.** A "pull from X9.150, push to
Kafka / Postgres / RabbitMQ / SNS" bridge is ~100 lines against the events API. Shipping it as a
**playground script with its own Compose profile** — never as a module, build dependency or CI job of
the service — keeps the dependency direction right and makes the community extension path literally
"copy the bridge and change the sink" (**Q14**).

`PaymentEventPublisher` **stays in the codebase as a port** with a single log-backed implementation, so
an in-process adapter remains a drop-in for anyone who wants one. We just do not ship one.

### 3.8 Two persistence hazards that must be handled

1. **`save()` would erase the outbox.** `QRCodeMongoRepository.save()` maps the whole entity and
   replaces the document. A replace racing with a concurrent `$push` drops events; a replace racing
   with a relay `$pull` resurrects them. **Change `save()` on the update path** (`isNew() == false`) to
   a `MongoTemplate` field-level `$set` of the mapped fields — explicitly excluding `outbox` and
   `payment_initiation` — guarded by the current `revision` to keep optimistic locking. Creation
   (`isNew()`) keeps `insert`.
2. **The TTL reaper deletes documents with pending events.** `ttl` is set to `validUntil` with
   `@Indexed(expireAfter = "30S")`, so a QR document vanishes 30 s after it expires — taking any
   undelivered outbox events with it. Every `$push` therefore also `$unset`s `ttl`, and the `$pull`
   that empties the array restores `ttl = valid_until`. A document with pending events is never
   reaped; once drained, the existing retention behavior resumes unchanged.

---
## 4. HTTP surface

| Endpoint | Change |
|---|---|
| `POST /pub/api/v1/payment-notification` | Returns a body: `{ "paymentId": "…", "status": "PAYMENT_INITIATED", "replay": false }`. **409** on a competing initiation (the schema already declares `ConflictErrorResponse` — only the mapping is missing). 200 + same `paymentId` on an idempotent replay. |
| `PUT /api/v1/payment-request/{id}/status-update` | **`PAYMENT_INITIATED` now actually works** (§3.4.7) — advertised in the contract today but rejected by the code. Backed by the same atomic update, **409** when the QR is not `ACTIVE`. Accepts an optional `paymentId` (idempotent replay); emits `payment.initiated` / `payment.cleared` / `payment.failed`. |
| `GET /pub/api/v1/events` | **New.** The read-only event stream (§4.1). |
| `GET /api/v1/payment-approvals` | **New, biller-facing.** Long-poll for notifications awaiting a verdict (§4.2). |
| `POST /api/v1/payment-approvals/{paymentId}` | **New, biller-facing.** Return the verdict: `{ "decision": "ACCEPT" \| "REFUSE", "reason": "…" }` (§4.2). |

> The two approval endpoints are **management-surface** (`/api/v1/...`), not `/pub`. They are the
> biller's control channel, not the payer's — putting them under `/pub` would invite the payer's side
> of the network to vote on its own payment.

### 4.1 The events endpoint

```
GET /pub/api/v1/events?after=<cursor>&limit=<1..500>&wait=<0..30s>
->  { "events": [ … ], "nextCursor": "01JB…", "hasMore": false }
```

- **Cursor** is the `seq` ULID assigned at drain time: opaque to the caller, monotonic, gap-free in
  ordering terms. `after` omitted starts from the beginning of retained history.
- **Long-poll.** With `wait > 0` the request is held open until at least one event is available or the
  timeout elapses, then returns (possibly empty). Idle consumers cost one parked request rather than a
  poll loop. **Virtual threads make this nearly free** — `spring.threads.virtual.enabled` is already
  on, so a parked request holds no platform thread. Cap `wait` server-side (`max-wait`, default 30 s)
  to stay under proxy and ingress idle timeouts.
- **`hasMore`** lets a catching-up consumer drain without waiting.
- **At-least-once by design**: a consumer that crashes before persisting its cursor re-reads events.
  Dedupe by `eventId`, exactly as a broker consumer would.
- **Retention is ours now, and it is the one real trade-off** (§12 Q5). A consumer offline longer than
  `x9.events.log.retention` misses events permanently — a broker would have absorbed that. Mitigate
  with a generous default (**P30D**, raised from P7D now that this is the only path out), a
  `x9.events.oldest-retained` gauge, and a documented "if your cursor predates retention, resynchronize
  from the QR state via the management API" recovery.

> **Exposure.** This publishes payment facts on the open, unauthenticated `/pub` surface, which makes
> the project's "protect it at your edge" posture load-bearing rather than advisory. It must say so in
> `ENDPOINTS.md`, `SECURITY.md` and the OpenAPI description. Because this is now the *only* way out,
> it ships **enabled by default** (`x9.events.api.enabled`, default `true`) — a reversal of the
> earlier position, and the reason the exposure note matters more, not less.

`PublicEndpointsController.processPaymentNotification` currently catches `Exception` and rethrows
`BusinessRuleException` → everything becomes 400. Narrow it so `PaymentConflictException` and
`QRCodeEntityNotFoundException` pass through to the advice.

`GlobalControllerAdvice` / `ErrorTypeEnum`: add
`CONFLICT("Payment Conflict", …/api/payment-conflict, HttpStatus.CONFLICT)`; handle
`PaymentConflictException` and `OptimisticLockingFailureException` (today an uncaught 500, §2.1).

### 4.2 The approval channel (biller-facing)

```
GET  /api/v1/payment-approvals?wait=<0..30s>&limit=<1..50>
  -> { "pending": [ { "paymentId": "…", "qrId": "…", "locationId": "…",
                      "amount": 5000, "currency": "USD", "network": "ACH",
                      "transactionId": "…", "expiresAt": "…",
                      "deadline": "2026-09-26T10:00:04.6Z" } ] }

POST /api/v1/payment-approvals/{paymentId}
  { "decision": "ACCEPT" }                     -> 200, payment proceeds
  { "decision": "REFUSE", "reason": "amount mismatch" }  -> 200, QR returns to ACTIVE
```

- **`deadline`** tells the biller how long it actually has before X9.150 applies `on-timeout`. A
  verdict arriving after the deadline gets `409` with the outcome that was already applied — it never
  flips a decision (§3.4.5).
- **Long-poll, not a queue.** A pending approval is a row in the QR document, not a message. If the
  biller crashes mid-decision, the next long-poll sees the same pending approval again, and the
  deadline still governs. There is nothing to acknowledge and nothing to lose.
- **Only `APPROVE`-policy QRs appear here.** A `NOTIFY` QR never produces a pending approval.
- **Same virtual-thread economics as §4.1** — a parked biller costs no platform thread.
- **Exposure:** this is the *decision* channel. Anyone who can reach it can approve or refuse payments
  on any QR. On an open, unauthenticated API that is the sharpest edge in the whole design, and
  `SECURITY.md` must say so in those words: **if you enable `APPROVE`, you must restrict
  `/api/v1/payment-approvals` at your edge.** It is off unless a QR opts in, which limits blast radius
  but does not remove it. See **Q15**.

## 5. Configuration

```yaml
x9:
  payments:
    initiation-ttl: PT15M         # how long a PAYMENT_INITIATED lock survives
    sweep-interval: PT1M
    sweep-batch-size: 100
    default-notification-mode: NOTIFY     # NOTIFY | APPROVE — applies only when paymentNotification
                                          # is present; absent still means "no notifications"
    approval:
      timeout: PT5S               # how long to hold the payer while the biller decides
      on-timeout: REFUSE          # REFUSE (fail closed) | ACCEPT (fail open)
      on-no-listener: REFUSE      # nobody is long-polling at all
      max-wait: PT30S             # biller long-poll ceiling
  certificate:
    trust:
      allowed-issuers: []         # e.g. ["DigiCert"]; empty = any issuer the truststore chains to
      allow-self-signed: false    # true for dev/playground only — logs a startup WARN
  events:
    schema-version: "1.0"
    drain:
      enabled: true
      interval: PT1S
      batch-size: 100
    log:
      retention: P30D             # TTL index on payment_events — the only buffer consumers have
    api:
      enabled: true
      max-limit: 500
      max-wait: PT30S             # long-poll ceiling; keep under ingress idle timeouts
  public-endpoints:
    host-source: static           # static | cloudflare-quick-tunnel    (§9)
    cloudflare:
      metrics-url: http://127.0.0.1:20241
      refresh: PT30S
    rerender-qr-content: false    # dev only (§9)
```

No broker settings, no `spring.kafka` block, no Schema Registry — **the service's entire messaging
configuration is a retention period and a poll ceiling.** Every value binds from an environment
variable via Spring's relaxed binding (`X9_EVENTS_LOG_RETENTION`, `X9_EVENTS_API_MAXWAIT`, …), matching
the pattern `ENDPOINTS.md` documents.

## 6. Proof (the three required tests, plus)

Follow the existing Testcontainers pattern (`@DatabaseTest`, `MongoDatabaseInitializer`). **No broker
container is needed** — CI stays exactly as it is today (`./mvnw clean test` with Docker on
`ubuntu-latest`), which is itself an argument for this shape.

| Test | Asserts |
|---|---|
| `PaymentInitiationConcurrencyDatabaseTest` | N=32 virtual threads POST the same notification concurrently → **exactly 1** HTTP 200, **31** HTTP 409 (synchronous, no 500s), one `payment_initiation`, outbox holds **exactly one** `payment.initiated`. |
| `PaymentNotificationReplayDatabaseTest` | Same `transactionId` twice → both 200, same `paymentId`, still exactly one event. |
| `OutboxDrainCrashDatabaseTest` | Kill the drain between `eventLog.append` and the `$pull` → next tick re-appends the same `eventId`; the upsert makes it a no-op, the event is never lost and never duplicated in the log. |
| `PaymentInitiationExpiryDatabaseTest` | TTL ~100 ms; after the sweep the QR is `ACTIVE`, `payment_initiation` gone, `payment.expired` carries the original `paymentId`. |
| `OutboxSurvivesTtlDatabaseTest` | A QR past `validUntil` with a pending event still exists after the reaper window (§3.8 hazard 2). |
| `SaveDoesNotClobberOutboxDatabaseTest` | A PATCH concurrent with a pending outbox event leaves the event intact (§3.8 hazard 1). |
| `EventsApiCursorDatabaseTest` | Paging with `after` returns every event exactly once, in per-QR order, with no gaps across page boundaries; an unknown/expired cursor is a clear 4xx, not a silent empty page. |
| `EventsApiLongPollTest` | `wait=2s` with no events returns empty after ~2 s; an event arriving mid-wait returns promptly; `wait` above `max-wait` is clamped. |
| `ApprovalAcceptFlowTest` | `APPROVE` QR: notification #1 is held, the biller's `ACCEPT` releases it, payer gets 200, QR is `PAYMENT_INITIATED`, one `payment.initiated`. |
| `ApprovalRefuseFlowTest` | `REFUSE` returns the QR to `ACTIVE`, payer gets a 4xx carrying the reason, no `payment.initiated`, and the QR is immediately payable again. |
| `ApprovalTimeoutTest` | No verdict within `timeout` → `on-timeout` applied; with `REFUSE` the QR is back to `ACTIVE`; a late verdict gets 409 and does **not** flip the outcome. |
| `ApprovalNoListenerTest` | `APPROVE` QR with nobody polling → `on-no-listener` applied within the timeout, not a hung request. |
| `SecondNotificationNeverRefusedTest` | Notification #2 (txHash present) returns 200 even with no listener, a disagreeing amount, and a biller that would have refused. |
| `PhaseInferenceTest` | Phase is decided by `transactionId` presence, not by `action`: `SENT` without a hash is rejected; `PAYMENT_INITIATED` **with** a hash is treated as post-commit, is not vetoable, and logs the disagreement. |
| `NotificationModeTest` | Absent `paymentNotification` still rejects a notification (unchanged); `mode` absent behaves as `NOTIFY` and creates no pending approval; `mode: APPROVE` does. |
| `CreateApiBackwardCompatibilityTest` | A create request with no `mode` produces byte-identical behavior to the pre-change build — the additive-field claim is asserted, not assumed. |
| `TrustAllowlistTest` | A JWS from a disallowed issuer is refused distinctly from a malformed/expired one; the demo keystore works with `allow-self-signed: true` and is refused with `false`. |
| `ApprovalIdempotenceTest` | Replayed notification #1 returns the stored verdict without re-asking; a twice-posted verdict returns the recorded outcome. |
| `StatusUpdateInitiatedTest` | `PUT /status-update` with `PAYMENT_INITIATED` moves an `ACTIVE` QR and returns `paymentId`; returns **409 with the current status** when the QR is already `PAYMENT_INITIATED`, `PAID` or `CANCELLED`; replay with the same `paymentId` is idempotent. Concurrent callers → exactly one 200. |
| `PaymentEventSchemaTest` | Every `PaymentEvent` factory output validates against `payment-event-v1.schema.json`. |
| Unit tests (no Spring) | Event factories, network→intent dispatch **including `BASE`/`XRP`/`ARC` and the `default` throw**, `PaymentInitiationVO.isExpired`, drain use case with fake ports. |

## 7. Work plan

Each phase is one reviewable commit (`git commit -s`, squash-merged per `CONTRIBUTING.md`). The suite
must be green at every phase.

| # | Phase | Deliverable | Depends on |
|---|---|---|---|
| 0 | **Baseline fixes** | Exhaustive network switch + `default` throw (§2.1); `OptimisticLockingFailureException` → 409; `CONFLICT` error type. Tests for `BASE`/`XRP`/`ARC`. | — |
| 3a | **Status-update `PAYMENT_INITIATED`** | Close the contract/implementation gap (§3.4.7): wire the advertised enum case to the atomic update, 409 when not `ACTIVE`, optional idempotency `paymentId`. **The pacs.008 entry point** — usable on its own, before any notification work lands. | 3 |
| 1 | **Domain** | `PaymentEvent`, `PaymentEventTypeEnum`, `PaymentInitiationVO`; entity + validator changes; unit tests. | 0 |
| 2 | **Ports** | `PaymentEventPublisher`, `PaymentOutboxRepository`, `PaymentEventLog`, `PublicHostProvider`, `PaymentConflictException`; repository port extensions. | 1 |
| 3 | **Persistence** | Conditional `findAndModify` ops; nested models; partial index; **the two §3.8 hazards**; `@DatabaseTest`s per transition. | 2 |
| 4 | **Atomic initiation** | Notification use case rewrite (§3.4.6 dispatch), idempotent replay, 409 mapping, response body. **Concurrency + replay tests.** | 3 |
| 4a | **Notification mode** | Optional `paymentNotification.mode` (`NOTIFY` default, `APPROVE`) on create + patch, OpenAPI addition, default-mode config. **Additive only — no `/v2`, no behavior change for existing callers** (§3.4.1.1). | 4 |
| 4b | **CA allowlist** | `allowed-issuers` + `allow-self-signed`, distinct rejection for a disallowed issuer, startup WARN. Tests with the demo keystore and with a disallowed issuer. | 4 |
| 4c | **Two-phase approval** | Approval channel (§4.2), held payer request, verdict storage, timeout/no-listener policy, abort-to-ACTIVE. **The protocol tests below.** | 4a |
| 5 | **Expiry** | Sweep use case + scheduler + inline reclaim. **Expiry test.** | 4 |
| 6 | **Drain + event log** | Drain use case, scheduler, `payment_events` collection with ULID `seq` + TTL. **Crash test.** | 4 |
| 7 | **Events API** | Cursor paging, long-poll, limits, OpenAPI path, exposure note. **Cursor + long-poll tests.** | 6 |
| 8 | **Event contract** | JSON Schema v1.0 + conformance test + `EVENTS.md` (consumer rules, cursor protocol, recovery-from-retention-loss). | 6 |
| 9 | **Bridge example** *(optional)* | A ~100-line reference consumer in `playground/` (pull the events API → Redpanda), with its own Compose profile. **Not a module of the service, not a build dependency, not in CI** — see **Q14**. | 7, 8 |
| 10 | **Dynamic public host** | §9 — provider port, static + Cloudflare adapters, host-length validation moved to QR creation. | 2 |
| 11 | **k3s / Helm / Compose** | §10 — chart values, probes, cloudflared wiring, `DOCKERHUB.md`. | 7, 10 |
| 12 | **Docs & playground** | §11 — `STATE-MACHINE.md`, `ENDPOINTS.md`, `openapi.yaml`, `README.md`, `TODO.md`, `HIGH-AVAILABILITY.md`, `simulate_notification.py`, `simulate_consumer.py`, Makefile targets. | all |

Phases 0–4 are the core of the brief; 5–8 complete it; 9–12 are the surrounding product work. Phase 9
is optional and separable. Phases 10 and 11 are independent of the outbox and can run in parallel.

## 8. Observability

Micrometer is already wired (`micrometer-registry-prometheus`, actuator exposed). Add:

- `x9.payments.initiations{result=accepted|conflict|replay}` (counter)
- `x9.payments.initiations.expired` (counter)
- `x9.events.outbox.pending` (gauge — the number of documents matching the partial index; **the alert
  that matters**: a growing value means the relay is stuck or the broker is down)
- `x9.events.published{type,result}` (counter), `x9.events.publish.latency` (timer)
- `x9.events.relay.lag` (timer — `now − occurredAt` of the oldest pending event)

A health indicator `paymentEventRelay` reports DOWN when the oldest pending event exceeds a
configurable age, so k3s surfaces a stalled relay through the existing readiness/liveness plumbing.

---

## 9. Public callback URL with an ephemeral tunnel

**Requirement.** The notification endpoint is a public Internet URL. For testing (and for the
community, with no Cloudflare account) we want an **anonymous Cloudflare quick tunnel**: start
`cloudflared`, receive a `*.trycloudflare.com` hostname, and use it in the payload we return to the
payer. The hostname changes between runs, so it must not be frozen into stored data.

**What already holds.** The notification URL is **not** stored at creation. For `kind: DEFAULT`,
`RetrieveQRCodePayloadUseCase.getPaymentNotificationUri` calls
`QRCodeLocationService.retrievePaymentNotificationEndpoint()` per request, which builds the URL from
`x9.public-endpoints.host`. The JWKS (`jku`) and certificate (`x5u`) URLs are built the same way. Only
`kind: EXTERNAL` stores an endpoint, and that is the creditor's own URL, which is correct.
(§14.2 of the payload contract keeps a per-QR override; nothing here changes it.)

**What has to change — three things.**

1. **The host must be resolvable at request time, not only at startup.** Introduce
   `PublicHostProvider` (application port) and replace the direct property reads in
   `DefaultQRCodeLocationService` with it.
   - `StaticPublicHostProvider` — returns `x9.public-endpoints.host`. Default; today's behavior.
   - `CloudflareQuickTunnelPublicHostProvider` — reads the hostname from the local `cloudflared`
     metrics server, caches it for `refresh`, and falls back to the static host when `cloudflared`
     is not reachable. **Verified against `cloudflared` 2026.9.1 while writing this plan:**

     ```bash
     cloudflared tunnel --url http://localhost:8080 --metrics 127.0.0.1:20241
     curl -s http://127.0.0.1:20241/quicktunnel
     # {"hostname":"peripheral-yarn-dependent-comments.trycloudflare.com"}
     ```

     No account, no login, no DNS record — exactly the zero-setup path the community can use. Pin
     the `cloudflared` version in Compose/Helm and keep the static-host fallback, since `/quicktunnel`
     is not a documented stability contract.
2. **Move the ≤37-character host check from startup to QR creation.** `X9Properties.afterPropertiesSet`
   rejects a long host at boot because the loc URL has to fit EMV tag `26`. Quick-tunnel hostnames
   exceed that **routinely, not occasionally** — the one minted during this plan's verification,
   `peripheral-yarn-dependent-comments.trycloudflare.com`, is **52 characters**. Today that means the
   app will not start at all. But the limit applies **only to the URL embedded in the QR** — the notification, JWKS and
   certificate URLs travel in JSON and JWS headers with no length budget. Validate at
   `CreateQRCodeUseCase` instead: a long host still boots and still serves notifications and signature
   verification; only QR *creation* fails, with a clear message. Keep a loud startup WARN.
3. **Optional EMV re-render for tunnels.** The EMV content (and its embedded loc URL) is generated at
   creation and stored (`qrcode_emv`). If the tunnel hostname rotates, previously created QRs point at
   a dead host. Add `x9.public-endpoints.rerender-qr-content` (**default `false`**): when `true`, the
   EMV is regenerated from the current host on read/payload instead of echoing the stored value.
   Strictly a development convenience — flag it as such, because in production the printed QR is
   already in the payer's hands and the host is stable by definition.

**Deliverables:** the provider port + two adapters, the validation move, the re-render flag, a
`cloudflared` service in `docker-compose.tunnel.yml`, a `cloudflared` sidecar block in the Helm chart
(the chart already supports `sidecars`), a `make tunnel` target, and a rewritten
`ENDPOINTS.md` tunnel section covering the notification callback, not just the scan path.

---

## 10. k3s deployment (and keeping Compose)

The chart (`others/helm/x9-qrcode/chart/`) already has what matters: `replicaCount: 1`,
`autoscaling.enabled: false`, `sidecars`, `initContainers`, `customerEnvs`, `ingress`, probes, secrets
and a `configmap` carrying `application.yml`. Deploying on k3s from `materainc/x9-qrcode` needs
additions, not surgery.

| Item | Change |
|---|---|
| Image default | `values.yaml` `image.repository` → `materainc/x9-qrcode` with an immutable `git-<sha>` tag example, matching `DOCKERHUB.md`. Drop the mandatory `imagePullSecrets` from `values-minimal.yaml` (Docker Hub public pulls need none) — keep the key documented for private registries. |
| New config | `application.x9.events.*` and `application.x9.payments.*` plumbed through the configmap. **No broker credentials, so no new secrets.** |
| Broker | **None — not bundled, not configured, not required.** A bare `helm install` works with Mongo alone, which is now the whole story rather than a default. |
| Drain health | Wire a `paymentEventDrain` indicator (oldest pending outbox event age) into the **readiness** probe group. Never liveness. |
| Single active | Set `strategy.type: Recreate` in `values.yaml` (with a comment) so a rolling update never runs two serving pods — which `HIGH-AVAILABILITY.md` §3 says exceeds Annex A §1(a), and which would also briefly double the relay. Today's default rolling update overlaps pods. |
| Graceful drain | `terminationGracePeriodSeconds` ≥ `spring.lifecycle.timeout-per-shutdown-phase` (20 s) so the relay finishes its in-flight publish instead of being killed mid-ack. |
| Tunnel | Documented `sidecars` block for `cloudflared` (§9), and a k3s-specific note: with a real Ingress + DNS the tunnel is unnecessary; it is for labs and demos. |
| Mongo | Chart README gains a k3s note: the app needs a replica set, so point `spring.data.mongodb.uri` at a real replica set (Atlas, Bitnami chart, or the MongoDB Community Operator). A single-node `mongod` will not start the app. |
| Compose parity | `docker-compose.yml` is **unchanged** — no broker profile to add. The optional Redpanda demo lives with the bridge example (**Q14**), not in the service's compose file. |
| Verification | A `helm template` smoke check in CI (renders the chart with `values-minimal.yaml` and the events values) so chart drift is caught by the same gate as the code. |

---

## 11. Documentation to update

| Doc | Change |
|---|---|
| `STATE-MACHINE.md` | Rewrite: `PAYMENT_INITIATED` (fix the `INITIATED` drift), the atomic-transition table with filters, expiry, the event per transition, the two-phase approval protocol, and the `SENT`/`NOT_SENT` question the doc leaves "under review" — now answered: `NOT_SENT` releases, `SENT` records. Keep and strengthen the existing txHash-inference section, adding **why** it is an inference: the standard has no phase marker by committee decision. |
| `ENDPOINTS.md` | 409 semantics + response body, `GET /events` and its exposure caveat, tunnel section rewritten for the callback path (§9), host-length rule moved from "rejected at startup" to "rejected at QR creation". |
| `openapi.yaml` | Notification response body, `GET /pub/api/v1/events`, the two approval endpoints, optional `paymentNotification.mode`, `paymentId` on status-update, and a release-note entry stating the additions are backward-compatible under the contract's own policy. |
| `apis/events/payment-event-v1.schema.json` + `EVENTS.md` | New: the public contract, the cursor/long-poll protocol, consumer rules (dedupe by `eventId`, cursor persistence), versioning policy, recovery when a cursor predates retention, and how to write a bridge. |
| `README.md` | "Payment events" section: outbox, at-least-once, pull API, no broker required, link to `EVENTS.md`. |
| `TODO.md` | **Replace** the "payment expected event" entry — its Spring Cloud Stream direction is superseded (§1.1). Keep the certificate-caching entry. |
| `HIGH-AVAILABILITY.md` | Relay is single-active by design; `Recreate` strategy; broker HA is out of the Licensed Work, like MongoDB. |
| `AGENTS.md`, `CLAUDE.md` | New module surfaces (events, outbox, drain, events API) and the pull-based consumption model. |
| `CONTRIBUTING.md` | How to write a bridge against the events API — the community extension path, which now needs no change to this codebase. Point contributors at `docs/adr/` for the reasoning, and at its "Writing a new one" section for proposing a change of direction. |
| `docs/adr/` | Already written (ADR-0001…0008). Each open question in §12 becomes an ADR when decided. |
| `others/helm/x9-qrcode/README.md`, `DOCKERHUB.md` | k3s install, events values, broker options (§10). |
| `playground/` | `simulate_notification.py` — sign and POST a notification, show 200 vs 409; a `--concurrent N` flag that demonstrates exactly-one-wins from the command line. |

---

## 12. Open questions

Q3, Q9, Q10 and Q11 are settled. **Q7 is the one that may change the product**, and Q1/Q8 change this
branch's shape.

- **Q1 — Drop the replica-set requirement?** The brief says the embedded outbox works on standalone
  Mongo. Nothing in this plan needs a transaction, but `MongoTransactionManager` is still wired and
  four docs plus both Compose files state the requirement. Removing it is a clean follow-up; keeping
  it is the status quo. **Recommendation: keep for this branch, file a follow-up.**
- **Q2 — `initiation-ttl` default.** PT15M suits ACH/blockchain pre-commit. Should it be per-network
  (blockchain pre-commit may want longer than ACH)? **Recommendation: single global default now,
  per-network override later if the adopter asks.**
- **Q3 — ~~`payment.cleared` on FedNow/RTP~~ — answered.** Record-only rails emit nothing in v1.0
  (the consumer's source of truth there is the ISO 20022 message on its own rail), and
  `payment.cleared` **is** emitted on the settle path as the terminal "QR is paid" fact, even when the
  biller drove it via `status-update`. Both are in the design above.
- **Q4 — ~~`GET /events` default~~ — answered by the architecture.** It is the only way events leave
  the system, so it ships enabled. What remains is the **exposure** question, not the toggle: payment
  facts on an open, unauthenticated surface (§4.1). **Recommendation: enabled by default, with the
  edge-protection requirement stated plainly in `ENDPOINTS.md`, `SECURITY.md` and the OpenAPI.**
- **Q5 — Event log retention.** P7D default; the adopter may want longer, which makes `payment_events`
  the de-facto audit log rather than a replay buffer.
- **Q6 — Quick tunnels vs. the EMV length budget.** `/quicktunnel` is verified working, but a
  52-character quick-tunnel hostname can never carry a QR-embedded loc URL (85-char EMV budget, 37 for
  the host). So an anonymous quick tunnel fully supports the **notification callback, JWKS and
  certificate** paths, and supports **scanning only with a short named tunnel on your own domain**.
  Is the community story "notifications work anonymously, scanning needs a domain", or do we want a
  short-host indirection so quick tunnels also work end to end? **Recommendation: document the split;
  no indirection.**
- **Q7 — ~~The money model's `int64` ceiling~~ — CLOSED: `int64` is normative.** Checked directly
  against the standard (its data-type table and the §13.5 / §14.3 / §2.1 field tables). The finding,
  quoting the normative fragments that decide it (short attributed excerpts; see the quoting rule in
  `AGENTS.md`):

  - The `Amount (Minor Units)` data type is normative, not advisory:
    > "**Shall** be a 64 bit integer (see specific field-level requirements on whether can be
    > negative)" — ANSI X9.150-2026, Data Types
  - And for the payment amount specifically:
    > "**Shall** be a 64 bit integer with minimum value = 0." — ANSI X9.150-2026 §2.1
  - Tip amount (§2.2) is likewise **non-negative**.
    The bill **adjustment** amount (§13.5.3.2) is the signed one — which is exactly what
    `openapi.yaml` already documents.
  - Amount fields carry a **max length of 18 digits**, which is *tighter* than `int64`'s 19.

  **So `int64` is not our implementation choice — it is the contract.** Widening X9.150 to a larger
  amount type would be a **non-conformance**, not an enhancement, and no GitHub issue should be opened
  to do it. The 18-decimal problem is real but belongs to the standard, not to this codebase: a
  conformant X9.150 payload cannot express 10 ETH or 10 DAI at 18 decimals. If that matters for the
  product, the venue is **feedback to the ASC X9 committee**, and meanwhile the EVM rails
  (Ethereum, Polygon, Base, Arc) should carry a documented amount limitation. Practically, the widely
  used dollar stablecoins (USDC, USDT) are 6-decimal and comfortably within range.
- **Q7b — Conformance gap: we do not enforce the 18-digit maximum.** `AmountVO` validates only
  non-null and non-negative, and the `Amount` schema in `openapi.yaml` declares `format: int64` with
  no `maximum`. So the service currently accepts 19-digit amounts (up to 9.22e18) that exceed the
  standard's 18-digit field length. Small, self-contained fix — **worth its own GitHub issue**, and
  genuinely ours to make, unlike Q7.
- **Q8 — Close the record-only double-payment gap?** A QR paid via FedNow/RTP stays `ACTIVE` and can
  be initiated again on another rail (§3.4). Options: leave it (the rails reconcile independently), or
  have a record-only notification also take the atomic lock. **Recommendation: document it in v1.0,
  decide with the adopter** — it changes rail semantics, not just plumbing.
- **Q9 — ~~Event namespace~~ — answered.** All five types stay under `payment.*`. A `qr.*` prefix for
  cancelled/expired was proposed and withdrawn: `payment.expired` refers to the **initiation lock**
  expiring, not the QR, so it belongs in `payment.*` regardless, leaving too little for a split
  namespace to buy in a v1.0.
- **Q10 — ~~Payer-side participant identifier~~ — answered.** Omitted from v1.0. No structured
  identifier is needed, and the free-form `payer.info` string stays off an open topic. Additive later
  if a need appears.
- **Q11 — ~~Avro amount type~~ — MOOT under the pull model.** X9.150 no longer serializes to Avro, so
  no schema is registered and no lock-in exists here. The analysis is preserved because it belongs in
  `EVENTS.md` as **guidance for bridge authors**: amounts are normatively `int64` (Q7), so an Avro
  bridge should use `long`; widening later is possible only by *adding* a defaulted field, never by
  retyping (`long` does not promote to `bytes`/`fixed`). That is now the bridge author's decision, not
  ours — which is itself a point in favour of this architecture.
- **Q12 — Signed, repeated adjustment amounts in the event contract.** `AmountVO` rejects negatives,
  and `adjustment` is an **array** whose entries may mix signs (a discount and a late fee together).
  So the day adjustments join an event they need a `SignedAmountVO` **and** an array-typed field, not
  a single scalar. Not needed for v1.0 (payment amounts are non-negative).
- **Q15 — Authentication for the approval channel.** The project's posture is "open API, protect at
  the edge", which was defensible when every endpoint was read-or-report. `POST /payment-approvals`
  is different in kind: it **authorizes money movement**. Anyone who can reach it can approve or
  refuse any pending payment. Options: (a) keep the posture and document the requirement loudly;
  (b) make `APPROVE` policy require a shared secret / mTLS on those two endpoints only, as a narrow
  exception to the no-auth rule; (c) bind an approval to a token minted at QR creation, so only the
  creator can vote on its own QR. **Recommendation: (c) — it needs no auth framework, keeps the
  no-auth posture intact, and is a natural fit since the biller already created the QR.** Decide
  before phase 4c.
- **Q16 — `on-timeout` default.** `REFUSE` (fail closed) is recommended and means **a biller whose
  consumer is down cannot be paid**. `ACCEPT` keeps payments flowing but makes `APPROVE` decorative.
  A biller who prefers availability should choose `NOTIFY`, not `APPROVE` + `ACCEPT`. Confirm the
  default with the adopter.
- **Q18 — Should `pacs.002 → PAID` get the same treatment?** §3.4.7 wires `PAYMENT_INITIATED`; the
  settlement side already works through `pay()`, but it is *not* conditional on `paymentId` unless one
  is supplied, so a late pacs.002 could settle a QR that a different payment had since locked.
  **Recommendation: accept an optional `paymentId` on the `PAID` transition too**, matching §3.3 (B),
  so an ISO 20022 caller can close exactly the initiation it opened.
- **Q17 — Does `APPROVE` belong on rails that carry the QR id?** FedNow/RTP/Solana-with-MEMO
  reconcile from the payment message, and a biller can move the QR from the pacs.008 itself. Should
  `APPROVE` be rejected at QR creation for those rails, or allowed as a courtesy gate?
  **Recommendation: allow but warn** — the rails where it matters are ACH and pre-commit EVM.
- **Q13 — Direct MongoDB access for third parties? Recommend NO.** Raised as an option alongside the
  pull API. It looks cheap and is not:
  - It makes our **internal document schema a public contract** — `outbox`, `payment_initiation`,
    field names — the exact coupling this architecture exists to avoid. We could no longer refactor
    persistence without breaking consumers.
  - Draining requires `$pull`, so a third party would need **write access to the QR collection**.
  - A database grant exposes **everything in the database** — creditor details, tokenized account
    references — not just events, unless carefully scoped to a view with a dedicated read-only user.
  - It needs DB credentials and network reachability to the datastore, which is a far worse security
    posture than one HTTP endpoint behind the existing edge.

  The pull API gives the same capability with none of that, and `payment_events` is already an
  append-only projection. **If someone still wants it, the supported form is a read-only user scoped
  to a `payment_events` view — documented as unsupported-for-refactoring, never as a contract.**
- **Q14 — Where does the reference bridge live?** A ~100-line "pull from the events API, push to
  Kafka/Redpanda" example. Options: (a) `playground/` in this repo — discoverable, runs with the demo,
  but puts a broker back into the repo's test/demo surface; (b) a separate companion repository —
  cleanest dependency direction, one more thing to maintain. **Recommendation: (a) `playground/`, as a
  Python script with an optional Compose profile**, matching how `simulate_payee.py` /
  `simulate_payer.py` already work. It keeps the messaging story demonstrable without any broker
  dependency in the service, its build, or its CI.
---

## 13. Out of scope

- Any adopter-specific integration, schema, or shared database. X9.150 publishes facts; consumers map.
- **Any broker client inside X9.150** — Kafka, RabbitMQ, webhook, SNS, Pub/Sub. Consumers pull and
  bridge to their own stack (§3.7); a reference bridge is Q14.
- Consumer-side tooling, replay CLI, or a dead-letter topic.
- Multi-instance / active-active relay (outside the license — `HIGH-AVAILABILITY.md` §3).
- Any change to the X9.150 payload contract itself; the events are **our** fan-out, not a standard
  requirement.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
