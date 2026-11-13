# 30. A black-box suite defines "done", and the cutover leaves only rows the runtime could have written

Date: 2026-11-13

## Status

Proposed. Part of [0022](0022-rewrite-in-go-as-a-standalone-product.md).

## Context

Most of the Java build's 293 test methods are Spring integration tests that cannot run against a Go
binary. What can run against any build is `others/blackbox/` (52 pytest tests),
`others/acceptance/acceptance.py`, `others/demo/two-instance-payment-cycle.sh`, and the fixtures,
including a test CA.

The service has one active instance, small state, and one programmatic downstream: the event
stream, whose consumers deduplicate by `eventId`. Java holds undrained events in an **outbox array
inside each QR document** for up to about a second before they reach `payment_events`.

## Decision

**Behaviour is pinned first.** The suite ([0034](0034-the-black-box-suite-is-python-in-this-repository-under-its-licence.md))
is extended until it covers every rule in STATE-MACHINE.md, ENDPOINTS.md, INTERPRETATION.md and
ADRs 0004–0021, and it must pass against **Java**. A rule without a test is a rule the rewrite may
lose.

**"Done" means all of the following:**

- the differential run of [0028](0028-the-http-contract-is-frozen-and-equivalence-is-proved-by-a-differential-run.md)
  shows no untolerated difference;
- the golden JWS and EMV corpora pass;
- `two-instance-payment-cycle.sh` completes in all four Java/Go pairings.

**`x9-migrate` produces only rows the runtime could have written.** It ships as a **separate image
tag** (`materainc/x9-qrcode:<version>-migrate`), so the service image carries no MongoDB driver. It:

- writes each document as one `qr_code` row:
  - its `revision` is kept;
  - `request_json` holds the request fields only;
  - `location_id` is copied; **`emv_content` is copied verbatim.** Printed codes must keep working.
    The tool also computes `domain.EMV(location_id, emvConfig)` with the converted configuration,
    and **refuses to run** if any recomputed string differs. A mismatch means the configuration
    was not converted faithfully ([0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md));
  - `valid_until` is computed from `request_json` as at runtime;
  - **no audit rows are written,** because Java kept no previous values, so the audit log starts
    empty and the first PATCH writes `(id, current revision)` cleanly;
- writes the last stored notification as one `qr_code_notification` row, with no JWS (Java never
  kept it);
- copies `payment_events` in `seq` order:
  - new `seq` values are assigned;
  - original `eventId`s are kept;
  - the Java cursor goes into `payment_event_legacy_cursor` ([0026](0026-identifiers-and-the-event-cursor.md));
  - events whose QR Code MongoDB already deleted are copied too (no foreign key, [0029](0029-time-is-applied-on-read-and-retention-is-the-only-job.md));
- **sets `event_sequence.last_seq` to the highest assigned `seq`;**
- **refuses to run** if any MongoDB document still has a non-empty `outbox`, or if the target schema
  holds any data. "Empty" means no rows in the data tables and `event_sequence.last_seq = 0`. The
  lookup rows and the sequence row seeded by Liquibase are expected;
- prints row counts and a per-QR checksum, and has a `--dry-run` mode.

**Cutover, per deployment:**

1. Announce the window.
2. Stop **inbound traffic** to Java, but leave the process running until its drain has emptied every
   outbox. `x9-migrate` checks this.
3. Stop Java.
4. Run Liquibase on the empty schema.
5. Run `x9-migrate`.
6. Start Go.
7. Consumers continue with their stored cursors (translated).

**In-flight work:**

- Reservations are migrated with their expiry instant, so they lapse on time.
- Outbound payer-side calls (ADR-0015) that were cut off are the caller's to retry, as the ADR
  already says.

**Rollback:** stop Go, then start Java against the untouched MongoDB.

- Rollback is safe **until Go has accepted its first write of any kind**: a create, PATCH,
  notification or status-update. Until then, Java's state is still the truth.
- After that first write, the path is **forward-fix only.** Codes created, edited or paid on Go
  would be missing on Java, and their printed QR Codes would answer 404.
- The cutover runbook checks this by comparing row counts and `revised_at` against the migration's
  output.

## Consequences

- The first deliverable is tests against Java. It feels backwards, and it is the only way to know
  what "the same" means.
- The Java integration tests are frozen with the code in `legacy/java/`, kept for history.
- The window is minutes, and consumers do nothing.

## Alternatives rejected

- **A strangler proxy, endpoint by endpoint.** Both builds would have to share one store with one
  model.
- **Dual-write.** Two sources of truth for a payment lock whose purpose is having one.
- **Writing a synthetic audit row per migrated code.** The first PATCH would then collide on the
  audit log's primary key, and the row would claim previous values that nobody recorded.
- **Reverse export for rollback.** Unbuilt machinery for a window that a "no Go events yet" rule
  already makes safe.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
