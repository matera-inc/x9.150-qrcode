# Known Bugs

Tracked, reproducible defects. Each entry lists how to reproduce, expected vs. actual behavior, and
the root cause as currently understood. Defects that are not yet fixed are listed under **Open**;
fixed ones stay under **Fixed**, for the record.

## Open

---

## BUG-003 — An unreachable CRL is not soft-failed when the certificate names no OCSP responder

- **Reported:** 2026-11-14 (Story 4.3, `X9-LOC-307`)
- **Severity:** Medium — when a CA's CRL server is down, every PSP it issued without an OCSP
  responder is refused: payload fetches and payment notifications alike.
- **Pinned by:** `others/blackbox/tests/test_payers.py::TestPayload::test_an_unreachable_revocation_source_soft_fails`
  (the `crl-unreachable-no-ocsp` cases are strict expected failures).
- **Area:** `JwsQRCodeSignatureService.validateCertificateChain` (infrastructure layer)

### Steps to reproduce

1. Issue a payer certificate from a trusted CA with a CRL distribution point on a host and port
   where nothing listens, and no Authority Information Access (no OCSP responder).
2. Sign a payment notification (or a payload request) with it and send it.

### Expected

ADR-0027, "Revocation, parity with Java", step 4: **soft-fail when neither the CRL nor OCSP
answers.** The payer is accepted, as it is when the certificate names both sources and both are
unreachable, or only an unreachable OCSP responder.

### Actual

401 `UNTRUSTED_CHAIN` on the notification endpoint, 400 on `/pub/api/v1/loc/{id}`.

| Sources the certificate names | Java today |
|---|---|
| CRL and OCSP, both unreachable | accepted (soft-fail) |
| OCSP only, unreachable | accepted (soft-fail) |
| **CRL only, unreachable** | **refused** |
| neither | refused (no source to consult; `X9-LOC-308`) |

### Root cause

`PKIXRevocationChecker` runs with `PREFER_CRLS` and `SOFT_FAIL` but without `NO_FALLBACK`. The
unreachable CRL raises `UNDETERMINED_REVOCATION_STATUS`, which `SOFT_FAIL` would forgive; the JDK
then falls back to OCSP, finds no responder in the certificate, and raises a failure with an
unspecified reason, which `SOFT_FAIL` does not forgive. The checker rethrows the CRL failure.

### Suggested direction

Either add `NO_FALLBACK` when the end entity has no AIA OCSP entry, or catch the fallback's
"no responder" case and let the CRL's soft failure stand. Decide together with ADR-0027's Go
implementation, which must give the same four answers.

## Fixed

---

## BUG-002 — A status-update `PAYMENT_INITIATED` on a lapsed reservation reserves nothing

- **Reported:** 2026-11-14 (Story 4.1, ADR-0035 row X9-MAP-010)
- **Severity:** Medium — the payment system believes it has reserved a QR Code that any payer can
  still take.
- **Fixed:** 2026-11-14 — the X-to-X no-op now requires both the stored and the effective status to
  match the request. Pinned by `X9-LIFE-142`/`X9-LIFE-143` (`StatusUpdateAfterLapsedReservationApiTest`)
  and `others/blackbox/tests/test_transition_map.py::test_map_010_status_update_payment_initiated_on_a_lapsed_reservation`.
- **Area:** `UpdateQRCodeStatusUseCase` (application layer)

### Steps to reproduce

1. A payer pre-commits a QR Code: `PAYMENT_INITIATED`, held by that payer.
2. Let the reservation lapse (`x9.reservation.ttl-seconds`, 90 s by default; or move the test
   clock past it). `GET` now reports `ACTIVE`.
3. `PUT /api/v1/payment-request/{id}/status-update` with `{"status": "PAYMENT_INITIATED"}`.

### Expected

ADR-0035's map allows a status-update `PAYMENT_INITIATED` from `ACTIVE` (effective), so a lapsed
reservation counts: the QR Code is reserved again, held by nobody identifiable, and
`payment.initiated` is published. Another payer's pre-commit is then refused with 409.

### Actual

200 with `"status": "ACTIVE"`. Nothing is written and no event is published; the QR Code still
reads `ACTIVE`, and the next payer's pre-commit takes it.

### Root cause

The X-to-X no-op in `UpdateQRCodeStatusUseCase.execute` compares the requested status with the
**stored** status. The stored status of a lapsed reservation is still `PAYMENT_INITIATED`, so the
request is treated as a repeat and short-circuited before `QRCodeEntity.initiatePayment` (which
reads the effective status and would accept it) is reached. The comment there chose the stored
status for the `ACTIVE` case; for `PAYMENT_INITIATED` the same choice drops a real transition.

### Suggested direction

Compare the effective status for `PAYMENT_INITIATED` (or for every status: on a lapsed reservation
an `ACTIVE` request is already refused by `reactivate`). Decide with the owner's answer on the X-to-X
cells of ADR-0036's matrix (Story 4.1, "Questions for the owner").

---

## BUG-001 — Early-payment discount is not applied to pegged alternate-currency payment methods

- **Reported:** 2026-07-20
- **Fixed:** 2026-07-20
- **Severity:** Medium — payer is overcharged on the alternate currency; discount silently omitted.
- **Status:** Fixed
- **Area:** Retrieve payload / adjustment formula (application layer)

### Summary

When a QR accepts more than one currency from the same pegged group (e.g. `USD` **and** `USDC`),
an early-payment discount is applied **only** to the payment method whose currency matches the
**bill** currency. Payment methods in the pegged alternate currency are returned at their full,
undiscounted amount.

### Steps to reproduce

1. Create a payment request whose `bill.amountDue.currency` is `USD` with a
   `FixedDiscountLateFeeLinearInterest` discount (e.g. `daysBefore: 10`, `discount: 250`), and two
   `paymentMethods`: one in `USD` and one in `USDC` (pegged 1:1).
   (See `playground/requests/qr-cloudprovider-createqr.json`.)
2. Fetch the payload (`POST /pub/api/v1/loc/{locationId}`) with a `dateForPayment` at least 10 days
   before the due date, so the discount is eligible.

### Expected

Both pegged payment methods reflect the discount. For a 5% early discount on USD 50.00, the payer
should owe the equivalent of $47.50 on **either** rail — `USD 4750` and `USDC 47.50` (`47500000`
in USDC minor units).

### Actual

Only the USD method is discounted; the USDC method keeps its full amount:

```json
"paymentMethods": [
  { "currency": "USD",  "amount": 4750 },      // discounted (5000 - 250)  ✅
  { "currency": "USDC", "amount": 50000000 }   // NOT discounted            ❌
]
```

### Root cause

`RetrieveQRCodePayloadUseCase.execute(...)` computes a **single** `FormulaResultDTO` from the bill's
amount and currency only:

- `x9-qrcode-application/.../retrievepayload/RetrieveQRCodePayloadUseCase.java:69-75` — passes
  `bill.amountDue().currencyAmount().amount()` / `...currency()` (USD) into `formulaService.calculate(...)`.

`RetrieveQRCodePayloadPaymentMethodMapper.map(...)` then applies that result to a payment method
**only when the method's currency equals the formula's `targetCurrency`**:

- `x9-qrcode-application/.../retrievepayload/mapper/RetrieveQRCodePayloadPaymentMethodMapper.java:39-47`
  — `checkCurrencyPredicate` returns `true` (→ keep the method's own amount) whenever
  `!paymentCurrency.equals(formula.targetCurrency())`. So any method not in the bill currency is
  left undiscounted.

The discount is modeled as a fixed minor-unit amount in the bill currency and is neither converted
nor pro-rated to other members of the pegged group. Note a naïve subtraction would also be wrong:
minor-unit scale differs across currencies (USD has 2 decimal places, USDC has 6), so the fix must
pro-rate by ratio (e.g. apply the discount *percentage* `discount / originalAmount` to each method's
own amount), not subtract the raw `250`.

### Suggested direction

Apply the adjustment proportionally to every payment method within the pegged group — compute the
discount as a ratio of the bill amount and scale each method's own amount by it — rather than
applying a fixed bill-currency delta to the single matching-currency method. Confirm the intended
product behavior (per-currency fixed amount vs. pegged-group-wide rate) before implementing.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
