# 29. Time is applied on read, and retention is the only background job

Date: 2026-11-13

## Status

Accepted 2026-11-13. Part of [0022](0022-rewrite-in-go-as-a-standalone-product.md).
Keeps [ADR-0019](0019-a-reservation-expires-and-nothing-sweeps.md). Replaces MongoDB's TTL indexes.

## Context

MongoDB did two time-based jobs with no code:

- **It deleted a QR Code 30 seconds after `validUntil`.** That makes an expired code a 404,
  indistinguishable from one that never existed. The payee also loses the record of its own
  receivable, even though ADR-0020 lets the payee mark a code paid after expiry. And it is the only
  reason Java's location release worked for expired codes ([0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)).
- **It deleted events 30 days after `occurredAt`.**

PostgreSQL has no TTL indexes.

## Decision

- **Business time is applied on read,** as today:
  - reservation lapse (`effectiveStatus`, ADR-0019);
  - QR Code and payment-method expiry;
  - discount and late-fee windows.

  No status is ever written because time passed.
- **Where Java uses the stored status, so does Go.** `pay`, `cancel` and location release use the
  stored status, as Java does (ADR-0020). Every other status decision goes through
  `effectiveStatus`. Each transition says which one it uses, in the transition map
  ([0035](0035-one-event-builder-and-a-written-transition-map.md)).
- **Expiry is not deletion.** An expired QR Code stays readable, and the payload endpoint answers
  with its expiry reason (a tolerated difference, [0028](0028-the-http-contract-is-frozen-and-equivalence-is-proved-by-a-differential-run.md)).
  Its location is releasable ([0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)).
- **Retention is one goroutine with one `time.Timer`,** reset after each run. There is no schedule
  table: two tasks on one instance need none. Each run:
  1. **purges events:** deletes `payment_event` rows older than `events.retention` (default `P30D`);
  2. **purges QR Codes:** deletes `qr_code` rows whose `valid_until` **and** `revised_at` are both
     older than `qrcode.retention` (default `P400D`), in any status, together with their
     `qr_code_audit_log` and `qr_code_notification` rows.
- **Pacing:** each purge runs in batches of a few hundred rows, one transaction per batch, with a
  pause between batches. Cutoffs are computed in Go from the clock
  ([0033](0033-the-clock-can-be-moved-in-test-deployments-only.md)).
- **The service refuses to start** if `qrcode.retention` is shorter than `events.retention`.
  `payment_event.qr_code_id` carries **no foreign key**: events are a transport and may refer to
  codes that are already gone, including migrated ones ([0030](0030-a-black-box-suite-defines-done-and-migration-writes-only-runtime-valid-rows.md)).

## Consequences

- An expired QR Code no longer vanishes. "Expired" and "never existed" can be told apart.
- Storage grows with retention: roughly a few GB a year at the licensed ceiling. Operators who want
  less shorten `qrcode.retention`.
- The only sweep deletes. It never changes a status, so it cannot race a payment.

## Alternatives rejected

- **Delete 30 s after `validUntil`.** It reproduces a defect.
- **`pg_cron` or date partitions.** Database-side jobs, PostgreSQL-only, and unnecessary at this
  volume.
- **A schedule table claimed with `SKIP LOCKED`.** Built for many tasks across instances; here
  there are two tasks and one instance.
- **A sweeper writing `EXPIRED`.** Expiry is not a status.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
