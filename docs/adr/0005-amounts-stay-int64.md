# ADR-0005 — Monetary amounts stay `int64` minor units

- **Status:** Accepted
- **Date:** 2026-09-26
- **Context:** `supporting-payment-notifications`

## Context

The event contract needed an amount type. Because X9.150 carries digital-asset tickers and EVM chains
represent token amounts as `uint256` on-chain, widening was considered — as decimal strings, and as an
Avro `decimal` over `fixed(32)`/`fixed(33)`.

## Decision

**Amounts stay `int64` minor units**, as JSON numbers, in the event contract exactly as in
`AmountVO` and in every amount field of `openapi.yaml`.

This is not a preference — the standard mandates it. From the `Amount (Minor Units)` data type
definition:

> "**Shall** be a 64 bit integer (see specific field-level requirements on whether can be negative)"
> — ANSI X9.150-2026, Data Types

and of the payment amount specifically:

> "**Shall** be a 64 bit integer with minimum value = 0."
> — ANSI X9.150-2026 §2.1

The rest, checked against the §13.5 / §14.3 / §2.1 field tables:

- Tip amount (§2.2) is likewise non-negative. The bill **adjustment**
  amount (§13.5.3.2) is the signed one, and arrives as an **array whose entries may mix signs** — a
  discount and a late fee together.
- Amount fields carry a **max length of 18 digits**, *tighter* than `int64`'s 19.

**Widening X9.150's amount type would be a non-conformance, not an enhancement.**

## Consequences

- An 18-decimal asset is bounded at **9.2234 whole units** (ETH, DAI). A conformant payload cannot
  express 10 ETH. This is a limitation **of the standard**, and the venue for changing it is ASC X9,
  not this codebase. EVM rails should carry a documented amount limitation.
- The widely used dollar stablecoins (USDC, USDT) are **6-decimal** and unaffected — ~9.22 trillion
  units of headroom.
- Consumers needing a wider representation convert on receipt, from a value they already trust.
- **Consumer note:** `int64` minor units exceed JavaScript's exact integer range (2^53 ≈ 9.0e15), so a
  JS/TS consumer must use a bigint-aware JSON parser. A parsing concern, not a wire-format change.
- **Conformance gap, fixed 2026-11-14:** `AmountVO` used to validate only non-null and
  non-negative, and the `Amount` schema had no `maximum`, so 19-digit amounts were accepted. Every
  amount the service takes is now refused past 999999999999999999 (18 nines): the `Amount` schema,
  the editable range, discounts and late fees carry `maximum` in `openapi.yaml` (the adjustment
  amount also `minimum`, as it is signed), and `AmountVO.requireAtMost18Digits` enforces it once, for
  every path, including signed notifications that bypass bean validation. Tests: `X9-AMT-110` to
  `X9-AMT-112` (`AmountVOTest`), `X9-AMT-120` to `X9-AMT-123` (`AmountEighteenDigitsApiTest`), and
  the black-box `X9-AMT-421` and `X9-AMT-006`. A late fee grows every day but stops at the QR
  Code's `validUntil`, so a bill whose amount plus the largest late fee (`fixed + perDay × days
  late at validUntil`), or any payment-method amount as the payload would show it with that fee,
  would pass 18 digits is refused at creation and on PATCH, and the formula refuses rather than
  emits such an amount (`X9-AMT-113` to `X9-AMT-119`, `X9-AMT-124` to `X9-AMT-126`, black-box
  `X9-AMT-423`).
- **A fraction is refused, never truncated (fixed 2026-11-17).** Both builds read an amount sent as
  `6.5` as `6` (Jackson's `ACCEPT_FLOAT_AS_INT`, which the Go build had reproduced) and answered 201:
  the stored debt was not the one asked for. Every integer member the service takes — every amount
  of a create, a PATCH and a notification, inbound or outbound, and the tip percentages and
  `daysBefore` beside them — is now refused with 400 naming the field when the number is written
  with a fraction or an exponent. `6.0` and `6e2` are refused too: the contract says integer, and
  the reader never has to decide whether a written fraction is "really" zero. Tests:
  `contract/integer_test.go`, black-box `X9-AMT-424` to `X9-AMT-426`.

## Alternatives rejected

**Decimal strings / `uint256` sizing:** advertises a range the standard forbids us to produce.

**Avro `decimal(78,0)` over `fixed(33)`:** moot under ADR-0001 — X9.150 serializes no Avro. It would
also have been *both* wider than the standard permits *and* permanently frozen, since Avro can neither
promote `long`→`fixed` nor resize a `fixed`.

**A `decimals` field:** X9.150 is currency-agnostic and does not know an asset's scale; emitting one
would mean inventing it. Scale comes from the currency/asset definition on the consumer side.

## Guidance for bridge authors

A bridge (ADR-0001) that re-serializes to Avro should use `long`. Widening later is possible **only by
adding a defaulted field**, never by retyping — `long` does not promote to `bytes` or `fixed`, so a
Schema Registry will reject an in-place change. That is the bridge author's decision, not ours.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
