# Best practices for counterparties

Advice, not rules. Everything here is **optional** — a counterparty that ignores all of it still
interoperates, because none of it is required by ANSI X9.150-2026 and none of it is enforced. It is
written down because each item is something that cost somebody real time to discover, and because a
reference implementation should pass on what it learned rather than only what it decided.

Where a practice exists because the standard leaves a gap, that is said plainly. See
[INTERPRETATION.md](INTERPRETATION.md) for the readings this deployment makes, and the ADRs in
[`docs/adr`](../docs/adr) for decisions that are ours rather than the standard's.

---

## 1. Put something in `payer.info` — even something that identifies nobody

**This is the one that changes behaviour, so it is first.**

§3.1 makes `payer.info` free text: *"Any information related to the Payer."* The standard defines no
payer identifier at all, so when a payment notification arrives there is exactly one thing about the
sender that can be trusted: **the subject of the certificate that signed it**. That is bound by the
signature and chained to a trusted CA, and in practice it identifies the payer's **PSP** — the bank
— not the individual payer behind it.

That is enough to be safe, and coarser than it should be. Without anything else, every payer at one
PSP looks like the same party. With a value in `payer.info`, they stop looking alike.

**What to send:** an opaque identifier for the payer. The mechanical requirement is narrow — **the
same value on every notification about the same payment, and different for different payers** — and
everything this service does with it follows from that alone.

```json
"payer": { "info": "p_7f3a9c21e45b" }
```

**Whether to keep it stable for that payer across payments is yours to decide, and it is a real
trade.** A value that is stable over time is linkable: a payee can tie one payer's payments together
across months, and payees who compare notes can do so across merchants. A value that changes per
payment is not, and still satisfies everything above. Against that, stability is what makes a payer
recognisable to a biller at all, which is what loyalty, repeat-customer handling and dispute history
are built on — all of them legitimate, all of them impossible with a fresh identifier each time.

We are not going to tell you which to pick; it depends on what you are building and on what your
payers have been told. What we will say is that the choice should be made deliberately rather than
discovered, because both directions are hard to reverse once counterparties have built on them.

**It does not need to identify anybody, and preferably should not.** An opaque token that means
something only in your own systems is strictly better than an email address: it links notifications
from the same payer, which is what the state machine needs, while telling the payee nothing about
who that person is. The standard's own example is `JaneDoe@Gmail.com`; you are not obliged to follow
it, and we would rather you did not.

**Why it matters.** A reservation is held by whoever announced it, and only that party may release
it, re-announce it, or report on it (ADR-0021). With `payer.info` the "whoever" is a payer; without
it, the best we can do is a bank. **Two of your own customers then read as the same party**, and
either may refresh or release the other's reservation — the collision a reservation exists to
prevent, happening inside the one institution where two payers are most likely to share a signer.

What you get by sending it, concretely:

- **a retry is safe.** A repeated announcement from the same party answers `200` and refreshes the
  window. A request that timed out can simply be sent again.
- **a second payer is told apart from you**, and gets `409` rather than taking your reservation.

**Do not put in it:** anything you would mind a payee storing and logging, anything you would mind
appearing in a support ticket, or anything a payer did not consent to share. It is transported and
persisted.

**A note on length.** The standard allows 254 characters — **Table 4 — Payment Notification
Requirements**, row `3.1 Payer Info`, with §3.1 repeating it as *"SHALL be string with 254 maximum
characters"*. This deployment declared 140 and, it turned out, enforced nothing at all: a payment
notification arrives as a signed JWS, so the payload is parsed out of the token rather than bound by
the framework, and the declared limit never ran. A 255-character value was accepted. The bound is
now 254 and checked in the domain, where it actually executes.

Keep your identifier far shorter than the maximum anyway — an opaque token needs a dozen characters
or two.

**What we never trust it for.** `payer.info` is supplied by the sender and verified by nobody, so it
can only ever *narrow* an identity the certificate already established. It can never widen one, and
a notification whose certificate does not match is refused whatever it claims in `payer.info`.

## 2. Tell the payee when you are not going to pay

If you announced a payment and then do not make it — the custody provider refuses, funds are short,
the chain rejects it — send `blockchain.action = NOT_SENT`.

Announcing takes the QR Code out of circulation. Until something releases it the bill cannot be
paid by anybody, and a payer has no access to the payee's management API by design — so this
notification is the only door out.

It returns the QR Code to `ACTIVE`, publishes `payment.failed`, and lets the next payer take the
bill. Only the party holding the reservation may send it; anybody else gets `409`.

**You do not need it in order to retry.** Re-announcing is accepted from the party that already
announced, so a payment you are going to attempt again needs no release first. `NOT_SENT` is for
giving up: you are telling the payee the money is not coming, so the bill can go to somebody else
rather than waiting out the reservation window.

A failed payment after a successful announcement is ordinary, not exceptional. Treat the release as
part of the normal path, not as error handling.

## 3. Echo the `ETag`; never construct one

Read it from the `GET` response and send it back verbatim in `If-Match`.

It is opaque and its format is ours to change. In particular it covers the status as well as the
revision, which is deliberate: a precondition keyed on the revision alone would stop noticing a
payer starting to pay, which is the exact race conditional requests exist to close. A tag you built
yourself will look correct and protect nothing.

## 4. Assert what was applied, not what was answered

When you edit a payment request, check the resulting values — not the status code.

This is the lesson of several real defects here, in both directions: a `200` with nothing applied,
and a `400` that wrote anyway. A status code tells you what the server decided to say. Reading back
the amount tells you what it did.

The same applies to your own tests. A check that asserts something is *refused* passes happily when
the fixture was never built and the refusal came from an unrelated rule.

## 5. Deduplicate events by `eventId`, and persist the cursor last

The event stream is at-least-once. A consumer that crashes before storing its cursor re-reads
events, and `eventId` is stable across re-publishes precisely so you can drop the repeats.

Persist the events **before** the cursor. Cursor first means silent loss: the stream has moved on
and nothing records what was in the gap.

**Exactly one system should poll a deployment.** Two pollers each see everything, which gains no
isolation and only invites the belief that it provides some.

## 6. Pin an image digest, not `latest`

`latest` moves. Pin `git-<sha>` or the digest, and when you report behaviour that disagrees with
ours, quote the digest you are running — it is the only thing that settles which build you measured.

More than one afternoon has been lost to two clusters running different images while everyone
assumed they were the same.

## 7. `amount` in a notification is the total, tip included

The merchant's share is `amount - tipAmount` (§2.1 with §13.6.2). Compute the tip from the amount
you are actually paying, not from the bill's reference figure, and send the total.

A tip taken *out of* the merchant's share — sending the bill amount with a tip carved from it — is
refused, and should be: it leaves the merchant short by exactly the tip while the bill looks settled.

## 8. Send the spec's spelling; expect us to be lenient about yours

We emit the standard's lowercase rail names and ISO 4217 upper-case currency codes, and we refuse
anything else when **issuing** a QR Code.

When **receiving** a notification we read both case-insensitively, because refusing a real payment
over a capital letter helps nobody. Do not rely on that from other implementations — strict in what
you send is the half that travels.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0
(source-available; not open source) — see LICENSE.md at the repository root.</sub>
