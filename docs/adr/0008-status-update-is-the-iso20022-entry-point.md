# ADR-0008 — Status-update is the rail-agnostic entry point (pacs.008)

- **Status:** Accepted
- **Date:** 2026-09-26
- **Context:** `supporting-payment-notifications`

## Context

Not every initiation arrives as a payment notification. On ISO 20022 rails the payee's own platform
learns of a payment from the **pacs.008** (the credit-transfer request) and should lock the QR then —
before any courtesy notification. That needs a plain *"move this QR to `PAYMENT_INITIATED`, and fail
if it is not `ACTIVE`"* call.

## Decision

Wire the **already-advertised** `PAYMENT_INITIATED` case of `PUT /api/v1/payment-request/{id}/status-update`
to the same atomic conditional update as ADR-0002. **X9.150 does not parse ISO 20022**; the adopter
maps the pacs.008 to one call.

- Document returned → **200** with the `paymentId`.
- `null` → **409 carrying the current status** — already initiated, paid, or cancelled. That *is* the
  "is this QR still valid" check, answered atomically rather than by a read-then-write the caller
  could race.
- **No biller vote.** The caller here *is* the payee's platform; asking it to approve its own call
  would be circular. ADR-0003 governs notifications from the payer's side only.
- **Idempotent** on an optional caller-supplied `paymentId`, so a pacs.008 retry is safe.

## This closes a conformance gap, it is not a new feature

Grounded in ANSI X9.150 Annex **A.9** and **Table 3 field 8** (`$.status`), paraphrased rather than
quoted since the standard is not distributed with this repository:

- On initial network acceptance of the credit transfer request (**pacs.008**), the payee's PSP
  **SHOULD** set status to `PAYMENT_INITIATED`.
- On network confirmation of settlement (**pacs.002**), it **SHOULD** set `PAID`.
- On receipt of a payment notification it **SHOULD** set `PAYMENT_INITIATED` — and the standard states
  the reason in as many words: **to prevent duplicate payment**, which is independently the rationale
  for ADR-0002.
- Only a `PAYMENT_INITIATED` payload may revert to `ACTIVE`; once initiated, paid or cancelled it
  **SHALL NOT** be revised. Both already match `QRCodeEntity`.
- ACH has **no network finality event**; the transition to paid is implementation-dependent and out of
  scope — which is why the notification path carries the weight there.

**Current state — the contract promises this and the code refuses it:**

| Layer | `PAYMENT_INITIATED` on status-update |
|---|---|
| `openapi.yaml` `StatusUpdate.status` enum | **Advertised** |
| `UpdateQRCodeStatusUseCase` | **Rejected** — `default -> throw "Status PAYMENT_INITIATED is not allowed."` |

A caller following the published contract gets a `400`. `PAID`, `CANCELLED` and `ACTIVE` are wired;
`PAYMENT_INITIATED` was left out.

## Consequences

- The gap between contract and implementation closes; no OpenAPI change is needed, only code.
- The adopter gets a rail-agnostic entry point usable **before** any notification work lands, which is
  why it is sequenced early and independently.
- `pacs.002 → PAID` needs nothing new — the existing `pay()` path covers it. **Open question:** that
  path is not conditional on `paymentId` unless one is supplied, so a late pacs.002 could settle a QR
  that a different payment had since locked. Accepting an optional `paymentId` on `PAID` too would let
  an ISO 20022 caller close exactly the initiation it opened.

## Alternatives rejected

**Teach X9.150 to parse pacs.008:** drags ISO 20022 into a component whose job is the QR lifecycle,
and would have to be repeated for every rail. The adopter already parses its own rail.

**Require a payment notification for ISO 20022 rails:** contradicts the standard, which names the
pacs.008 as the trigger, and would force a courtesy message into a mandatory position.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
