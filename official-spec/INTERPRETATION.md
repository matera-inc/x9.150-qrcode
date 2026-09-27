# How this implementation reads ANSI X9.150-2026

The standard is not distributed here (see [README.md](README.md) — buy it from ANSI). This file is
the other half of that: the places where the text left us a choice, and which way we went.

It exists so that somebody who has bought the standard, cloned this repo, and put the two side by
side can tell **our decisions from the standard's** without reading the code. Where we depart from a
literal reading, or pick one of two readings the text supports, it is written down here — with the
reason, so a future reader can disagree on the merits rather than guess at the intent.

Nothing here is normative. The standard is normative; this is one implementer's log.

> **Scope of quotation.** Short attributed fragments only. The standard is copyrighted by ASC X9 /
> ANSI and is neither reproduced nor paraphrased at length in this repository.

Architectural decisions that are *ours alone* — not readings of the standard — live in
[`docs/adr/`](../docs/adr/README.md). The two are cross-referenced where they touch.

---

## I-1 — Network names: we accept any casing, and emit the one the standard shows

**Where:** §2.4 (`$.payment.network`), §14.5 and Table 14 (`$.paymentMethods[].networks.*`)

**What the standard says.** Two different things, in two different places, and they do not use the
same casing:

| | Kind | Standard's form |
|---|---|---|
| `$.paymentMethods[].networks.*` | JSON **object key** | `fednow`, `rtp`, `ach` |
| `$.payment.network` | String **value** in a payment notification | `FedNow`, `RTP`, `ACH` |

These are not in conflict — they are separate fields and may legitimately differ. The conflict is
inside §2.4, which introduces its list as "exact, all-uppercase values" and then gives `FedNow` as
the first entry and `"FedNow"` as the example. `FedNow` is not all-uppercase. One of the two is
wrong and the text does not say which.

The object keys have their own quiet surprise: §14.5 uses lowercase for `fednow` but camelCase for
`americanExpress`, so "lowercase" is not the convention either — the keys are simply spelled out
one by one.

**What we do.**

- **On input, we accept any casing** for both the object key and the notification value. `FEDNOW`,
  `fedNow`, `FedNow` and `fednow` all resolve to the same rail.
- **On output, we emit exactly what the standard prints**: `fednow` / `rtp` / `ach` as object keys,
  and `FedNow` / `RTP` / `ACH` as the `$.payment.network` value — the literal forms, not the prose
  description of them.
- **The emitted string is configuration, not a constant** (`NetworksProperties.emittedKeys`), so a
  deployment can change what it puts on the wire without a code change.

**Why.** An ambiguity in a wire format becomes an interoperability failure the first time two
implementers resolve it differently, and here two readings are both defensible. Postel's rule is the
cheap insurance: being liberal on input costs us nothing and rescues every counterparty that read
"all-uppercase" literally, while being strict on output keeps us to the form the standard actually
demonstrates. Examples are what implementers copy; prose describing examples is what they skim.

Making the output string configurable is the same bet taken one step further. If the committee
errata resolves this the other way, or a large counterparty resolves it the other way first, we want
to follow within a deploy rather than within a release.

## I-2 — We interpret three networks, and refuse the rest by name

**Where:** §2.4, §14.5

**What the standard says.** §2.4 closes the set — the value "**SHALL** contain only one of the
following exact […] values" — and then, two lines later, opens it again: the notification structure
"is network-agnostic and **MAY** also carry a network not listed above for early adopters." A field
cannot be both closed by SHALL and open by MAY. §14.5 additionally *names* `zelle`, `visa`,
`mastercard`, `americanExpress` and `discover`, but defines no fields for any of them, deferring
each to that network's own documentation. They are absent from Table 14.

**What we do.** This build interprets **`fednow`, `rtp` and `ach` only**. Any other network — named
in §14.5 or not — is **rejected at creation, by name**, with a 400 that says which key was refused
and which are supported.

**Why.** We take the SHALL as the rule and the MAY as permission the *format* grants, not an
obligation the *implementation* carries: X9.150 can transport a network it does not define, and an
implementation may still decline to interpret one. A network we cannot interpret is one we cannot
validate a payment notification against — no routing number to check, no destination address to
match — so accepting it would mean issuing a QR Code we could never honour. Refusing at creation,
where the biller can still fix it, beats accepting a QR Code that fails at payment time in front of
a payer.

Rejecting **by name** rather than ignoring silently is the deliberate part. A dropped key looks
exactly like a working one from the caller's side, right up until nobody can pay.

The five card and P2P brands are *named* rather than *specified*, which is not enough to build
against. When their fields are defined — or when a deployment needs a network this standard never
mentions — the way in is configuration, not another branch in a switch: see
[ADR-0010](../docs/adr/0010-networks-are-interpreted-only-once-their-authority-publishes.md).

## I-3 — USD only, because that is what these three rails settle

**Where:** §2.3 (`$.payment.currency`), §13.5.2 (`$.bill.amountDue.currency`), §14.1 (`$.paymentMethods[].currency`)

**What the standard says.** The payload is currency-agnostic: it carries any ISO 4217 code, and
explicitly "**MAY** also carry non-ISO 4217 digital asset currencies (e.g., USDC, BTC) for early
adopters."

**What we do.** We keep the payload currency-agnostic — any code is carried verbatim, never
translated — but this deployment **accepts only `USD`** on creation
(`supported-currencies.json`). An empty allow list turns the check off entirely.

**Why.** Currency-agnostic is a statement about the *format*; what a deployment can settle is decided
by its rails. FedNow, RTP and ACH move dollars. A QR Code denominated in EUR or USDC would advertise
an amount no rail we support can actually pay — unpayable at creation, and discovered only by the
payer. The allow list is configuration for the same reason the network set is: adding a rail that
settles another currency is precisely what should make that currency acceptable, and that should be
a deployment decision, not a release.

This is separate from the peg-mixing rule (`pegged-currencies.json`), which asks whether the
currencies on one request may appear *together*. This one asks whether each is acceptable at all.

## I-4 — `$.paymet.network` is read as `$.payment.network`

**Where:** §2.4 heading

The section heading spells the path `$.paymet.network`. Every other reference in the document —
including §2.5 directly below it — uses `$.payment.*`. We read it as a typo for `$.payment.network`
and implement that. Recorded here only so nobody re-derives it from the heading.

---

## Reporting

Found a place where we read the standard differently than you do, or one we have not written down?
Open an issue. Divergence that is *documented* is an interoperability problem someone can solve;
divergence that is buried in a mapper is one they debug at three in the morning.
