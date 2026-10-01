# 17. A tip is money on top of the bill, never a slice of it

Date: 2026-09-30

## Status

Accepted.

## Context

A payment notification carries `payment.amount` and, optionally, `payment.tipAmount`. Nothing
connected the two. `tipAmount` was validated as "greater than zero if present", stored, echoed in
the payload and forwarded outbound — and never once compared with the bill, with
`bill.tip.allowed`, or with `bill.tip.range`.

Meanwhile `PaymentNotificationAcceptancePolicy.validateAmount` compared `payment.amount` — the whole
figure — against what the bill asked for. Measured against `git-7029c1d` on a 1000 USDC bill:

| notification | what happened | what should happen |
|---|---|---|
| `amount: 1200, tipAmount: 200` | **refused** — *"Expected 1000 USDC but the notification carries 1200"* | accepted: 1000 to the merchant, 200 tip |
| `amount: 1000, tipAmount: 200` | **accepted**, QR Code paid in full | refused: the merchant received 800 |
| `amount: 1000, tipAmount: 999999` | **accepted** | refused: far outside any published range |
| tip on a bill with `allowed: false` | **accepted** | refused |

The second row is the one that costs money. The bill is satisfied on paper, the QR Code reaches its
terminal state, and the merchant is short by exactly the tip — with no signal anywhere that it
happened. A consumer totalling payments across the several QR Codes of one payment request would
count that bill as settled.

The standard settles it across three clauses. §2.1, of `$.payment.amount`:

> "Total amount sent for payment."

§13.6.2, which supplies what "total" means and that it reaches the notification:

> "The computed total (amount \+ tip) **SHALL** apply only to the payment instruction sent to the
> payment network and related payment notification; it **SHALL NOT** be re-encoded in the payload."

and A.10:

> "Payment applications SHOULD display the base amount and any available tipping options and compute
> the payable total (base \+ tip) at runtime."

and §2.2 — `$.payment.tipAmount`, *"MAY be present if a tip was paid"* — is where the opposite
reading would have had to be stated, and is silent.

So `amount` is the total transferred, tip included, and `tipAmount` reports how much of it was
gratuity. We were non-conformant.

The word carrying §2.1 is **total**. A total is a sum of parts, and the part is named one clause
later — a field labelled "total amount sent" beside a field reporting the tip is a total and a
component of it, not two figures to be added. Had `amount` been the meal alone it would not be the
total *sent*; it would be the amount due, for which the standard already has `$.bill.amountDue`.
See [I-10](../../official-spec/INTERPRETATION.md).

Separately, three of the four ways to express a tip **could not be created at all**. The code
generator initialises an absent array to an empty one, so `minItems: 1` on `bill.tip.presets` became
"presets are mandatory": `{"allowed": false}`, `{"allowed": true}` and every `range` form returned
400. The `TipRange` schema — a whole object, cited to §13.6.2 — was unreachable. The acceptance
suite never sent a tip in any form, so 43 checks passed throughout.

## Decision

**`payment.amount` is the total the payer transferred, tip included. The merchant's share is
`amount - tipAmount`, and that is the figure judged against the bill.**

A tip is accepted only when:

1. the bill offers one — `bill.tip.allowed` is true; and
2. it falls inside `bill.tip.range`, read as integer percentages of the merchant's expected amount
   **in the notified currency**; and
3. it is not larger than the transfer that carries it.

`bill.tip.presets` **do not bind.** A.10 validates a *preset-selected* tip against `min ≤ tip ≤ max`
as well, which makes the range the rule and the presets the suggested buttons. A bill with presets
and no range accepts any positive tip.

`minItems: 1` is dropped from `presets`. The constraint was unreachable in the only direction that
mattered, and what was reachable was wrong.

## Consequences

**We now enforce rules the standard addresses to the payer.** §13.6.1 and A.10 are `SHOULD`s aimed
at payer-facing applications. Enforcing them again at the payee is ours, not the standard's, and is
recorded as [I-10](../../official-spec/INTERPRETATION.md). A rule that lives only in the
counterparty's client is not a rule.

**A payer who previously tipped correctly was refused; they now succeed.** Any client built against
the old behaviour — one sending the bill amount with the tip carved out of it — will now be refused.
That is the intended consequence: it was silently underpaying merchants.

**Percentages are taken against the notified currency's amount**, not the bill's own figure, because
currencies on one QR Code share a dollar peg but not a scale.

**The per-payment split is already persisted.** Every notification records `amount` and `tipAmount`
separately, so a consumer can total merchant money and tip money per payment without further work
here. Totalling *across* the QR Codes of one payment request remains the consumer's job — x9.150 has
no grouping key ([ADR-0016](0016-a-revision-is-a-version-of-the-request-not-of-its-status.md)).

**Tips are still not a trigger for anything.** No event is emitted for a tip; it is part of the
payment that carries it.
