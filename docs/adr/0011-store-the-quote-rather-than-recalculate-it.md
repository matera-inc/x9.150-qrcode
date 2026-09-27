# ADR-0011 — Store the quote we presented, rather than recalculating it

- **Status:** Accepted — applies when FX lands; see *Current state*
- **Date:** 2026-09-27
- **Context:** `supporting-payment-notifications`

## Context

Every call to the loc URL may build a **fresh payload**. The amounts in it are computed at that
moment: the bill's adjustment is applied (a discount that is still earnable, a late fee that has
accrued), and in future a non-1:1 currency will carry an FX rate with its own validity window.

So when a payment notification arrives claiming to pay some amount, we have to answer: *is this an
amount we actually quoted?* There are two ways to know.

**Recalculate.** Recompute the amount at notification time and compare. This works only for figures
that are **derivable** — a pure function of stored parameters and time. Adjustments are exactly that.

**Store.** Record what we presented, and compare against it. This works for anything, and is the only
option for figures that are **not derivable**.

An FX rate is not derivable. It came from a provider at an instant, and recomputing it later yields a
*different* number by definition — not a slower answer to the same question, a different question.

## Decision

**Store the quote for every currency, uniformly.** Not "derive what we can, store what we cannot".

FX forces a store to exist. Once it exists, splitting the rule by currency type buys nothing and costs
real complexity: two code paths, two sets of failure modes, two behaviours to document, and a
conditional that has to be right in both branches. Uniformity is worth more than the property the
split would preserve.

### What the quote holds

Enough to answer the acceptance question, and no more — not the presented JSON:

```
qrCodeId → [ Quote { issuedAt, expiresAt,
                     methods: { currency → exact amount | (min, max) } } ]
```

Two details that are easy to get wrong:

- **A set of quotes per QR Code, not one.** Each fetch may produce a different quote — the payer's
  app re-fetches, a QR Code at a till is scanned by several people before one pays. Keying by
  `qrCodeId` and overwriting means a payer who fetched at T₁ is judged against someone else's quote
  from T₂, which refuses a correct payment. Each quote expires on its own window, so the set stays
  small on its own.
- **Editable ranges need no quote at all.** `editable.range` is already persisted on the payment
  method, so the rule the payer saw is already the rule we check.

## Consequences

- **A payment is accepted when it matches a live quote**, and refused otherwise. One rule, one path.
- **We give up the self-correcting case.** A payer whose quote expired, but whose amount a fresh
  calculation would still produce, is now refused. See *Future configuration* — this is the thing the
  decision trades away, and it is worth naming rather than discovering later.
- **Losing the store refuses payments, and that is affordable** — but only because of where the veto
  sits. A refusal costs the payer a rescan, and nothing else, because **only pre-funds notifications
  can be refused** (ADR-0003): a post-commit notification is never vetoed, so a lost quote can never
  strand money that has already moved. In a design where the veto came later this would be
  unacceptable.
- **Quotes are short-lived and must expire on their own window**, so the store is bounded by fetch
  rate × quote lifetime rather than by history.
- **An in-process map fits the deployment model** (ADR-0009: one active system per deployment).
  Redis becomes worthwhile when quotes should survive a restart or a failover — for durability, not
  for speed.

### On encoding, if the store ever needs shrinking

Measure before optimising; a quote is a handful of fields with a lifetime of minutes. If it does need
shrinking:

- **Networks** can be indexed for free. `NetworkEnum` is closed and small, and `EnumMap` *is* an
  array indexed by ordinal — with readable output and no registry to maintain.
- **Currency cannot.** It is an open string by design (ISO 4217 or any digital-asset ticker), so a
  numbered registry would have to grow at runtime from an open, unauthenticated API — an unbounded
  store keyed by attacker-influenced input. **Canonicalise the string instead**, so every quote shares
  one instance: the same memory win, no registry, no ordering contract, and a heap dump still reads
  `USDC`.
- **Never persist an ordinal or an index.** `ordinal()` shifts when an enum is reordered and a
  hand-maintained registry can be rebuilt in a different order, so a stored index can silently come to
  mean a different rail. This store exists to stop someone paying the wrong amount; an encoding that
  can quietly mean the wrong thing trades away the exact property it is there to provide.
- **Do not hash for identity.** Collisions are not an acceptable failure mode for deciding whether an
  amount was quoted.

## Future configuration

A later option could mark some values as **derivable**, letting the service recompute them instead of
requiring a stored quote. The gain is availability: an expired quote whose recalculated amount comes
out the same would be accepted, so a payer holding a stale payload is not sent back to rescan for
nothing.

It is deliberately not built now. It only helps the currencies that do not need a store, it
reintroduces the second code path this ADR removed, and it is additive — nothing here has to change
to allow it later.

## Current state

**Not yet built.** The implementation recalculates, which is correct and sufficient while every
currency is 1:1 and every amount is derivable. This ADR records the decision the FX work inherits, so
the reasoning does not have to be reconstructed then.

## Alternatives rejected

**Recalculate everything.** Impossible for FX: the rate is not a function of anything we hold.

**Derive what is derivable, store the rest.** Keeps the self-correcting behaviour for adjustments,
but splits the acceptance rule by currency type — two paths, two failure modes, two sets of tests —
for a benefit that disappears the moment the store exists anyway. Available later as configuration.

**Store the whole presented JSON.** Far more than the question needs, and it makes the payload's
internal shape a stored contract. The acceptance decision needs amounts and windows, nothing else.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
