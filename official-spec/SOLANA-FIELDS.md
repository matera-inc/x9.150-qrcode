# Solana fields for X9.150

**Source:** *Solana Fields for X9.150*, as indicated by the **Solana Foundation** — Ilan, 1 September 2026.

This is **not** part of ANSI X9.150-2026. It is the embedding published by the network itself, which
is precisely what [ADR-0010](../docs/adr/0010-networks-are-interpreted-only-once-their-authority-publishes.md)
requires before this service will interpret a network:

> X9.150 specifies the style and the root of a payment method; **the inner JSON of each network
> object belongs to that network's owner.**

The standard defines *where* a network's object hangs (`$.paymentMethods[].networks.<name>`) and
nothing about what goes inside it. That inside is this document.

## The fields

| Field | M/O | Length | Format | What it is |
|---|---|---|---|---|
| `recipient` | **M** | 44 characters | Base58 | Wallet address of the recipient |
| `memo` | O | 100 characters | UTF-8 | Anything from the payload. The payload ID should be included, in the form `{QRCD:"payloadID"}` |

The object key is **`solana`**, lower-case, like every other key under `networks` (§14.5). The
network's name as a *value* — in a payment notification's `$.payment.network` — is **`Solana`**,
matching the form §2.4 uses for the bank rails. Those are two different fields; see
[INTERPRETATION.md](INTERPRETATION.md) I-1.

```json
"networks": {
  "solana": {
    "recipient": "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM",
    "memo": "{QRCD:\"01A0E3A12805CB382EF4687F18CDC43A\"}"
  }
}
```

## Why this document exists in this repository

Because we guessed, and the guess was wrong.

Before this embedding was published, this service modelled a blockchain payment method as a single
`walletAddress` field with **no memo**. That was an inference, and ADR-0010 was written precisely to
stop us shipping inferences as though they were the standard:

> A chain we modelled without such a publication would be our guess wearing X9.150's name, and would
> meet a different guess from the next implementer — the interoperability failure the standard exists
> to prevent.

The published fields are `recipient` and `memo`. Both halves of our guess were wrong: the wrong name
for the address, and a missing field that carries the thing that makes reconciliation possible. Had
those QR Codes gone out, every one of them would have been unreadable by a conformant payer.

Keeping the source here, next to the standard it extends, is what lets the next reader check our
implementation against the thing it claims to implement rather than against our memory of it.

## What we do with it

See [INTERPRETATION.md](INTERPRETATION.md) I-8 for the two judgement calls this document leaves open
— the exact address length, and who is responsible for putting the payload ID in the memo.

---

<sub>The field definitions above are reproduced as the interface contract this software implements,
attributed to their author. Copyright © 2026 Matera Systems, Inc. for the surrounding commentary;
licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at
the repository root.</sub>
