# 18. A patch names every currency the QR Code offers, or none of them

Date: 2026-09-30

## Status

Accepted.

## Context

`paymentMethods` entries are matched and merged **by currency**. A patch could therefore name a
currency the QR Code did not offer, and the mapper would find no match, return `null`, and filter it
out before `QRCodeEntity` ever saw it.

The entity has had the right rule all along:

```java
if (!paymentMethodsMap.containsKey(paymentMethod.currency())) {
    throw new BusinessRuleException(
        "Could not find any paymentMethods with currency %s to be updated.".formatted(...));
}
```

It could not fire. A layer above had already thrown away the evidence, so the guard only ever saw
entries that matched by construction. Measured against `git-205ec5a`, a QR Code offering USD and
USDC at 22500 each:

| patch | before | after | answer |
|---|---|---|---|
| USD 11250 + USDC 11250 + **FRNT** 11250 | 22500 / 22500 | 11250 / 11250 | **200** — FRNT vanished |
| USD 11250 only | 22500 / 22500 | **11250 / 22500** | **200** — USDC left behind |
| FRNT only | 22500 / 22500 | unchanged | **200** — nothing applied |

The **revision incremented in every one of those**, so the `ETag` moved and a caller using
`If-Match` got a fresh tag back. Every signal said the write landed: 200, no violations, new ETag.
The conditional-request machinery cannot catch this class — the precondition is working, the write
is real, it simply does not contain what was sent.

Row 2 is the one with teeth. Reducing a bill after a partial payment is the common use of this
endpoint, and leaving a currency out left **one debt with two prices**, depending on which rail the
payer chose to settle it with.

## Decision

A patch that names any currency in `paymentMethods` must name **every** currency the QR Code offers,
and no currency it does not offer.

- a currency that is not on the QR Code → **400** naming it. A patch may change the amount of a
  currency already there; it may never add one. To offer a new currency, issue a new QR Code.
- a currency on the QR Code left out of the patch → **400** naming it.
- nothing is written in either case, so the revision and the `ETag` do not move.

`paymentMethods` is required on every patch, so in practice "all or nothing" always means all: a
patch that only moves `locationId` still restates every currency at its current amount.

The check lives on `QRCodeEntity.requirePaymentMethodCurrencies` and runs in `PatchQRCodeUseCase`
*before* the mapper, because the mapper is what destroys the information. The mapper's own
unmatched branch now throws instead of returning `null` — unreachable, and kept loud rather than
silent in case it ever stops being.

## Consequences

**This is ours, not ANSI X9.150-2026's.** The standard describes the payload; it says nothing about
how a payment request is edited. Documented on the endpoint in `openapi.yaml`, where a caller
building the body will actually read it, and in the entity's javadoc.

**A caller that patched one currency of a multi-currency QR Code now gets a 400 where it used to get
200.** That is the point: it was applying half an edit and being told it applied all of it.

**Single-currency QR Codes are unaffected** — naming the only currency satisfies the rule, which is
every QR Code the adopter currently has in production.
