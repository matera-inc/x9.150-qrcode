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

## I-1 — Network object keys are lower-case, and that is not the ambiguous part

**Where:** §14.5 and Table 14 (`$.paymentMethods[].networks.*`); §2.4 (`$.payment.network`)

**What the standard says.** Two fields get confused for one another, so it is worth separating them
before anything else:

| | Kind | Standard's form | Ambiguous? |
|---|---|---|---|
| `$.paymentMethods[].networks.*` | JSON **object key** | `fednow`, `rtp`, `ach` | **No.** §14.5 and Table 14 agree, throughout, and Annex A's example payload writes them lower-case too |
| `$.payment.network` | String **value**, in a payment notification | `FedNow`, `RTP`, `ACH` | **Yes.** §2.4 introduces its list as "exact, all-uppercase values" and then gives `FedNow`, which is not |

The object keys have one quiet surprise — §14.5 uses lower-case for `fednow` but camelCase for
`americanExpress`, so "lower-case" is not a *convention*; the keys are simply spelled out one by one.
But they are spelled out unambiguously, and that is what matters.

**So the casing ambiguity lives in exactly one field, and it is not one this document's other
sections are about.** `$.payment.network` is a payment-notification field. How we read it is settled
in the payment-notification work, not here.

**What we do with the object keys.** One spelling, used in both directions.

- **Outbound:** lower-case `fednow`/`rtp`/`ach`. No judgement call — §14.5 says so.
- **Inbound, on our own create/patch API:** the same spelling, exactly. `FedNow`, `FEDNOW` and
  `fedNow` are refused with a 400 naming both what was sent and what to send.
- **Currency codes, on our own API:** likewise exact. `usd` is refused in favour of `USD`.

**Why be strict on our own API?** Because we emit *one* form, and whatever we emit is what the caller
reads back. Accepting `FedNow` and returning `fednow` hands them a round-trip mismatch on a field
they just set, which they then discover somewhere less forgiving than our 400. The published OpenAPI
contract already declares the property as `fednow`, so a generated client is correct by construction;
only a hand-rolled one can get this wrong, and it should be told immediately rather than left to
drift.

**What we do with `$.payment.network`.** The opposite, and for the opposite reason.

That value reaches us only from **outside**: a payer's notification, a settlement system's status
update. Their implementations are not ours to correct, their reading of §2.4 may legitimately differ
from ours, and refusing a payment over the case of a string we can resolve unambiguously would be
indefensible — the payer has already moved, or is about to. So it is matched **case-insensitively**,
as is the currency on the same message.

The value is then stored and echoed back **verbatim**. A notification is a record of what somebody
claimed; normalising it would be rewriting their words, and the claim is what a dispute would later
turn on.

**The two rules are not in tension — they follow from the same question:** whose implementation is
it? Ours, and the contract binds it. Someone else's, and we meet them where they are.

## I-2 — We interpret three networks, and refuse the rest by name

**Where:** §2.4, §14.5

> **Update, 2026-11-14.** The set is now four: `fednow`, `rtp`, `ach` and **`solana`**, the last on
> the Solana Foundation's published embedding ([SOLANA-FIELDS.md](SOLANA-FIELDS.md), I-8), which is
> the bar [ADR-0010](../docs/adr/0010-networks-are-interpreted-only-once-their-authority-publishes.md)
> sets. The reading below is unchanged: anything else is refused at creation, by name
> ([ADR-0012](../docs/adr/0012-refuse-what-this-deployment-cannot-honour.md), amendment of 2026-11-14).

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

> **Update, 2026-11-14.** With Solana interpreted, the shipped allow list is `USD`, `USDC` and
> `FRNT`, all in one dollar peg group. And **no currency is ever implied**: every payment method and
> every notification must state its currency, and `supported-currencies.json` is an allow list, never
> a default. The reasoning below is unchanged.

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

## I-5 — `paymentMethods` is an array, whatever the examples show

**Where:** §14 and Table 14 (normative) vs Annex A.2 and A.3 (examples)

**What the standard says.** Both, unfortunately.

§14 is unambiguous: *"Array of Payment Methods objects. At least one entry **SHALL** exist in the
array."* Every normative JSON path agrees — `$.paymentMethods[].currency`,
`$.paymentMethods[].networks.fednow.routingNumber`, and 46 others, all carrying the `[]`.

The worked examples in Annex A do not. They write:

```json
"paymentMethods": {
  "currency": "USD",
  "validUntil": "2025-11-30T23:59:59Z",
  ...
}
```

— a bare object. No `[`, no `]`, in either example. An implementer who builds from the examples
rather than the tables produces a payload that fails against the normative paths, and whose single
payment method cannot be addressed as `$.paymentMethods[0]` at all.

The same example carries a second defect worth knowing about: one of them sets
`"currency": "840"`, the ISO 4217 *numeric* code for the dollar — two pages after §14.1 states that
the field *"**SHALL** adhere to ISO 4217 alphabetic currency codes (non-numeric)"* and that
*"Numeric codes are not supported in this standard."* The example contradicts the rule it
illustrates.

**What we do.** Follow the normative text: `paymentMethods` is a JSON array, always, even with a
single entry. The OpenAPI contract declares it as one, and a bare object is rejected by schema
validation before it reaches any business rule.

We mention the example defects rather than quietly working around them because an implementer who
copied them will send us an object and get a schema error that does not, on its face, explain why
the standard's own example does not work.

**Why an array at all, when this build settles only `USD`?** Because the array's purpose is one
entry per *currency* (§14.1), not per network — a single entry carries every rail that settles that
currency. So a USD-only deployment normally has exactly one entry, with up to three rails inside it.
The array earns its brackets when a second currency becomes payable.

### Correct examples

One currency, one rail — the minimum:

```json
"paymentMethods": [
  {
    "currency": "USD",
    "validUntil": "2030-12-31T23:59:59Z",
    "amount": 11845,
    "networks": {
      "ach": {
        "routingNumber": "051000017",
        "accountNumber": "9876543210",
        "protectionType": "tokenized"
      }
    }
  }
]
```

One currency, all three rails — the shape the Annex A example was reaching for, corrected:

```json
"paymentMethods": [
  {
    "currency": "USD",
    "validUntil": "2030-12-31T23:59:59Z",
    "amount": 11845,
    "networks": {
      "fednow": {
        "routingNumber": "121000358",
        "accountNumber": "12345678987654321",
        "protectionType": "tokenized"
      },
      "rtp": {
        "routingNumber": "026009593",
        "accountNumber": "ACME00112233445",
        "protectionType": "tokenized"
      },
      "ach": {
        "routingNumber": "051000017",
        "accountNumber": "9876543210",
        "protectionType": "tokenized"
      }
    }
  }
]
```

Note `tokenized` rather than the example's `plaintext`: this build implements the tokenized
protection approach only, and `protectionType` is mandatory (see the README). The rail keys are
lower-case, which is also how the standard's own Annex A example writes them — corroborating I-1.

Both blocks above were posted to a running instance while this section was written, and both created
a QR Code. That is not ceremony: the first draft of the second one did **not** work, and finding out
why produced I-6.

## I-6 — Bank account numbers are alphanumeric (a bug this document found)

**Where:** Table 2, *Account Number (Bank)*

Running the corrected Annex A example from I-5 against this service failed, on the RTP account number
`ACME00112233445` — a value the standard itself publishes. Our OpenAPI schema had
`pattern: ^\d{4,17}$`, digits only.

The standard is explicit, and we were wrong: an Account Number (Bank) *"**SHALL** be a string with a
minimum of 4 and a maximum of 17 characters **SHALL** consist only of digits (0–9) AND/OR letters
(A–Z, a–z)"*. Letters are allowed. The pattern is now `^[0-9A-Za-z]{4,17}$`.

This one is recorded not because the standard is ambiguous — it is not — but because of **how it was
found**. It had survived review, a full test suite and a release, because every fixture we had
written used a numeric account number. It surfaced the moment an example from the standard was
*executed* rather than read. Examples in a specification are test cases that nobody has run; running
them is cheap and finds things.

This was double-checked against the rail that is strictest about it rather than taken from X9.150
alone. Nacha's ACH Entry Detail Record defines **DFI Account Number as 17 positions, alphanumeric**,
left-justified and blank-filled — which is plainly where X9.150's maximum of 17 comes from, and it
permits letters. FedNow and RTP are more permissive still, carrying the account in ISO 20022
`Othr/Id` (up to 34 characters). So 4–17 alphanumeric is the correct intersection for the three rails
this build supports.

Most US consumer account numbers are in fact all digits, which is why a digits-only pattern looks
right and passes every fixture somebody writes from memory. The *field* is not digits-only, and a
payload is validated against the field.

Nacha also directs that spaces and special characters be omitted from the field, which is why the
pattern excludes `-`, `_` and space rather than tolerating them.

Routing numbers are unaffected: Table 2 requires exactly 9 digits there, which is what we enforce.

The full rationale, including what Nacha does and does not say about upper- versus lower-case and
what that means for anyone comparing account numbers, is in
[ACCOUNT-NUMBERS-US.md](ACCOUNT-NUMBERS-US.md) — written so that a reader who meets
`^[0-9A-Za-z]{4,17}$` in the code does not have to wonder whether the letters are a bug.

One known gap remains, deliberately. The `encrypted` protection approach yields base64url ciphertext,
which contains `-` and `_` and so would not satisfy this pattern. This build implements `tokenized`
only (see the README), so the question does not arise yet; it must be revisited if `encrypted` is
ever implemented.

## I-7 — `alg` is whatever the X9-approved suite allows, so both RSA and EC must verify

**Where:** §9 JWS header table (`alg`), Annex A examples

**What the standard says.** The header table requires `alg` to be *"a value from the X9-approved
suite (SD-34)"* — a referenced document, not an enumeration in the text. It names no algorithm. Its
own Annex A examples sign with **`ES256`**.

It also allows the signing chain to arrive three ways: *"jku, x5u or x5c"*, all three permitted.

**What we do.** Verify whatever the payer presents. `alg` is read from the JWS header and the
verifier is chosen from it — `RS256/384/512` and `PS256/384/512` against an RSA key, `ES256/384/512`
against an EC key. What this service *signs* with (`PS512`) is a local configuration choice and is
not imposed on anybody else.

**Why this is not a detail.** It was broken, and the break was invisible. On the `x5c` path the code
parsed the certificate's key as RSA unconditionally, so an EC payer was refused with *"The public
key of the X.509 certificate is not RSA"* — while a perfectly good ECDSA branch sat one method away,
unreachable. The refused payer was the **conformant** one: ES256 is what the standard's own examples
use, and x5c is one of the three chain mechanisms it permits.

Nothing caught it because every test signed by calling our own signing endpoint, with our own RSA
demo key. A round trip through one's own implementation cannot detect a disagreement with anybody
else's — which is the only kind of bug interoperability testing exists to find.

The fixtures under `x9-qrcode-infrastructure/src/test/resources/certificate/` now sign as a third
party with both key types, issued by a test CA. Their README explains the two PKIX subtleties that
make that possible.

**The same assumption ran the other way, and was worse.** Our *own* identity was parsed as RSA too,
so a deployment issued an EC certificate by X9 could not start — the application context failed with
the same "not RSA" message before it served anything. Verifying a counterparty's EC signature and
signing with an EC key of our own are separate code paths, and both assumed RSA.

Both now follow the key. A configured `alg` the key cannot produce fails at startup naming both
halves, rather than substituting an algorithm the operator did not choose or failing later at the
first signature.

## I-8 — Two judgement calls in Solana's published fields

**Where:** [SOLANA-FIELDS.md](SOLANA-FIELDS.md) — the Solana Foundation's embedding, not ANSI X9.150

The Foundation's table is short and mostly unambiguous. Two things it leaves to the implementer.

### The address length is a maximum, not a width

The table gives `recipient` as "44 characters, Base 58". Read literally that is an exact width, and
we do not read it that way.

Base58 is variable-length. A Solana address is a 32-byte public key, and 32 bytes encode to **43 or
44** Base58 characters depending on magnitude — roughly one address in twenty-nine falls in the
43-character case. Enforcing exactly 44 would reject those wallets outright: a conformance failure
against real addresses, in the name of a stricter reading of a table.

So the pattern is `^[1-9A-HJ-NP-Za-km-z]{32,44}$`. The floor of 32 admits the all-zero system
address. A test pins the 43-character case, because the rule is easy to "simplify" back to 44 by
someone who has only read the table.

### We carry the memo, we do not compose it

The table says `memo` may hold "anything from the payload", and that the payload ID **should** be
included as `{QRCD:"payloadID"}`. That marker is what lets an on-chain transfer be matched back to
the QR Code that asked for it, so a memo without it makes reconciliation a manual job.

It would therefore be tempting to inject the payload ID automatically. **We do not.** The memo is a
field the biller composed, with a 100-character budget they are spending; silently rewriting it — or
appending to it — would be a surprising thing for a transport to do, and would quietly overwrite a
value somebody chose for a reason we cannot see.

So the memo round-trips exactly as supplied, and the responsibility for including `{QRCD:...}` stays
with the party that knows what else belongs in those 100 characters. The field description in
`openapi.yaml` says so, which is where an integrator will actually read it.

This is a *should*, not a *shall*. If it were a *shall* the calculus would change: refusing a memo
without the marker would then be enforcing the publication rather than second-guessing the biller.

## I-9 — An unknown network is refused when we issue, and carried when we transport

**Where:** §14.5 (`$.paymentMethods[].networks.*`)

The payload nests like this, and only the innermost level is ever opaque:

```
paymentMethods[]          ← one entry per currency
  ├── currency            ← we define it
  ├── amount, validUntil  ← we define them
  └── networks
        └── <name>        ← we may know this rail, or we may not
```

A `paymentMethods` entry is fully specified by the standard, so an unexpected key **there** is a
defect rather than an extension: a stray `creditor` beside `amount` means the sender is confused, and
carrying it would legitimise the confusion. The contract declares those objects exhaustively and
nothing else in the payload is open.

Inside `networks` it is the other way round. §14.5 fixes only where a network object hangs and defers
its contents to "network documentation", so the inner JSON belongs to that network's owner. We may
know the rail — `fednow`, `rtp`, `ach`, `solana` — or we may never have heard of it.

**What we do with one we do not know depends on which side of the transaction we are on, and the two
answers are opposites.**

| Role | Unknown network | Why |
|---|---|---|
| **Issuer** — someone asks us to mint a QR Code | **Refused, by name** | A QR Code advertising a rail we cannot validate a payment against is a promise we cannot keep. The biller can still fix it; the payer, at the till, cannot. See I-2 and ADR-0012. |
| **Payer-side transport** — we decode someone else's QR Code | **Ignored, and carried intact** | We do not own that payload and are not validating a payment against it. The software that asked us to decode it may understand that rail perfectly well and pay it. |

That second row needs one word pinned down. **"Ignore" here means *do not interpret*, not *discard*.**
Dropping the network we cannot read would remove the only thing the payer needed in order to pay,
and it would do so silently — the caller would receive a payload that looked complete and was not.
So an unknown network arrives with its keys and values byte-for-byte unchanged, including shapes
nothing here anticipated: numbers, nested objects, arrays, a key spelled in somebody else's
convention.

The apparent inconsistency — refuse it here, carry it there — dissolves once you notice that a
promise and a message are different things. We refuse to *make* a promise we cannot keep. We decline
to *break* a message that was never ours.

**Where this is visible in the code:** `NetworksSimple` and `PatchNetworksSimple` are the only
`additionalProperties: true` objects in the payload contract, and `openapi.yaml` says so at the point
where it matters. `Solana` itself is closed — opacity is for rails we do *not* interpret; a rail we
do interpret, on a published embedding, should report an unknown field rather than carry it.

---

## I-10 — A tip is money on top of the bill, and we enforce that at the payee

**Where:** §2.1 (`$.payment.amount`), §2.2 (`$.payment.tipAmount`), §13.6 (`$.bill.tip`),
§13.6.2, A.10, §9 (Payment Notification)

**`$.payment.amount` is the total the payer transferred, tip included.** Three clauses interlock,
and it is worth being explicit about which one does what, because no single one of them settles it.

**§13.6.2 gives the arithmetic** — what "total" means, and that it reaches the notification:

> "The computed total (amount \+ tip) **SHALL** apply only to the payment instruction sent to the
> payment network and related payment notification; it **SHALL NOT** be re-encoded in the payload."

That establishes *total = amount + tip* and that the total belongs in the notification. It does
**not** say which field carries it.

**§2.1 names the field.** Of `$.payment.amount`:

> "Total amount sent for payment."

**§2.2 is where the opposite reading would have had to live, and does not.** `$.payment.tipAmount`
says only *"MAY be present if a tip was paid"* and then repeats the integer/minor-unit rules. If the
tip were meant to sit *outside* `amount` — to be added to it rather than reported out of it — this
is the clause that would say so, and it is silent.

Read together: the payer sends one figure, and that figure is the total; `tipAmount` reports how
much of it was gratuity. A payer settling a 1000 bill with a 200 tip sends `amount: 1200,
tipAmount: 200`, and the merchant's share is `amount - tipAmount` = 1000.

**§2.1 is close to sufficient on its own**, and the word carrying it is *total*. A total is a sum of
parts, and the part is named one clause later: `$.payment.tipAmount`. A field labelled "total amount
sent" sitting beside a field reporting the tip is a **total and a component of it**, not two figures
to be added. Had `amount` been the meal alone it would not be the total *sent* — it would be the
amount due, which the standard already has a name for elsewhere (`$.bill.amountDue`). §13.6.2 then
confirms the arithmetic explicitly, and §2.2 declines the chance to say otherwise.

This deployment previously compared the *total* against the bill, which failed in both directions at
once: it refused the payer who tipped correctly, and it accepted a payer who paid the tip out of the
merchant's share — marking the QR Code paid in full while the merchant was short by exactly the tip.
That was a conformance defect, not an interpretation.

**What is genuinely ours is who enforces the tip rules.** The standard addresses them to the payer:

> §13.6.1: "If false, the payment application **SHOULD NOT** allow the Payer to add a tip."
>
> A.10: "Payer-facing applications **SHOULD** validate any user-entered or preset-selected tip such
> that min ≤ tip ≤ max."

Both are `SHOULD`s aimed at somebody else's client. We enforce them again at the payee, and refuse a
notification that breaks either. A rule that lives only in the counterparty's software is not a rule
— before this, a tip on a bill that refused tipping was accepted, and so was a tip of any size at
all. Same reasoning as capping `unstructured` at the door rather than trusting the sender.

**Presets do not constrain the payer; the range does.** A.10 validates *"any user-entered or
preset-selected tip"* against `min ≤ tip ≤ max`, so a preset is a suggested button that is itself
checked against the range — never an independent whitelist. A bill publishing presets and no range
therefore accepts any positive tip, and a payer who types 13% where 10/15/20 were offered is making
a legitimate choice rather than an error.

**A tip offer must carry a range or presets**, which is stricter than §13.6.2 and §13.6.3 — both
make those `MAY`. `{"allowed": true}` alone is refused at creation: it renders as nothing in a
payer-facing application, so it is an offer only in name. The consequence of pairing that rule with
the one above is worth stating plainly: a bill publishing **presets and no range accepts a tip of any
size**, because presets do not bind. A biller who wants a ceiling must publish a range.

**The percentage is of what is being paid** — `amount - tipAmount`, the merchant's share — not of
the bill's reference figure. For a fixed bill the two are the same number. For an **editable** amount
they are not, and the difference matters: an invoice of 1000 settleable from 400 and offering 10%
would, on the bill's figure, let a payer settling 400 tip 100 — a quarter of what they were actually
paying. The payer computes their tip from the amount they chose, so that is the figure we check
against, and it is the same number the bill is judged on.

§13.6.2's note — *"Payer-facing applications MAY calculate the currency value of the tip by applying
the percentage to the Bill Amount Due Amount"* — is a `MAY` addressed to the payer's application, and
for an editable amount `amountDue` is explicitly a figure the payer is invited to override. The
standard does not settle this; we do, and this is where it is written down.

**A tip may miss its computed bound by one minor unit.** The bound is a percentage of an integer
amount, so it is a rounded product, and a payer computing the same percentage — possibly after
converting from another currency, rounding again, under no obligation to round the way we do — can
land one unit either side having done nothing wrong. One unit, not a proportion: the gap comes from
rounding, which does not grow with the amount, and a percentage tolerance would quietly widen the
range the biller published.

**Percentages are taken in the notified currency**, not the bill's own. Currencies on one QR Code share a dollar peg but not a scale, so a percentage of
USD cents is not a percentage of USDC micro-units. This mirrors how adjustments are pro-rated
(see `PaymentNotificationAcceptancePolicy.adjustedAmountFor`).

**One schema deviation, deliberate.** §13.6.3 says `presets`, *when present*, holds 1–10 entries. We
do not declare `minItems: 1`, because the code generator initialises an absent array to an empty one
and Bean Validation then reads "at least one if you send it" as "you must always send it" — which
made `{"allowed": false}`, `{"allowed": true}` and the entire `range` form impossible to create. The
constraint was unreachable in the only direction that mattered and the reachable behaviour was
wrong, so the declaration went rather than the semantics.

## I-11 — Pre-commit is always used; post-commit carries the reference, and on blockchains it is the link

**Where:** Figure 1 step 10a ("Payment Notification"), §2.5 (`$.payment.transactionId`), A.6, A.7

**What the standard says.** The Payer's PSP sends a payment notification "IF requested by the
Payee's PSP"; the notification carries a "Payment Transaction ID". The standard has no phase marker
(the committee rejected one), and A.6 and A.7 show how the QR Code id travels inside the payment
itself on the bank rails: a `QRCD` creditor reference in the pacs.008, and `QRCD:[32-digit-id]` in
the ACH addenda record ("NACHA review and confirmation are pending").

**What we do.** We read the notification as **two phases**, told apart by the transaction reference
([ADR-0004](../docs/adr/0004-phase-inferred-from-transaction-id.md)):

- **Pre-commit** (no `transactionId`) asks *may I pay this amount?* It is judged by the payee's clock at
  receipt and, if accepted, reserves the QR Code. It is always used, on every rail: the reservation
  is what stops two payers from paying the same QR Code at the same time.
- **Post-commit** (with a `transactionId`) reports *I paid, here is the reference*. It is recorded and never
  judged again.

What links a payment to its QR Code depends on the rail. On FedNow / RTP and ACH the payment itself
carries `QRCD`, so the post-commit is a courtesy (and conveys the principal / tip split). On Solana the QR Code
id travels only in an optional memo that some custody providers' webhooks do not expose, and most
other chains have no memo at all, so **on blockchains the post-commit is the link**, and pre-commit / post-commit is the
mechanism for every chain. See [STATE-MACHINE.md](../STATE-MACHINE.md#pre-commit-and-post-commit).

**The phase comes from the evidence on every rail**, Solana included
([ADR-0036](../docs/adr/0036-pre-commit-on-every-rail-and-every-transaction-hash-is-recorded.md)):
FedNow and RTP have a pre-commit, an ACH Trace Number makes a post-commit, and `blockchain.action`
only corroborates (it decides `NOT_SENT` alone). The three deviations recorded here before Story 2.4
(no pre-commit on FedNow / RTP, ACH gating every notification, Solana keyed on the action) are
closed.

**The only payment X9.150 authorizes is the one that won the pre-commit.** While a payer holds the
reservation, another payer's pre-commit is refused with `409`; two pre-commits that arrive together
end with exactly one winner. A post-commit from another payer is not refused (the money has moved):
it is registered, flagged, and changes nothing, so the holder keeps the QR Code.

**A post-commit is never refused for status reasons.**

- On an `ACTIVE` QR Code (a payer that skipped the pre-commit, or whose reservation lapsed) it
  reserves the QR Code for that payer and publishes `payment.initiated`, then `payment.sent` on a
  chain.
- On a QR Code held by somebody else, `PAID` or `CANCELLED`, it changes nothing; on a chain its hash
  is still published as `payment.sent`.
- Once any hash is registered for a reserved QR Code, the reservation stops expiring and **no
  payer message changes its status**: the holder's retried pre-commit is refused with `409`, and
  its `NOT_SENT` is accepted, changes nothing and is flagged `ACTION_CONTRADICTS_HASH`. Only the
  payment system ends the reservation.
- A `NOT_SENT` carrying a `transactionId` is a post-commit (the evidence decides), flagged
  `ACTION_CONTRADICTS_HASH`.
- A notification that keeps losing a concurrent write answers `409` "busy" with the current
  status, never `500`.
- Every event a notification produces carries `receivedInStatus` (the QR Code's effective status
  when it arrived) and, when the payer broke the protocol, `deviations`:
  `RECEIVED_IN_WRONG_STATUS` (the QR Code was not `PAYMENT_INITIATED` held by this payer) and
  `ACTION_CONTRADICTS_HASH`. The event schema moved from `1.0` to `1.1`, additively.
- On a bank rail no `payment.sent` is published (the payment carries `QRCD`); the deviation is then
  in the `payment.initiated` event when there is one, and in the warning log otherwise.

**The holder, its retry, and the window** (Story 2.5):

- **The same payer** means both notifications carry `payer.info`, the values are equal, and the
  certificate subjects are equal. Without `payer.info` there is no telling two payers apart, so
  they are assumed to be different: such a payer may win a pre-commit, but cannot retry it (`409`),
  release it with `NOT_SENT` (`409`), or report its post-commit as the holder (it is registered
  with `RECEIVED_IN_WRONG_STATUS`). Sending `payer.info` is what lets a PSP's payers retry, release
  and report cleanly ([BEST-PRACTICES §1](BEST-PRACTICES.md)).
- **A retry is judged again** at the payee's clock, and **a refused retry releases** the
  reservation in the same step (on a chain with `payment.failed`). A refused pre-commit from a
  non-holder, or on an `ACTIVE` QR Code, changes nothing.
- **A takeover is evented:** winning the pre-commit on a lapsed reservation publishes
  `payment.initiated` for the new holder.
- **A registered hash stops the window**, whoever sent it; only the payment system then ends the
  reservation.
- **`x9.reservation.ttl-seconds: 0` means no window:** a reservation ends only by a refused retry,
  `NOT_SENT`, or the status-update API. The default stays `90`.

> ⚠️ **A `PAYMENT_INITIATED` carrying a transaction hash is bad behaviour by the payer:** it asks
> permission for money that has already moved. Either the payer skipped the question or it labelled
> its report wrongly. **Its hash is still kept**, as a post-commit, because funds that arrive in the
> right amount with a matching hash are enough for the payment system to mark the QR Code paid, and
> refusing the report would throw away the one piece of evidence linking those funds to this QR
> Code. It is recorded as received (its declared `action` included), flagged
> `ACTION_CONTRADICTS_HASH`, and logged as a warning naming the payer. Every hash is registered, a
> second one included, so the payment system can detect a duplicate payment. **This service never
> marks a QR Code `PAID`**: the payment system matches each hash against the funds and calls the
> status-update API.

## I-12 — The answer to a payment notification is signed, as the A.4.5 note models it

**Where:** A.4.5, the note under the Payment Notification example; §10.3.2 (`correlationId`)

**What the standard says.** The note asks that the JWS body of a payment notification response be
`{"statusCode": integer}`, so the payer holds a signed response (a JWS body cannot be empty), and
§10.3.2 has the responder echo the requester's `correlationId`.

**What we do.** We follow that model, and extend it to refusals:

- **A yes** (pre-commit or post-commit) is a compact JWS, `Content-Type: application/jose`, signed
  with this payee's X9 key, whose body is exactly `{"statusCode": 200}`. Its `correlationId` echoes
  the notification's, and its `iat` is this payee's clock at the decision.
- **A no** keeps its HTTP status and is signed too: the body is `{"statusCode": <status>}` plus the
  error fields a client already reads (`type`, `title`, `status`, `detail`, `violations`). The
  standard's note speaks of responses in general; signing refusals as well is **ours** (owner
  decision, 2026-11-13), so a payer holds the payee's no as evidence just as it holds a yes.
- **A notification that cannot be verified** (not a JWS, or a signature that does not verify) is
  refused, signed, under a **fresh** `correlationId`: one taken from an unverified header would be
  echoing a claim nobody checked.
- The header also carries `statusCode` and names it in `crit`, as our other responses do.

- **Every answer from the endpoint is signed**, including those the controller never writes: a
  request Spring refuses before reading it (an empty or unreadable body is a signed `400`, a wrong
  `Content-Type` a signed `415`) and an exception nothing maps (a signed `500` that says nothing
  about its cause). These go out under a fresh `correlationId`, except a `500` raised after the
  question verified, which echoes it. Only a failure to sign leaves an answer unsigned.

When this service sends a notification on a payer's behalf, it checks the payee's answer the same
way, against the payee's X9 certificate and the `correlationId` it sent, and reports `signature` as
`VERIFIED`, `ABSENT` (not a JWS at all) or `INVALID` (a JWS failing a check, including a signed
`statusCode` that differs from the HTTP status). When it is not `VERIFIED`, `signatureProblem` is a
fixed code from a closed list (`ABSENT_BODY`, `NOT_JWS`, `ALG_NONE`, `ENCRYPTED`, `BAD_HEADER`,
`CERTIFICATE_UNAVAILABLE`, `UNTRUSTED_CHAIN`, `BAD_SIGNATURE`, `EXPIRED`, `FUTURE_IAT`,
`CORRELATION_ID_MISMATCH`, `STATUS_MISSING`, `STATUS_MISMATCH`, `UNVERIFIABLE`; see the OpenAPI) and
`signatureDetail` says the same in words. It reports and never refuses: a 200 that is not verified
is an unsigned yes, and what that is worth is the payer's call.

**Time.** The standard defines `iat` and `ttl` but no clock skew. We refuse a JWS whose `iat + ttl`
has passed (`EXPIRED`), and also one whose `iat` is ahead of our clock by more than an allowed skew
(`FUTURE_IAT`; `x9.public-endpoints.jws-clock-skew-seconds`, 60 s by default). The second rule is
**ours** (owner decision, 2026-11-14): a JWS dated ahead stretches its own window past what its
signer's real clock allowed, so it could be held back and replayed later. One verifier checks
everything we receive, so the rule applies to the payee's answers and to the payer's questions
alike: our clock judges, as I-13 says.

**`typ` stays `JOSE`.** The answer is signed with `typ: JOSE`, like every other JWS this service
signs. The standard lists profile values (`payreq+jws`, `payresp+jws`, `paynote+jws`, `qr+jws`) but
none for the answer to a payment notification. Keeping `JOSE` here was decided (owner decision,
2026-11-14), and will be revisited together with all the `typ` values this service emits and
accepts, not for this answer alone.

**Why it matters.** The signed question (the payer's notification) plus the payee's signed answer,
linked by `correlationId`, is the evidence of what was authorized and when, checkable by anyone with
the public certificates. Settlement and reconciliation of the funds stay with the system that
receives them.

**History.** Until Story 2.3 this was a known deviation: an empty, unsigned 200 and plain JSON
refusals, and the outbound sender passed answers through unverified. See the 2026-11-14 amendment to
[ADR-0028](../docs/adr/0028-the-http-contract-is-frozen-and-equivalence-is-proved-by-a-differential-run.md).

## I-13 — The payee's clock decides, and the due date's last instant is on time

**Where:** §13.5.2 (adjustments: discounts before the due date, late fees after it), §11 and §14.2
(`validUntil` of the payload and of each payment method)

**What the standard says.** It defines a due date, discounts that apply before it and late fees
that apply after it, and a `validUntil` on the payload and on each payment method. It does not say
whose clock judges those instants, nor what is owed at exactly the due date.

**What we do.**

- **The payee's clock at receipt decides.** A pre-commit (an approval request) is judged at the
  instant it reaches the payee, by the payee's own clock: whether the QR Code and the payment
  method are still valid, and which amount is owed (discounted, face, or with a late fee).
- **The payer's check is advisory.** A payer may check `validUntil` and the quoted amount before
  sending, and should, but under clock skew it can be wrong in good faith: a payer whose clock runs
  behind can believe a discount or a `validUntil` still holds when, on the payee's clock, it has
  passed. The request is then refused (400 on `paymentNotification.data.payment.amount`, naming the
  amount carried and the amount owed, or 400 on `validUntil`), and the payer's remedy is to fetch
  the payload again and resend. Skew is the payer's risk; it never lets a payer lock in a discount
  or a validity the payee no longer grants.
- **`validUntil` is inclusive.** A request arriving at exactly `validUntil` is accepted; one
  millisecond later it is refused.
- **The due date is an instant, and a payment up to and including it is on time.** At exactly
  `dueDate` the full amount is owed, with no late fee. The first day's late fee applies from
  `dueDate + 1 ms`. (Before this, the formula threw at exactly `dueDate`, so that instant had no
  answer at all.)

A post-commit (money already moved, a `transactionId` present) is recorded and not judged again
against the clock; see I-11.

## I-14 — The payee honours what it signed: an adjusted amount's `validUntil` is its exact edge

**Where:** §13.5.3 (the adjustment in the payload: explanation, amount, `validUntil`)

**What the standard says.** Each adjustment in the payload carries a `validUntil`. The standard does
not say whether that is a calendar day, a rounded time, or the exact instant the adjustment's rule
changes.

**What we do.** The `validUntil` signed next to a discounted or late-fee amount is the exact instant
that amount stops applying, so that the instant advertised and the instant accepted are the same:

- **Discount:** E = `dueDate − daysBefore`. The discount is served, advertised and accepted up to
  and including E, and refused one millisecond after (the face amount is still accepted). Of
  several discounts, the largest eligible one is served and its `validUntil` is that discount's E.
- **Late fee:** k days late, with k = ceil((t − `dueDate`) / 24 h), the fee is k × per-day + fixed,
  and its `validUntil` is `dueDate + k × 24 h`. One millisecond later, one more day is owed.
- **Capped:** never later than the QR Code's `validUntil`, nor than the last of the payment methods
  served with it. Each method still carries its own `validUntil` beside its amount.

Before this, the value was rounded to `23:59:59` UTC of the day. A discount ending at E was
advertised until up to ~21 h later, and a pre-commit made inside that gap was refused while its
signed quote said it was valid.

---

## Reporting

Found a place where we read the standard differently than you do, or one we have not written down?
Open an issue. Divergence that is *documented* is an interoperability problem someone can solve;
divergence that is buried in a mapper is one they debug at three in the morning.
