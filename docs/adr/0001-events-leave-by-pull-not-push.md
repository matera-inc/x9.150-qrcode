# ADR-0001 — Events leave X9.150 by pull, not push

- **Status:** Accepted
- **Date:** 2026-09-26
- **Context:** `supporting-payment-notifications`

## Context

X9.150 must publish payment facts (initiated, cleared, expired, failed, cancelled) that any system can
consume, while staying **fully independent**: it knows nothing about its adopters and shares no
database. The first adopter consumes with Kafka; others will not.

## Decision

**X9.150 never pushes.** State transitions append events to an embedded outbox in the QR document
(ADR-0002); a drain moves them to an append-only `payment_events` collection; consumers read them
through a cursor-paged, long-polling HTTP API (`GET /pub/api/v1/events?after=…`).

Consumers bridge to whatever they run. A reference bridge (pull → Redpanda) ships in `playground/`,
never as a module, build dependency or CI job of the service.

## Consequences

- The service runs on **MongoDB alone**. No broker to deploy, configure, secure or keep up.
- **No broker client, serialization framework or Schema Registry** in a payment service. The entire
  messaging configuration is a retention period and a poll ceiling.
- **N consumers**, each with its own cursor and pace, instead of one publisher and one topic.
- Adding a transport requires **no change to this codebase** — the community extension path is "write
  a consumer", not "write a plugin".
- CI needs no broker container.
- **Retention becomes ours.** A consumer offline longer than `x9.events.log.retention` (default P30D)
  misses events permanently; a broker would have absorbed that. Mitigated by a generous default, an
  `oldest-retained` gauge, and a documented resynchronize-from-QR-state recovery.
- The events endpoint publishes payment facts on the open `/pub` surface, which makes the project's
  "protect it at your edge" posture **load-bearing rather than advisory**.

## Alternatives rejected

**An in-process Kafka publisher.** Puts broker config and broker failure modes inside a payment
service, makes the service useless without a broker, and forces a single retention policy on every
consumer. Superseded `TODO.md`'s proposal of Spring Cloud Stream binders, whose whole value —
transport portability — is a problem we no longer have once we ship no transport.

**Direct MongoDB access for third parties.** Looks cheap, is not: it makes our internal document
schema a public contract (`outbox`, `payment_initiation`, field names), so persistence could never be
refactored; draining requires `$pull`, i.e. **write access to the QR collection**; a database grant
exposes everything in the database, not just events; and it needs DB credentials plus network reach to
the datastore. If someone insists, the only supported form is a read-only user scoped to a
`payment_events` view, documented as explicitly not a contract.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
