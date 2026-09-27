# ADR-0010 — A network is interpreted only once its authority has published how it embeds in X9.150

- **Status:** Accepted
- **Date:** 2026-09-26
- **Context:** `supporting-payment-notifications`

## Context

### The standard's flexibility here is deliberate, and it is a strength

ANSI X9.150 does not attempt to specify, in one document, the format of every blockchain and payment
network in the world. That would require a single committee to hold expertise in every rail that
exists — and to revise the standard every time a new one appears. Instead it **specifies the style and
the root, and delegates the rest**:

| X9.150 defines | The network's owner defines |
|---|---|
| That a payment method carries a `networks` object | What the network is called inside it |
| The envelope: payload structure, signing, lifecycle, amounts, currency | **The inner JSON of that network's object** — its fields and their meaning |

**The inner JSON belongs to the network owner.** That is a division of authority, not a gap in the
text. Read as silence it looks like an omission to be filled in by whoever gets there first; read
correctly it is the mechanism that lets the standard outlive the rails it was written alongside.

The consequence for us is direct: the shape of any given network inside X9.150 is **not something an
implementer gets to invent**. It has to come from the party with authority over that rail.

- If **Pix** is to be an X9.150 payment method for Brazilians, **Banco Central do Brasil** is the body
  that should publish how Pix is embedded — the object name, the fields, their meaning.
- The same holds for every blockchain: the chain's own community or foundation says how it appears.

Anything we model without such a statement is a guess with our name on it. Two implementations
guessing independently produce two incompatible payloads for the same rail, which is exactly the
interoperability failure the standard exists to prevent.

## Decision

**A network is *interpreted* — given a typed object, validated, and documented — only once its
authority has published how it embeds in X9.150.** Until then it is accepted and stored **verbatim**
in the `networks` object's `additionalProperties`, and never interpreted.

**Solana is, at the time of writing, the only network that has manifested.** It has indicated how
X9.150 should be used with it, so it is the one blockchain we can model on authority rather than on
inference. That is why it is the reference rail and the one the blockchain flow tests use
(see the test consolidation accompanying this ADR).

The bar for promoting a network from `additionalProperties` to an interpreted object is therefore
simple and checkable: **point at the published embedding.** No publication, no promotion.

## A test of an unspecified rail is not a test

This is the decisive point, and it is worth stating on its own.

A test asserts that an implementation conforms to a specification. **Where no specification exists,
there is nothing to conform to** — so a test of that rail asserts only that our code matches our own
invention. We wrote both the expectation and the behaviour, so the assertion cannot fail for any
reason that would teach us something. It is a tautology with a green tick next to it.

Worse, it actively works against us. A passing test is a commitment: once a suite asserts our guessed
Ethereum shape, changing that shape to match a future published embedding registers as a
**regression**, and the guess acquires exactly the authority it never had. The test does not protect
the behaviour; it entrenches it.

Solana is different in kind, not degree. It has manifested, so a Solana test asserts conformance to
something external that we do not control and could genuinely fail against. That is what makes it
worth running.

So removing the Ethereum and Bitcoin flow fixtures is not a reduction in coverage. **It is the removal
of assertions that never had anything to assert**, and which would have made the eventual, correct
change look like a break.

## Consequences

- **`additionalProperties: true` on `Networks` is a feature, not a loose end.** It is the supported
  home for every rail whose authority has not yet spoken, and payloads round-trip through it
  unchanged — the behaviour already tested via the `Tron` case in the flow tests.
- **A rail can be used before it is modelled.** A PSP may put Pix, Zelle or any other network in
  `additionalProperties` today and be fully conformant; the paying PSP interprets it bilaterally. We
  simply do not claim to understand it.
- **The blockchains currently enumerated in `NetworkEnum` — Bitcoin, Ethereum, Polygon, Base, XRP,
  Arc — are modelled ahead of any published embedding.** They carry a single `walletAddress`, which is
  a reasonable inference and may well match what each chain eventually publishes, but it is an
  inference. See the open question below.
- **Tests concentrate on Solana**, the rail we can model on authority. Adding per-rail flow fixtures
  for chains that have not manifested would be testing our own guess.
- **Adding a rail stays cheap and is no longer a judgement call.** The question "should we interpret
  X?" reduces to "has X's authority published?", which anyone can answer without design debate.

## Amendment, 2026-09-26: the unpublished chains were removed, not marked provisional

As first accepted, this ADR left open whether `Bitcoin`, `Ethereum`, `Polygon`, `Base`, `XRP` and
`Arc` — all modelled ahead of any publication — should be **marked provisional** or **removed**. It
recommended marking them, on the grounds that they are useful, almost certainly right, and that
removing them would break adopters for a point of principle.

**Decided the other way: they are removed.** `NetworkEnum` now holds exactly `FedNow`, `RTP`, `ACH`
and `Solana`.

The recommendation underweighted its own argument. A typed object is a claim that *this is how the
rail embeds in X9.150* — and for six of the seven chains, that claim was ours to make and not ours to
make. Marking it provisional in a description does not stop an implementer building against it, nor
stop two implementations diverging; it only records that we knew. The interoperability hazard this
ADR is about is not reduced by a caveat.

**Nothing is lost in capability.** Those chains remain payable: a payment method on any of them is
carried verbatim in the networks object's `additionalProperties`, which is fully conformant and is
precisely the mechanism this ADR describes. What is removed is our unfounded claim to interpret them,
not the ability to use them. Each returns as a typed object the day its owner publishes — and that
addition is additive, per the API's own compatibility policy.

## Alternatives rejected

**Model every rail we can think of.** Maximises apparent coverage and minimises actual
interoperability: our guess at Pix would meet a Brazilian implementation's different guess, and the
two would not interoperate — with X9.150's name on the failure.

**Refuse any network we do not interpret.** Would make the standard's deliberate openness
unusable, and block every rail whose authority has not yet published — which today is nearly all of
them.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>

## Amendment, 2026-09-27: Solana published, and the guess had been wrong

Solana's embedding for X9.150 now exists — `recipient` (Base58, mandatory) and `memo` (UTF-8,
optional), published by the Solana Foundation and recorded in
[official-spec/SOLANA-FIELDS.md](../../official-spec/SOLANA-FIELDS.md). It is therefore interpreted
here, as a typed object in the OpenAPI contract, exactly as this ADR's bar requires: point at the
publication.

**The guess it replaced was wrong in both halves.** Before any publication, this repository modelled
a blockchain payment method as a lone `walletAddress` with **no memo**. The published fields are
`recipient` and `memo`. Every QR Code built on the guess would have been unreadable by a conformant
payer, and the missing field is the one that carries the marker making reconciliation possible.

That is worth recording plainly, because the earlier recommendation in this ADR was to keep the
unpublished chains and merely *mark* them provisional. Had we done that, this is what "provisional"
would have shipped as. The amendment of 2026-09-26 removed them instead, and this is the evidence
that removal was the right call rather than a fastidious one.

One further consequence, found by the tests that came with Solana: adding a rail to `NetworkEnum`
reopened a fall-through in `QRCodeEntityValidator`, because that dispatch was a switch *statement*
rather than an expression and so did not demand a case per rail. Solana notifications were accepted
without any of their validation running — the same class of hole that once let Base, XRP and Arc be
accepted and ignored. It is a switch expression now. **Exhaustiveness has to be enforced by the
compiler at every rail-dispatch point, not just the ones we remembered.**
