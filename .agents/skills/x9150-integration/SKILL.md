---
name: 'X9.150 - Integration Skill'
description: 'For developers building software that USES the X9.150 QR Code service: call sequences, what it will and will not do, and the mistakes that only show up in production'
---

# X9.150 — Integration Skill

This skill is for building **software that consumes** the X9.150 QR Code service — a billing
platform, a wallet, a PSP back end. It is not for changing X9.150 itself; that is
[`senior-developer`](../senior-developer/SKILL.md).

Read this before writing integration code. Most of it is about **what X9.150 deliberately does not
do**, because every serious integration bug here comes from assuming it does.

## What you are integrating with

A service that speaks ANSI X9.150-2026. It creates QR Codes, serves the signed payload a payer
fetches, and carries payment notifications between deployments. It is **tenant-agnostic**,
**unauthenticated by design**, and **never touches money**.

It plays two roles, and one deployment may play either or both:

| Role | You are… | Endpoints |
|---|---|---|
| **Payee** | issuing QR Codes and being told about payments | `/api/v1/payment-request*`, `/pub/api/v1/loc/{id}`, `/pub/api/v1/payment-notification`, `/pub/api/v1/events` |
| **Payer** | scanning someone's QR Code and paying it | `/api/v1/qrcode-emv-decoder`, `/api/v1/payment-notification/pre-payment`, `/api/v1/payment-notification/post-payment` |

## The four things X9.150 will not do for you

Internalise these before designing anything.

1. **It never observes money.** No rail tells it that funds arrived. A payer saying "I sent it" is a
   *claim*. Your system — the one that actually sees the bank or chain — is the only thing that can
   declare a payment settled, and it does so with `PUT /api/v1/payment-request/{id}/status-update`.
2. **It has no tenancy.** `/pub/api/v1/events` returns **every** QR Code's events for the
   deployment. There is no filter, because X9.150 has no axis to filter on. Fanning out to the right
   biller is your job, using the mapping you already own from having created the QR Code.
3. **It has no authentication.** The API is open on purpose; JWS signs *payloads*, it does not
   authenticate *callers*. Put it behind a gateway, mTLS or a network policy. Treat
   "the management endpoints are reachable" as your configuration problem, not a bug.
4. **It does not retry your notifications.** If a payee is unreachable you get `502` and *nothing was
   delivered*. The durable copy has to live with you, because an outbox on X9.150's side would be no
   help when you cannot reach X9.150 either.

## Payee sequence

```
POST /api/v1/payment-request                 → { id, qrCode (EMV), location.endpoint }
   ↓  show the QR; the payer's app scans it
POST /pub/api/v1/loc/{id}                    ← the payer fetches the signed payload (X9.150 answers)
POST /pub/api/v1/payment-notification        ← the payer's PSP reports (X9.150 verifies the JWS)
   ↓
GET  /pub/api/v1/events?after={cursor}       → you learn about it here
   ↓  your rail confirms the funds actually arrived
PUT  /api/v1/payment-request/{id}/status-update  { "status": "PAID", "endToEndId": "…" }
```

**`PAYMENT_INITIATED` is accepted only from `ACTIVE`.** A QR Code already initiated, paid or
cancelled is rejected with **409**, and the body reports the status actually found. That 409 is the
mechanism preventing a second payment against a QR Code someone is already paying — treat it as a
normal outcome to handle, not an error to log and ignore.

**`endToEndId` is mandatory when the status is `PAID`.**

## Payer sequence

```
POST /api/v1/qrcode-emv-decoder   { "qrCode": "<scanned EMV>" }
   → X9.150 fetches the payee's payload over HTTPS and verifies their signature for you
   → read paymentNotification (where to notify) and paymentMethods[] (what you may pay)

POST /api/v1/payment-notification/pre-payment      ← announce; NO transactionId
   → 200 { accepted: true } means the QR Code is reserved. Pay EXACTLY what you announced.
   → 200 { accepted: false } is a REFUSAL, not an error. Do not pay.
   → 502 means undelivered. Retry.

   ↓  now actually move the money on your rail

POST /api/v1/payment-notification/post-payment     ← report; transactionId REQUIRED
```

**The phase is inferred from `transactionId`, because X9.150 has no phase marker** — the committee
rejected one ([ADR-0004](../../../docs/adr/0004-phase-inferred-from-transaction-id.md)). A
pre-payment carrying a `transactionId` reads as a payment already made: the QR Code is never
reserved, and you pay against something another payer can still claim. X9.150 refuses that with a
400 before anything is sent, but design so it cannot arise.

**Rails differ.** ACH and Solana reserve the QR Code on a pre-payment. FedNow and RTP carry the QR
Code id inside the ISO 20022 message and reconcile from it, and the notification needs an
End-to-End ID that exists only once the payment has been initiated — so for those, go straight to
`post-payment`.

## Consuming the event stream correctly

```
GET /pub/api/v1/events?after={cursor}&limit=100&wait=25
```

```json
{ "events": [ { "eventId": "…", "type": "payment.sent", "qrCodeId": "…",
                "amount": 22500, "currency": "USDC", "transactionId": "…",
                "schemaVersion": "1.0" } ],
  "nextCursor": "01M3JFXNB4XNR7P61NZVG8474J", "hasMore": false }
```

1. **Deduplicate by `eventId`.** At-least-once delivery; the id is stable across re-publishes.
2. **Persist the cursor only after the events are stored.** Cursor-first loses events silently.
3. **One poller per deployment.** Two pollers each see everything and isolate nothing.
4. **`payment.sent` ≠ paid.** Only `payment.cleared` means funds arrived, and it is emitted because
   *you* said so.
5. **Ignore unknown fields and unknown `type` values.** They are additive within a major version;
   failing closed will break you on our next release.

`wait` (0–30s) long-polls, so an idle consumer costs one parked request rather than a poll loop.
Ordering holds **per `qrCodeId`** only. `qrCodeRevision` is monotonic per QR Code but **not
gap-free** — a notification that merely records details bumps it without emitting an event.

## Deployment constraints that bite

- **Two deployments must talk over HTTPS.** A payer normalises any host that is not its own to
  `https` before fetching a payload or a JWK set. Plain HTTP fails silently between instances while
  working fine for a single instance talking to itself — which is why it survives local testing. See
  [`others/demo/README.md`](../../../others/demo/README.md).
- **The advertised host must be ≤ 37 characters**, because the loc URL has to fit EMV tag 26. The app
  refuses to start otherwise. Anonymous Cloudflare quick tunnels never fit.
- **What this deployment accepts is narrower than the format.** The payload format is
  currency-agnostic, but `supported-currencies.json` decides what is honoured, and networks not in
  `NetworkEnum` are refused at creation by name rather than carried silently
  ([ADR-0012](../../../docs/adr/0012-refuse-what-this-deployment-cannot-honour.md)).

## Refusals you should expect and handle

| Status | Meaning | What to do |
|---|---|---|
| `400` | validation or business rule; `violations[]` names the field | fix the request; do not retry unchanged |
| `401` | the JWS did not verify | check your signing identity and `crit` headers |
| `404` | no such QR Code | |
| `409` | status transition not allowed from the current status | read the reported status; usually someone else is paying |
| `502` | the payee was unreachable; **nothing was delivered** | retry later, from your own durable copy |

Problem responses are RFC 9457 `application/problem+json` with `type`, `title`, `status`, `detail`,
`instance` and, for validation failures, `violations[]`.

## Before you say the integration works

Run two instances against each other — one payee, one payer — and put a whole payment through:

```bash
docker compose up -d mongo mongo-setup
mvn -q package -DskipTests
./others/demo/two-instance-payment-cycle.sh
```

A single instance cannot tell you whether you are interoperable, only whether you are
self-consistent: both ends hold the same assumptions, so a message that is wrong in the same way at
both ends still round-trips. The first run of that script found three defects no unit test had.

## Where the truth lives

| Question | Source |
|---|---|
| exact request/response shapes | `x9-qrcode-infrastructure/src/main/resources/apis/openapi.yaml` |
| endpoints, event stream, HTTPS rules | [`ENDPOINTS.md`](../../../ENDPOINTS.md) |
| why a decision is the way it is | [`docs/adr/`](../../../docs/adr/) |
| how we read the standard | `official-spec/INTERPRETATION.md` |
| ready-made calls | `others/postman/postman_collection.json` |

The ANSI X9.150 standard text is copyrighted and **not** in this repository. Ground answers about
"the spec" in the tracked sources above, never in a recollection of the standard.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
