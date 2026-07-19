# Plan — Non-USD-Pegged Currencies (request-time FX & per-currency `validUntil`)

Status: **Proposed** (design/plan only — not yet implemented)
Scope: `x9-qrcode-application` (new output port + use-case wiring), `x9-qrcode-infrastructure` (FX adapter), OpenAPI docs.

## 1. Problem

A QR Code's bill is denominated in a single **invoice currency** (in practice USD). The payload, however, can offer the payer **multiple `paymentMethods`, each with its own `currency`** (`USD`, `CAD`, `USDC`, and future assets). Today the amount for every currency must be **supplied statically at creation** and is simply echoed back at fetch time — there is **no currency conversion** and **no rate-aware `validUntil`**.

We want the system to, **at payer-request (fetch) time**, produce the correct amount and a correct `validUntil` for currencies other than the invoice currency, calling an external FX rate provider **only when the currency is not pegged to the invoice currency**.

## 2. Currency classification

| Class | Examples | FX call needed? | Amount basis |
|-------|----------|-----------------|--------------|
| **Invoice currency** | USD | No | Formula-adjusted amount (discount/late-fee) |
| **USD-pegged** | USDC (1:1 stablecoin) | No (peg = 1:1, adjust scale only) | Peg of the invoice amount |
| **Non-USD-pegged** | CAD, and future BTC/ETH | **Yes** — live rate | Invoice amount × rate, from the FX provider |

`CurrencyEnum` today: `USD` (scale 2), `CAD` (scale 2), `USDC` (scale 6). The peg classification must be explicit config, not inferred (e.g., a per-currency `peggedTo` attribute).

## 3. The rule (normative)

At fetch time, for each payment-method currency, compute a `validUntil` = **the earliest of every window that constrains the amount being stamped**:

- **Currency == invoice currency**, or **USD-pegged**:
  ```
  validUntil = min($.validUntil, $.bill.amountDue.adjustments[].validUntil)
  ```
- **Non-USD-pegged** (requires FX):
  ```
  rate, fxValidUntil = FX_API(from = invoiceCurrency, to = targetCurrency)
  validUntil = min(fxValidUntil, $.validUntil, $.bill.amountDue.adjustments[].validUntil)
  ```

Where:
- `$.validUntil` — the overall payload validity (standard §7).
- `$.bill.amountDue.adjustments[].validUntil` — the discount/late-fee window (standard §13.5.3.3). If no adjustment applies, this term is omitted from the `min`.
- `fxValidUntil` — the rate provider's quoted validity window.

If a biller-provided per-currency `validUntil` exists (standard §14.2 marks it Mandatory), the system **tightens** it rather than replacing it:
```
effectiveValidUntil = min(billerProvidedCurrencyValidUntil, <computed validUntil above>)
```

## 4. Why this is standard-aligned (ANSI X9.150)

Refs: ANSI X9.150-2026 (section references below) — see [`official-spec/README.md`](official-spec/README.md).

- **§7 `$.validUntil`** — payload is the hard outer bound; nothing is payable past it → always in the `min`.
- **§13.5.3.3 adjustment `validUntil`** — the stamped amount reflects the *current* discount/fee, so it must not be presented as valid past the adjustment window. The standard's Note says apps **SHOULD re-fetch** once the adjustment expires; capping at this window **forces** that re-fetch → safe and consistent.
- **§14.2 payment-method `validUntil`** — its Note explicitly reserves this field for *"currency rate or digital asset settlement windows"*, and states it "may not be strictly needed" for USD. That is exactly the `fxValidUntil` term for non-pegged currencies, and the "not strictly needed" case for USD/pegged.

Conservative-but-safe: the standard *permits* paying without the discount after adjustment expiry, but only via a re-fetch to correct terms — which our cap triggers.

## 5. Current behavior (baseline to change)

- `PaymentMethodVO` = `{ currency, validUntil, amount, networks, editable? }` — all provided at creation; `validUntil` and `amount` mandatory.
- `RetrieveQRCodePayloadUseCase` computes the discount/late-fee via `FormulaService` **only for the invoice currency**; `RetrieveQRCodePayloadPaymentMethodMapper` applies the formula amount only to the method whose `currency == formulaResult.targetCurrency`, and echoes every other currency's **static** amount and `validUntil` unchanged.
- No FX / exchange-rate logic exists anywhere.

## 6. Proposed design (Clean/Hexagonal, mirrors `FormulaService`)

### 6.1 New output port (application layer)
```
com.matera.x9qrcode.app.service.CurrencyConversionService   (interface, no Spring)

record ConversionResultDTO(
    Integer targetAmount,        // minor units in target currency scale
    String  targetCurrency,
    OffsetDateTime fxValidUntil) // rate provider's validity window

ConversionResultDTO convert(
    Integer sourceAmount,        // invoice-currency minor units (post-adjustment)
    String  sourceCurrency,
    String  targetCurrency,
    OffsetDateTime dateForPayment);
```
Peg handling is a domain concern: a `CurrencyEnum.peggedTo(...)` (or a `PeggedCurrencyPolicy` domain service) decides pegged vs non-pegged; the use case only calls `CurrencyConversionService` for non-pegged targets.

### 6.2 Infrastructure adapter
```
com.matera.x9qrcode.infrastructure.service.thirdparty.fx.<Provider>CurrencyConversionService
```
- Calls the configured FX provider over HTTPS (virtual-thread friendly), maps its response to `ConversionResultDTO`.
- Config via `x9.fx.*` properties (base URL, credentials, timeout, cache TTL). Secrets follow the existing jasypt/Helm/`.env` conventions.
- A no-op/echo stub can ship first so the wiring lands before a real provider is chosen.

### 6.3 Use-case wiring
In `RetrieveQRCodePayloadUseCase.execute(...)`, per payment method:
1. Determine class (invoice / pegged / non-pegged).
2. Amount:
   - invoice → `formulaResult.amount()` (existing behavior),
   - pegged → peg of invoice amount (scale-adjusted),
   - non-pegged → `CurrencyConversionService.convert(...)` result.
3. `validUntil` → the `min(...)` per §3.
4. Feed both into `RetrieveQRCodePayloadPaymentMethodMapper` (extend it to accept the computed per-currency amount + `validUntil`, replacing today's static echo).

## 7. Edge cases & rules

- **No adjustment present** → drop the adjustment term from `min`; amount is the base invoice amount (or its conversion).
- **Multiple adjustments** (`adjustments[]` per standard) → `min` over all their `validUntil`s. (Today the code models a single adjustment — may need generalization.)
- **FX fetch failure / timeout** → decide policy: (a) omit that currency's payment method from the payload, or (b) fail the request. Recommend **(a)** — degrade gracefully, still offer the invoice/pegged currencies. Must be logged.
- **Rounding / scale** → convert in minor units honoring each `CurrencyEnum.scale` (USD/CAD 2, USDC 6); define a rounding mode (e.g., round-half-up) and document it.
- **`dateForPayment` in the future** → the FX quote must be for that instant if the provider supports forward quotes; otherwise use spot and cap `fxValidUntil` accordingly.
- **Rate caching** → cache by `(from,to)` within `fxValidUntil` to avoid a provider call per request (respect provider TTL).
- **§14.2 mandatory field** → keep biller-provided per-currency `validUntil` accepted at creation; system only ever **tightens** it (never extends).

## 8. Non-goals

- No change to the create-time contract's required fields (still standard-compliant).
- No new payment rails/networks.
- No FX for the invoice currency or USD-pegged currencies (no unnecessary provider calls).
- Choosing/integrating a specific FX vendor is out of scope for this plan (a stub port ships first).

## 9. Implementation phases

1. **Domain**: add currency peg classification (`peggedTo` on `CurrencyEnum` or a policy service); unit tests.
2. **Application**: add `CurrencyConversionService` port + `ConversionResultDTO`; extend `RetrieveQRCodePayloadUseCase` + mapper with the amount/`validUntil` derivation; unit tests with a fake port.
3. **Infrastructure**: add FX adapter (stub first) + `x9.fx.*` config + bean wiring; caching; failure policy.
4. **Docs**: OpenAPI — document per-currency `validUntil` semantics (rate windows) and the fetch-time derivation; note pegged vs non-pegged. Update the project conventions.
5. **Deploy**: `x9.fx.*` values in Helm chart + `docker-compose*.yml` (inline for the compose path).

## 10. Testing

- **Unit** (domain/app, no Spring): `min(...)` derivation across all combinations (with/without adjustment, pegged/non-pegged, biller-provided tighter/looser, future `dateForPayment`).
- **Integration** (`@DatabaseTest`, Testcontainers Mongo): create a multi-currency QR, fetch it, assert per-currency amount + `validUntil` and that the FX port is called only for non-pegged currencies (fake adapter).
- **Failure**: FX timeout → currency omitted, others returned, error logged.

## 11. Open questions

- Which FX provider/endpoint? (drives the adapter; stub until decided)
- Peg policy source of truth — static config vs a periodically-refreshed reference?
- Forward-dated quotes (for future `dateForPayment`) — supported by the chosen provider?
- On FX failure: omit the currency (recommended) vs fail the whole payload?

---

## Appendix — worked example

Invoice: USD, `dueDate` end-of-day 2026-12-31 UTC, a 60-days-before discount valid until `2026-11-01T23:59:59Z`; payload `$.validUntil = 2026-12-31T23:59:59Z`. Payer requests on 2026-10-15 and selects **CAD** (non-USD-pegged):

1. Formula (invoice currency) → discounted USD amount, adjustment `validUntil = 2026-11-01T23:59:59Z`.
2. CAD is non-pegged → FX call returns rate + `fxValidUntil = 2026-10-15T18:30:00Z` (a short rate window).
3. CAD amount = discounted-USD × rate (CAD scale 2, rounded).
4. `validUntil(CAD) = min(2026-10-15T18:30:00Z, 2026-12-31T23:59:59Z, 2026-11-01T23:59:59Z) = 2026-10-15T18:30:00Z` — the rate window binds; after it, the payer app re-fetches for a fresh quote.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
