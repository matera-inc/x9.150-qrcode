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

Annex **A.9** (status lifecycle) names the ISO 20022 triggers directly:

> "On initial network acceptance of the credit transfer request (pacs.008), the Payee's PSP
> **SHOULD** update status to 'PAYMENT_INITIATED'"
>
> "On network confirmation of settlement (pacs.002), the Payee's PSP **SHOULD** update status to
> 'PAID.'"
>
> — ANSI X9.150-2026 §A.9

and states the rationale for the transition — independently the rationale for ADR-0002:

> "On receipt of a payment notification, The Payee's PSP **SHOULD** update status to
> 'PAYMENT_INITIATED' to prevent duplicate payment."
>
> — ANSI X9.150-2026 §A.9

The remaining lifecycle rules (Annex A.9, **Table 3 field 8** `$.status`, and §7), all of which
`QRCodeEntity` already matches — only a `PAYMENT_INITIATED` payload may revert to `ACTIVE`, and:

> "Once the payload status is 'PAYMENT_INITIATED', 'PAID', or 'CANCELLED', it **SHALL NOT** be
> revised." — ANSI X9.150-2026 §7

ACH is the exception: the standard defines **no network finality event** for it, so the transition to
paid is implementation-dependent and out of scope — which is why the notification path carries the
weight there.

The standard is not distributed with this repository; see
[`official-spec/README.md`](../../official-spec/README.md) to purchase it.

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
