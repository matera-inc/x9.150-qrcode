# Known Bugs

Tracked, reproducible defects that are not yet fixed. Each entry lists how to reproduce, expected
vs. actual behavior, and the root cause as currently understood.

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
