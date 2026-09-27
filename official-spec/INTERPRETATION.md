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

## I-1 — The casing ambiguity is confined to one field, and it is one we only receive

**Where:** §2.4 (`$.payment.network`), §14.5 and Table 14 (`$.paymentMethods[].networks.*`)

**What the standard says.** Two different things in two different places — and, crucially, two
different *kinds* of thing:

| | Kind | Standard's form | Ambiguous? |
|---|---|---|---|
| `$.paymentMethods[].networks.*` | JSON **object key** | `fednow`, `rtp`, `ach` | **No.** §14.5 and Table 14 agree, throughout |
| `$.payment.network` | String **value**, in a payment notification | `FedNow`, `RTP`, `ACH` | **Yes.** §2.4 introduces its list as "exact, all-uppercase values" and then gives `FedNow`, which is not |

The object keys have their own quiet surprise — §14.5 uses lowercase for `fednow` but camelCase for
`americanExpress`, so "lowercase" is not a *convention*, the keys are simply spelled out one by one.
But they are spelled out unambiguously, and that is what matters.

So there is exactly one ambiguous field. And it is one **this build only ever receives**: a payment
notification arrives from a third-party payer. We do not send notifications, so we never have to
choose which half of §2.4 to obey.

**What we do.** Postel's rule at the boundary we do not control; one spelling inside it.

- **`$.payment.network`, inbound from a payer — case-insensitive.** The payer may have read either
  half of §2.4. Refusing a payment over the case of a string would be absurd, and we have no
  standing to insist: it is their implementation, not ours. The value is stored and echoed back
  **verbatim**, because the notification is a record of what the payer claimed, and normalising it
  would be rewriting their words.
- **`networks.*` object keys, outbound — lowercase.** No judgement call: §14.5 says so.
- **`networks.*` object keys, inbound on our own API — the same lowercase, exactly.** A create or
  patch request comes from inside this ecosystem, against a published OpenAPI contract that declares
  the property as `fednow`. A generated client sends lowercase already; a hand-rolled one that sends
  `FedNow` gets a 400 naming both spellings.
- **Currency codes, on our own API — likewise exact.** `usd` is refused in favour of `USD`. Same
  field, same reasoning.

**Why not be lenient on our own API too?** Because we would have to emit *something*, and whatever
we emit is what the caller reads back. Accepting `FedNow` and returning `fednow` hands them a
round-trip mismatch on a field they just set — and they find out somewhere less forgiving than our
400. Leniency is a courtesy to a party whose implementation you cannot change. Our own callers are
not that party; they have the contract.

**The emitted key is still configuration** (`x9.networks.emitted-keys`), and since it is also the key
we *accept*, changing it moves both halves together — a caller always sends what they will read back.
Given §14.5 is unambiguous, this is insurance rather than a resolution of anything: if a large
counterparty turns out to have implemented the object key differently, matching them is a deploy
rather than a release.

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

---

## Reporting

Found a place where we read the standard differently than you do, or one we have not written down?
Open an issue. Divergence that is *documented* is an interoperability problem someone can solve;
divergence that is buried in a mapper is one they debug at three in the morning.
