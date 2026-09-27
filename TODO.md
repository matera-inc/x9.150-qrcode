# TODO

Planned improvements, not yet implemented.

## Cache signer certificates on the verifier (payer) side

Today the payer/verify path (`QRCodeExternalJwkService` → `RestClientExternalJwkService`) fetches the
signer's certificate / JWK Set from the `jku`/`x5u` URL on **every** JWS verification — no caching.
The header already carries `x5t#S256` (SHA-256 thumbprint), which is the natural cache key.

Add a caching layer (decorator around the external JWK/cert gateway) so a repeated signer cert isn't
re-fetched every time:

- **Key by `x5t#S256`** (not by URL) so signer key rotation naturally misses and refreshes.
- **Re-check validity on every hit** — cached ≠ trusted forever; still enforce notBefore/notAfter,
  chain-to-trust-anchor, and revocation (cached only skips the HTTP fetch, never the trust checks).
- **Bounded + TTL** (e.g. Caffeine with max size + expiry); optionally honor the cert endpoint's
  HTTP `Cache-Control`.
- **Behind a config flag** (default on) so it can be disabled.
- Cover with a unit test (cache hit avoids a second `RestClient` call; rotated thumbprint refetches;
  expired cert is rejected).

The spec treats certificate caching as RECOMMENDED, not required, so this is an optimization — the
current behavior is already conformant, just an extra round-trip per verification.

## Publish a "payment expected" event on blockchain payment initiation

Today `PaymentNotificationQRCodeUseCase` only updates the entity and calls `qrCodeRepository.save(...)`
— it emits no outbound event. The blockchain `from` (payer) and `to` (payee) wallet addresses **are**
already captured and persisted (`BlockchainVO` → `QRCodeMongoDocumentMapper`), but nothing downstream
is told that an on-chain payment is now expected.

For crypto rails (Solana/Ethereum/Bitcoin/…), the service that watches the chain needs to know to
expect an inbound transfer. When a blockchain notification arrives with `action = PAYMENT_INITIATED`
(QR moves `ACTIVE → PAYMENT_INITIATED`), publish a **"payment expected"** message to a topic:

- **Payload:** `qrCodeId`, `network`, expected `amount`/`currency`, the `to` (payee) and `from`
  (payer) wallet addresses, and a correlation id. Enough for the chain-watcher to match an incoming
  on-chain tx to this QR Code.
- **Transport — Spring Cloud Stream (binder pattern), broker-agnostic.** Use Spring Cloud Stream so
  the same code supports **both Kafka and RabbitMQ Streams** with no code change. App logic stays
  plain functional beans (`Supplier`/`Function`/`Consumer<Payload>`); the target broker is selected
  purely by (a) the classpath binder — `spring-cloud-starter-stream-kafka` vs
  `spring-cloud-starter-stream-rabbit` — and (b) `spring.cloud.stream.bindings.<name>.destination`
  config. For RabbitMQ Streams, set `spring.cloud.stream.rabbit.bindings.<name>.producer.containerType:
  stream` to enable the Stream protocol.
  - **Do not hand-roll a broker abstraction** — the binder *is* that abstraction; a custom one just
    reimplements thousands of lines of infra code. (The dormant `spring.rabbit` block in
    `application.yml` predates this and would be superseded by Spring Cloud Stream binder config.)
  - Still expose it behind a port in the infrastructure layer so the domain/use-case stays
    transport-agnostic.
- **Reliability:** publish via a **transactional outbox** (write the event in the same Mongo save,
  relay to the broker after commit) so a payment-initiated notification is never lost or
  double-emitted on retry.
- **Return path:** the chain-watcher later feeds confirmation back as the existing `SENT`
  (with `transactionId`) or `NOT_SENT` blockchain notification — no new inbound API needed.
- **Behind a config flag** (default off until a broker is provisioned), covered by a test that a
  `PAYMENT_INITIATED` crypto notification enqueues exactly one message with the expected fields.

Scope note: this is our integration concern (feeding an external blockchain-settlement system), not
an ANSI X9.150 requirement — the standard defines the notification contract, not the internal fan-out.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>

## Prove a scanned payload survives networks we do not interpret

The issuer side and the payer side treat an unknown network **oppositely**, and only one of them is
currently tested.

As **issuer**, a network this deployment cannot interpret is refused at creation, by name
([ADR-0012](docs/adr/0012-refuse-what-this-deployment-cannot-honour.md)): a QR Code advertising a
rail we cannot validate a payment against is a promise we cannot keep.

As **payer-side transport**, the opposite must hold. When somebody else's QR Code is scanned and
decoded, a network we do not understand is **not an error**. We do not own that payload, we are not
validating a payment against it, and the software that asked us to decode it may understand that rail
perfectly well and pay it. X9.150 does not need to know the syntax and semantics of every payment
network in the world in order to carry one — §14.5 says as much by deferring each network's contents
to its own documentation.

So the payload must reach the caller **intact, including the parts we cannot read**, with the unknown
network opaque rather than stripped, rejected or "helpfully" normalised.

Add a test that:

- decodes a payload whose `paymentMethods[].networks` contains a rail this build does not interpret
  (Pix, Zelle, a chain with no published embedding)
- asserts the decode **succeeds**
- asserts the unknown network object is returned to the caller **byte-for-byte**, keys and values
  unchanged — losing a field here would silently strip the only thing the payer needed in order to pay
- asserts the rails we *do* interpret in the same payload are still parsed normally alongside it

Worth stating in `official-spec/INTERPRETATION.md` too, since "we refuse it" and "we pass it through"
appearing in the same codebase looks like an inconsistency until you notice which side of the
transaction each one is on.
