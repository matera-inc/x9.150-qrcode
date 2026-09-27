# Bank account numbers in the US

**If you are reading the code, saw `^[0-9A-Za-z]{4,17}$` on `accountNumber`, and wondered whether
the letters are a mistake — they are not.** This file is why.

Almost every US consumer account number you will ever see is all digits, so a digits-only pattern
looks obviously right. It is not what either governing document says, and we had it wrong until an
example from the standard was executed rather than read (see
[INTERPRETATION.md](INTERPRETATION.md) I-6).

## What we implement

```
pattern:   ^[0-9A-Za-z]{4,17}$
minLength: 4
maxLength: 17
```

Digits and letters, either case. No spaces, no punctuation, no separators.

This satisfies both documents that constrain the field, which agree with each other.

## ANSI X9.150-2026, Table 2 — *Account Number (Bank)*

> **SHALL** be a string with a minimum of 4 and a maximum of 17 characters
>
> **SHALL** consist only of digits (0–9) AND/OR letters (A–Z, a–z)

Explicit on both counts: length, and both letter cases. §14.5.1.2, §14.5.2.2 and §14.5.3.2 (the
FedNow, RTP and ACH account numbers) each defer to this definition rather than restating it.

## Nacha — the ACH file format

The ACH rail is the one that actually binds the length. In the Nacha **Entry Detail Record**, the
*DFI Account Number* is **17 positions, alphanumeric**, left-justified and space-padded. An account
number longer than 17 is truncated to its leftmost 17; spaces and special characters are omitted.

That is plainly where X9.150's maximum of 17 comes from, and "alphanumeric" is why letters are
permitted rather than merely tolerated.

Nacha's file-level rules matter too: an ACH file is **fixed-width ASCII**, 94 characters per line,
where "an alphanumeric field must be left-justified and post-padded with spaces."

## The case question — the part worth being precise about

A reasonable worry, and the reason this section exists: **legacy mainframes are often unhappy with
lowercase**, and plenty of ACH integration guides state flatly that all alphabetic characters must be
uppercase.

Nacha's own developer guide is narrower than that. It says:

> Certain fields, like those that denote codes, must have uppercase characters only.

**Codes** — Service Class Code, Transaction Code, Standard Entry Class, and the like. It does not
impose uppercase on free-form alphanumeric data fields, and it says nothing case-related about the
DFI Account Number specifically. The blanket "everything must be ALLCAPS" appears in third-party
integration guides and ODFI onboarding documents, not in Nacha's format definition.

So there are three different statements in play, and it is worth not conflating them:

| Source | On case |
|---|---|
| ANSI X9.150-2026, Table 2 | Explicitly permits `A–Z` **and** `a–z` |
| Nacha, format definition | Uppercase required for **code** fields; silent on the account number |
| Many ODFIs and legacy originators, in practice | Often require or silently upper-case all alphabetic data |

**What we do, and why.** We accept both cases, because the standard we implement says both are
valid and refusing a conformant value is not ours to do.

**What we recommend.** Send uppercase. Nothing here will reject lowercase, but the further
downstream a value travels the more likely something upper-cases it, and a biller who sends
uppercase never has to find out which system did.

**What this implies for anyone building on this.** Do not treat case as distinguishing. If two
account numbers differ only in case, assume they are the same account: somewhere down the chain one
of them will have been folded. This service does not currently compare account numbers — bank-rail
payment notifications carry no destination account, so there is nothing to match — but any code that
starts doing so should compare case-insensitively.

## Why not just tighten it to digits?

Because the field is not digits-only, and a payload is validated against the field, not against what
is common. The standard's own Annex A example uses `ACME00112233445` for an RTP account — a value
our digits-only pattern rejected, which is how the bug was found. Narrowing a contract below what
the specification allows turns conformant senders away and produces exactly the interoperability
failure the standard exists to prevent.

## Known gap

The `encrypted` protection approach yields base64url ciphertext, which contains `-` and `_` and
would not satisfy this pattern. This build implements `tokenized` only (see the README), so the
question does not arise; it must be revisited if `encrypted` is ever implemented.

## Related

Routing numbers are unaffected and stricter: Table 2 requires exactly 9 digits, which is what we
enforce. FedNow and RTP are more permissive than ACH on the account side, carrying it in ISO 20022
`Othr/Id` (up to 34 characters), so ACH's 17 is the binding constraint across the three rails this
build supports.

## References

- ANSI X9.150-2026, Table 2 (*Account Number (Bank)*) and §14.5.1.2 / §14.5.2.2 / §14.5.3.2 — not
  distributed here; see [README.md](README.md)
- [Nacha — ACH Guide for Developers, ACH File Overview](https://achdevguide.nacha.org/ach-file-overview)
- [NACHA File Format Specifications — Entry Detail Record, DFI Account Number](https://dam.bancofcal.com/m/5ec324636794be06/original/UG32-NACHA-File-Format-Specifications.pdf)
- [NACHA ACH File Format Specifications](https://www.nicoletbank.com/nacha-file-format-specifications)

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
