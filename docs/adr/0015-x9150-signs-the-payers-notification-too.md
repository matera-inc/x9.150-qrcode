# ADR-0015 — X9.150 signs the payer's notification, not only the payee's QR Code

- **Status:** Accepted
- **Date:** 2026-09-27
- **Context:** `feat/outbound-payment-notification`

## Context

Everything built so far serves the **payee**: mint a QR Code, publish a payload, receive
notifications about it. But the standard's flow has two sides, and the payer's side has work in it:

1. scan the QR Code, get the EMV string
2. ask X9.150 to decode it — signature verified, payload fetched
3. receive the payload JSON, untranslated
4. pick a currency and rail, and pay:
   - **4a** announce the payment *before* making it
   - **4b** if the payee agrees, pay exactly what was announced
   - **4c** report the payment, carrying its reference

Steps 1–3 already worked: `DecoveEmvUseCase` decodes, and `RestClientQRCodeExternalPayloadService`
fetches the payload over a signed request. Step 4 did not exist.

Left alone, every PSP integrating with X9.150 would build the same thing: compose a notification,
manage an X9 keystore, sign a JWS with the right `crit` parameters, get `x5t#S256` right, and post
`application/jose`. That is a lot of cryptographic detail to re-implement per integrator, and every
re-implementation is a chance to get it subtly wrong in a way only a counterparty discovers.

## Decision

**X9.150 exposes a plain REST API for the payer and does the signing.** Two endpoints, named for what
they announce rather than for the protocol's internals:

```
POST /api/v1/payment-notification/pre-payment
POST /api/v1/payment-notification/post-payment
```

The caller posts JSON — the notification, plus the endpoint the payee published in the payload it
fetched at step 3. This signs with the deployment's own X9 certificate and delivers. Keystores,
`crit` headers and thumbprints stay on this side of the line.

### The phase is explicit here, and inferred on the wire

A payee deduces the phase from whether a transaction reference is present, because the standard gives
it nothing else — the committee rejected a phase marker
([ADR-0004](0004-phase-inferred-from-transaction-id.md)). That inference is forced on the *payload*.
It is not forced on *this* API, and being explicit lets a mistake be caught before it reaches anybody
else.

The check that earns its keep: **a pre-payment carrying a `transactionId` is refused before it is
sent.** Such a message is not merely mislabelled — it arrives as a post-payment, the QR Code is never
reserved, and the payer proceeds to pay against something another payer can still claim. One
comparison here, against a double payment to reconcile later.

### The payee's verdict is returned untouched

A pre-payment is a request for permission, and the answer belongs to the payer. So a refusal comes
back as **HTTP 200 with `accepted: false`** and the payee's own words in `body`. That is not a
failure of ours: we reached them, they answered.

An **unreachable** payee is the opposite case and answers **502**. The two must not look alike — one
is a decision to act on, the other a delivery to attempt again — and a payer that cannot tell a
rejection from an outage will eventually treat one as the other.

### Retry belongs to the caller

Step 4c does not need its answer validated, only retried on failure. That retry is the **caller's**,
and the durable copy of the report lives with them.

This looked at first like a job for the outbox we already have. It is not: the failure being guarded
against is the *payee's* X9.150 being down, and if this deployment were down instead, the caller
could not reach us to enqueue anything either. The store has to be on the caller's side in both
cases, so an outbox here would add a mechanism that helps in neither.

### No allow list of destinations

The endpoint arrives inside a **digitally signed** payload, and the signature is the trust mechanism
— that is what the PKI is for. A list of permitted destinations would refuse payees this deployment
can perfectly well pay, which is the interoperability failure the standard exists to prevent, reached
from the other direction.

**Redirects are not followed**, for the same reason rather than a contrary one: a redirect leads
somewhere the signature never attested, so following it would spend the trust somewhere it was never
given. The call also has explicit connect and read timeouts, because a payer is waiting on this
answer before moving money and an unbounded wait is worse than a refusal.

## Consequences

- A PSP integrates with JSON and never touches JWS.
- This deployment now makes **outbound** calls to counterparty-supplied URLs. That is new, and the
  reasoning above is the whole of the defence: signature-attested destination, no redirects, bounded
  timeouts.
- FedNow and RTP have no meaningful pre-payment. They carry the QR Code id inside the ISO 20022
  message and reconcile from it, so a notification there is a courtesy requiring the End-to-End ID,
  which exists only once the payment is initiated. For those rails a payer goes straight to
  `post-payment`. ACH and Solana reserve the QR Code on a pre-payment.
- **This does not reverse [ADR-0001](0001-events-leave-by-pull-not-push.md).** That governs events
  leaving to the system *this* deployment serves, which stays pull-only. This is payer-X9.150 →
  payee-X9.150: a different direction, a different counterparty, a different contract.

## Alternatives rejected

**Let each PSP sign for itself.** The status quo. Every integrator re-implements JWS signing, X9
keystore handling and the `crit` rules, and each one is a fresh opportunity to produce a message a
counterparty cannot verify — discovered, as always, by the counterparty.

**Make post-payment asynchronous, with an outbox and retries here.** Rejected above: it helps in
neither failure mode, and it would turn a synchronous answer the caller can act on into a queued
promise they cannot.

**Interpret the payee's verdict and raise on refusal.** Would oblige every caller to unpick an
exception back into the status and reason they already had, and would blur the line between "they
said no" and "we could not ask".

**Allow-list the destinations.** See above — it converts a working payment into a refused one for
administrative reasons, which is precisely what an interoperability standard exists to stop.
