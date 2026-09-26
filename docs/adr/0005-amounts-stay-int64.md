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
- **Conformance gap found:** `AmountVO` validates only non-null and non-negative, and the `Amount`
  schema declares `format: int64` with no `maximum`, so we currently accept 19-digit amounts that
  exceed the standard's 18-digit field length. Small, self-contained, and genuinely ours to fix.

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
