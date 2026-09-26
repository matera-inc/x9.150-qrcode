# ADR-0010 — A network is interpreted only once its authority has published how it embeds in X9.150

- **Status:** Accepted
- **Date:** 2026-09-26
- **Context:** `supporting-payment-notifications`

## Context

ANSI X9.150 defines the **envelope** — the payload structure, the signing, the lifecycle — but it is
deliberately **open about how an individual payment method is named and how its JSON is organised
inside `networks`**. The standard does not enumerate every rail on earth, and it does not dictate the
shape of a rail's own object.

That openness is the point: it lets rails be added without revising the standard. But it means the
shape of any given network inside X9.150 is **not something an implementer gets to invent**. It has to
come from the party with authority over that rail.

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

## Open question

Should `Bitcoin`, `Ethereum`, `Polygon`, `Base`, `XRP` and `Arc` be **marked provisional** in
`openapi.yaml` and `CLAUDE.md` until their embeddings are published — or left as they are, on the
grounds that a single `walletAddress` is the only plausible shape and matching it costs nothing?

**Recommendation: document them as provisional, do not remove them.** They are useful, almost
certainly right, and removing them would break adopters for a point of principle. But a reader should
be able to tell which rails rest on a publication and which rest on our reading — and, per ADR-0004,
this project has already been bitten once by the difference between what a standard says and what an
implementer assumes.

## Alternatives rejected

**Model every rail we can think of.** Maximises apparent coverage and minimises actual
interoperability: our guess at Pix would meet a Brazilian implementation's different guess, and the
two would not interoperate — with X9.150's name on the failure.

**Refuse any network we do not interpret.** Would make the standard's deliberate openness
unusable, and block every rail whose authority has not yet published — which today is nearly all of
them.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
