# Known Bugs

Tracked, reproducible defects. Each entry lists how to reproduce, expected vs. actual behavior, and
the root cause as currently understood. Defects that are not yet fixed are listed under **Open**;
fixed ones stay under **Fixed**, for the record.

## Open

---

## BUG-004 — An expired `ACTIVE` QR Code does not give up its location to a rebind

- **Reported:** 2026-11-14 (Story 4.2, S4.2-AC4)
- **Severity:** Low — a biller re-pointing an expired printed code gets a 400 until MongoDB's TTL
  monitor deletes the holder, normally within about a minute of its `validUntil`.
- **Pinned by:** `others/blackbox/tests/test_payment_requests_pinned.py::test_an_expired_active_holder_of_the_same_creditor_gives_up_its_location`
  (`X9-LOC-421`), a strict expected failure on Java and an ordinary test on Go.
- **Area:** `QRCodeEntity.releaseLocation` (domain)
- **Go build:** fixed. A create (Story 9.6) releases an expired `ACTIVE` holder of the same
  creditor (`domain.HolderIsLive` checks `valid_until`); `internal/paymentrequest`'s
  `AnExpiredActiveHolderGivesUpItsLocation` test proves it against PostgreSQL. `X9-LOC-421` passes
  on Go, where the whole suite is required (Story 10.8).

### Steps to reproduce

1. Create a QR Code with `validUntil` two minutes ahead.
2. Move the test clock three minutes forward (or wait, and race the TTL monitor).
3. Create a QR Code for the same creditor with `locationId` set to the first one's location.

### Expected

ADR-0024's location rule: a holder is not live when its stored status is `PAID` or `CANCELLED`, **or
its `validUntil` is in the past**. The create is accepted, and the expired holder moves to a fresh
random location without an event.

### Actual

400 `The QRCode needs to be inactive to reuse location. Current status: ACTIVE`; nothing changes.

### Root cause

`releaseLocation` asks `isNotActiveOrInitiated()`, which reads the status and never `validUntil`.
Java got away with it because the TTL index deletes an expired QR Code, after which the location is
simply free. ADR-0024 names this "the gap closed" for the Go build, whose rows are retained.

### Suggested direction

Leave the frozen Java build as it is and let the Go build close the gap, as ADR-0024 decides; or, if
Java is to agree with the suite, make `releaseLocation` also accept `isExpired()`.

## Fixed

---

## BUG-006 — `x5t#S256` naming a CA of the chain made that CA the signer

- **Reported:** 2026-11-15 (Story 8.5, Go's verifier)
- **Severity:** Medium — the chain still had to validate against the truststore and the CA's key
  usage had to allow `digitalSignature`, so only a trusted CA's own key could exploit it. But such
  a CA could sign a message as itself while presenting any payer's leaf as the end entity, and the
  service took it as that payer's.
- **Fixed:** 2026-11-15 — `x5t#S256` must be the thumbprint of the end-entity certificate, the
  first of the chain; one naming any other certificate is refused with `UNTRUSTED_CHAIN`, as Go's
  `internal/pki` does. Pinned by `X9-SIG-069` (`CaIssuedSignatureApiTest`) and the tamperer's
  `x5t_names_the_ca` case in `X9-SIG-510`, `X9-LOC-304` and `X9-SIG-506` (black-box suite).
- **Area:** `JwsQRCodeSignatureService.validateAndExtractCertificateByThumbprint` (infrastructure
  layer), shared by payer notifications, payload requests and payee answers.

### Steps to reproduce

1. Take a CA in the truststore whose key usage allows `digitalSignature` (the test CA re-issued to
   itself: `x9-test-ca-digital-signature.pem`).
2. Sign a payment notification with that CA's key, with `x5c` = [a payer's leaf issued by it, the
   CA] and `x5t#S256` = the CA's thumbprint.
3. Send it.

### Expected

ANSI X9.150-2026 §10.3.1: `x5t#S256` is "the SHA-256 thumbprint of the end-entity certificate";
§10.7 step 7 checks it against the end entity. The thumbprint names the CA, so the notification is
refused, 401 `UNTRUSTED_CHAIN`.

### Actual (before the fix)

200: the notification was accepted and the QR Code moved to `PAYMENT_INITIATED`, as from the
payer.

### Root cause

The thumbprint was matched against every certificate of the chain, and the first match became the
certificate whose key the signature was checked with. Nothing tied it to the end entity, which is
the one the chain validation and the revocation check are about.

---

## BUG-005 — A `jku` or `x5u` that merely mentions this deployment's host is fetched over plain HTTP

- **Reported:** 2026-11-15
- **Severity:** High — a payer message's `jku` or `x5u` is unauthenticated input. A URL on any
  host that carries this deployment's public host anywhere in it was fetched over plain `http`,
  so anyone on the network path could substitute the certificate the signature is checked with.
  The chain was still validated against the truststore, which limits what a substitute could
  prove, but ADR-0027's "HTTPS only, except the deployment's own host" did not hold.
- **Fixed:** 2026-11-15 — the URL's host, port included, must equal `x9.public-endpoints.host`
  (case-insensitive) to be read internally; every other URL is fetched over `https` from the host
  it names, without its user information, as Go's fetcher (Story 8.3) does. Pinned by
  `X9-LOC-483`, `X9-LOC-484` (`DefaultQRCodeLocationServiceTest`), `X9-SIG-068`
  (`CaIssuedSignatureApiTest`) and `X9-LOC-312` (`others/blackbox/tests/test_payers.py`).
- **Area:** `DefaultQRCodeLocationService.normalizeHost` (infrastructure layer), used for the
  `jku`/`x5u` fetch and for the payload fetch alike.

### Steps to reproduce

1. Sign a payment notification with an `x5u` of `http://evil.example/chain.pem?h=<public host>`
   (no `x5c`), where `evil.example` serves the signer's chain over plain `http`.
2. Send it.

### Expected

ADR-0027, "Certificate fetching": the chain is fetched over `https` from `evil.example`. A server
that speaks only plain `http` cannot answer, and the notification is refused with
`CERTIFICATE_UNAVAILABLE`.

### Actual (before the fix)

The chain was fetched over plain `http` from `evil.example` (with the public host in its query
replaced by `localhost:<port>`), and the payer was accepted. The same held for the public host in
the path, as the user information (`http://<public host>@evil.example/`), as a subdomain
(`<public host>.evil.example`) or as the end of a longer host name, which produced a broken URL
instead.

### Root cause

`normalizeHost` asked whether the URL *string* contained the public host, then replaced every
occurrence of it with `localhost:<port>` and forced `http`. It never parsed the host.

---

## BUG-003 — An unreachable CRL is not soft-failed when the certificate names no OCSP responder

- **Reported:** 2026-11-14 (Story 4.3, `X9-LOC-307`)
- **Severity:** Medium — when a CA's CRL server is down, every PSP it issued without an OCSP
  responder is refused: payload fetches and payment notifications alike.
- **Fixed:** 2026-11-14 — when the end entity names a CRL distribution point but no OCSP responder,
  the revocation checker no longer falls back to OCSP (`NO_FALLBACK`), so the unreachable CRL's soft
  failure stands. Pinned by `X9-SIG-055` to `X9-SIG-059` (`CaIssuedSignatureApiTest`) and by
  `others/blackbox/tests/test_payers.py::TestPayload::test_an_unreachable_revocation_source_soft_fails`
  (`X9-LOC-307`, now asserted on both endpoints, no longer an expected failure).
- **Area:** `JwsQRCodeSignatureService.validateCertificateChain` (infrastructure layer)

### Steps to reproduce

1. Issue a payer certificate from a trusted CA with a CRL distribution point on a host and port
   where nothing listens, and no Authority Information Access (no OCSP responder).
2. Sign a payment notification (or a payload request) with it and send it.

### Expected

ADR-0027, "Revocation, parity with Java", step 4: **soft-fail when neither the CRL nor OCSP
answers.** The payer is accepted, as it is when the certificate names both sources and both are
unreachable, or only an unreachable OCSP responder.

### Actual (before the fix)

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

### Fix

`validateCertificateChain` adds `NO_FALLBACK` only when the end entity carries a CRL distribution
point and its Authority Information Access names no OCSP responder by URI. The four cases now read:

| Sources the certificate names | Java |
|---|---|
| CRL and OCSP, both unreachable | accepted (soft-fail) |
| OCSP only, unreachable | accepted (soft-fail) |
| CRL only, unreachable | accepted (soft-fail) |
| neither | refused (no source to consult; `X9-LOC-308`, `X9-SIG-059`) |

A certificate a reachable CRL lists as revoked is still refused (`X9-SIG-053`, CRL only, no OCSP).
A certificate naming neither source keeps the fallback, and so stays refused: `NO_FALLBACK` there
would let the missing CRL soft-fail and accept a certificate nothing could ever revoke. The rule the
Go build ports (ADR-0027): a source the certificate names but that cannot be reached soft-fails; a
revoked certificate is refused; a certificate naming no source is refused.

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
