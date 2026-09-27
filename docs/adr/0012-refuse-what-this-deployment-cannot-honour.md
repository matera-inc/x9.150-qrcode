# ADR-0012 — Refuse at creation what this deployment cannot honour

- **Status:** Accepted
- **Date:** 2026-09-27
- **Context:** `fix/conformant-network-naming`
- **Supersedes:** the *carried verbatim* consequence of [ADR-0010](0010-networks-are-interpreted-only-once-their-authority-publishes.md)

## Context

ADR-0010 settled **when** a network may be interpreted: only once its authority has published how it
embeds in X9.150. It also decided what happens to every other network — *accepted and stored
verbatim* in the networks object's `additionalProperties`. That second half is what this ADR
reverses.

It was a reasonable reading. §2.4 of the standard closes the network set with a `SHALL` and then,
two lines later, reopens it: a notification "**MAY** also carry a network not listed above". Carrying
an uninterpreted network verbatim honours the `MAY` and keeps the service out of the way of rails it
has no opinion about.

What that misses is who is left holding the problem. A QR Code is a **promise to be payable**. When
the biller sends `networks: { "Pix": {...} }` and we store it without comment, we have not stayed
neutral — we have issued a QR Code advertising a rail nothing here can validate a payment against:
no routing number to check, no destination address to match, no acceptance gate that means anything.
The biller believes a rail is live. The payer discovers otherwise, at the till.

The same shape appears one field over. The payload format is currency-agnostic and carries any
code verbatim, which is the *format's* position and a correct one. But what a **deployment** can
settle is decided by its rails, and the rails interpreted here — FedNow, RTP, ACH — move USD. A QR
Code denominated in EUR or USDC advertises an amount no supported rail can pay. Same promise, same
breach, same person left discovering it last.

## Decision

**Refuse, at creation, anything this deployment cannot honour — by name.**

- **Networks.** Only `fednow`, `rtp` and `ach` are accepted. Any other key in the networks object is
  rejected with a 400 that names it, on create and on patch alike.
- **Currencies.** Only currencies in `supported-currencies.json` are accepted — `USD` by default.
  An empty list disables the check, for a deployment that settles by some other route.

Both lists are **configuration, not constants**. Adding a rail is what should make its network name
and its currency acceptable, and that should be a deployment decision rather than a release.

**By name** is the operative part. This mapper runs behind an ObjectMapper with
`FAIL_ON_UNKNOWN_PROPERTIES` disabled, so the alternative to refusing is not neutrality — it is
**silently dropping the key**. A dropped network looks exactly like a working one from the caller's
side, right up until nobody can pay.

## Consequences

- A biller sending Pix, Zelle, Tron or a card brand gets a 400 naming the network and listing what is
  supported, instead of a 201 and a QR Code that cannot be paid.
- A biller sending EUR or USDC gets the same treatment.
- The `MAY` of §2.4 is read as permission the **format** grants, not an obligation an implementation
  carries. X9.150 can transport a network it does not define; an implementation may still decline to
  interpret one. This reading is recorded in
  [official-spec/INTERPRETATION.md](../../official-spec/INTERPRETATION.md) (I-2, I-3).
- **`payment.sent` and `payment.failed` have no trigger in this build.** They report a transaction
  already committed on a public ledger — a distinction only a blockchain offers, because the payer
  can point at a txHash anyone can verify. No bank rail has an evidenced moment between "announced"
  and "settled". The events, the enum values and the two-phase dispatch stay in place, unemitted,
  until an interpreted chain returns.
- ADR-0010's rule is **unchanged and still governs**: a network becomes interpreted when its
  authority publishes, never because a caller sent it. What changes is only the fate of a network
  that has not.

## Amendment, 2026-09-27: the same refusal applies to spelling

Closing the set answered *which* networks and currencies. It left open *how they are written*, and
the same argument settles it.

Our own API now accepts exactly one spelling of each: the network object key (`fednow`, `rtp`, `ach`
— §14.5's normative paths, unambiguous) and the configured currency code (`USD`). Any other casing
is refused, naming both what was sent and what to send.

The reason is round-trip, not pedantry. We emit **one** form, so a caller who sends `FedNow` and
reads back `fednow` has a disagreement with us about a field they just set, and finds out somewhere
less forgiving than a 400. The published OpenAPI contract already declares the property as `fednow`,
so a generated client is correct by construction; only a hand-rolled one can get this wrong.

**Scope.** This governs the `networks` object keys and the currency code on our own create/patch API.
`$.payment.network` on an inbound notification is the opposite case and gets the opposite rule: it
arrives from a third-party payer whose implementation is not ours to correct, so it is matched
case-insensitively and echoed back verbatim. Both follow from one question — whose implementation is
it? See [official-spec/INTERPRETATION.md](../../official-spec/INTERPRETATION.md) I-1.

## Alternatives rejected

**Keep carrying unknown networks verbatim.** The status quo, and defensible as a reading of §2.4 —
but it moves the failure from creation, where the biller can still fix it, to payment, where the
payer cannot. A QR Code that cannot be honoured is worse than one that was never made.

**Accept but warn.** A 201 with a warning in the body. Nobody reads a warning in a 201; the biller's
integration test goes green and ships.

**Refuse silently — drop the key, return 201.** The worst option, and the one we would get by
default if we simply closed the schema. It is indistinguishable from success.

**Hardcode the lists.** Simpler, and wrong on the first interoperability call. Adding a rail is a
deployment's decision, and waiting for a release to make it is how an interoperability problem
becomes an outage.

**Validate networks but leave currency open.** Inconsistent: both are promises about what can be
paid, and a QR Code priced in a currency no rail settles is no more payable than one routed over a
network no rail carries.
