# 26. Identifiers are server-minted UUIDs, written the way the contract already writes them

Date: 2026-11-13

## Status

Accepted 2026-11-13. Part of [0022](0022-rewrite-in-go-as-a-standalone-product.md).

## Context

| Id | Today | Visible where |
|---|---|---|
| QR Code id | UUID, 32 hex characters, no dashes, on the wire | API paths; events (`qrCodeId`) |
| Location id | UUID, 32 hex | **inside the EMV QR string** (85-character tag-26 budget) |
| Event id | UUID v4 | events (`eventId`, the consumer's dedupe key) |
| Event cursor | monotonic ULID string | `nextCursor`, documented as opaque |

The create contract has no client-supplied id. Consumers store our ids as 32-character strings, and
some have persisted ULID cursors.

## Decision

- **All ids are `UUID` columns,** minted by the server with `github.com/google/uuid`, and bound as
  UUIDs, never as strings:
  - **QR Code id:** v7 (insert-ordered key);
  - **location id:** v4 (unguessable, printed in public, leaks no creation time);
  - **event id:** v7.
- **On the wire, ids keep the 32-hex form.** Input is accepted in either case, with or without
  dashes. Output is always 32 upper-case hex.
- **The cursor is `payment_event.seq`, as a decimal string** ([0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md)).
- **ULID cursors are translated, not reset.**
  - Migrated events keep their Java cursor in a separate table,
    `payment_event_legacy_cursor(legacy_cursor PK, seq)`. Only migrated events have a row there, so
    no nullable unique column is needed.
  - An `after=` that parses as a ULID is translated to the `seq` of the matching row. A ULID that
    matches no row positions the cursor at the first migrated event whose legacy cursor is greater.
  - Consumers do nothing at cutover ([0030](0030-a-black-box-suite-defines-done-and-migration-writes-only-runtime-valid-rows.md)).
- **No new idempotency mechanism during the rewrite.** An `Idempotency-Key` for create is a
  feature, not a port. It is deferred until after cutover, under the contract's own versioning
  policy.

## Consequences

- Consumers keep their stored cursors. `eventId` dedupe still covers any overlap.
- Events written after cutover carry v7 `eventId`s. Consumers already treat them as opaque strings.
- `ulid-creator` goes. The ULID parsing kept for translation is a few lines with no dependency.

## Alternatives rejected

- **Caller-supplied ids.** A breaking change for every integrator of an external standard.
- **A forced cursor reset at cutover.** It works, thanks to `eventId` dedupe, but every consumer
  must act. Translating is cheap.
- **v7 location ids.** They would expose the creation time in a public QR Code.
- **Dashed UUIDs on the wire.** They change a format consumers already store.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
