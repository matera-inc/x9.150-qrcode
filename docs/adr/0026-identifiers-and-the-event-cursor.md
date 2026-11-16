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
  - **location id:** the QR Code's own id when the create gives none, as Java does
    ([0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)); a fresh v4 when a rebind
    moves a holder to a new location;
  - **event id:** v7.
- **The QR Code id is exactly 32 hex characters, with no dashes, in requests and in answers,** as
  X9.150 defines it (it must fit an ACH field). Input is accepted in either case; output is upper
  case. A location id has the same form, since it is printed in the QR Code's URL. **This is the
  standard's id format, not a rule for every UUID:** `eventId` and `correlationId` keep the usual
  dashed form (`format: uuid` in the contract). *(Amended 2026-11-15, owner.)*
- **The cursor is `payment_event.seq`, as a decimal string** ([0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md)).
- **ULID cursors are translated, not reset.** *(Amended 2026-11-16, [0038](0038-no-data-migration-from-the-java-build-a-written-mapping-instead.md): no data is
  migrated, so the legacy-cursor table stays empty and every ULID resumes at the start, as the
  last item below says. The translation stays built.)*
  - Migrated events keep their Java cursor in a separate table,
    `payment_event_legacy_cursor(legacy_cursor PK, seq)`. Only migrated events have a row there, so
    no nullable unique column is needed.
  - An `after=` that parses as a ULID is translated to the `seq` of the matching row. A ULID that
    matches no row positions the cursor at the first migrated event whose legacy cursor is greater.
  - *(Added 2026-11-15, owner.)* **A ULID later than every migrated cursor resumes after the last
    migrated event,** and so does any ULID when nothing was migrated (the "last migrated event" is
    then position 0, the start). Every event the Go build writes comes after every migrated one, so
    a consumer whose cursor ran past the copied history (it read Java events written after the
    migration snapshot, say) gets every Go event and loses nothing; `eventId` dedupe absorbs any
    overlap. This is Story 10.1's reading, written down.
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
- **A v4 default location id, so a printed code would not reveal its creation time.** Rejected
  on 2026-11-15 (owner): the payload anyone can fetch from the code already carries `createdAt`,
  and a v7 id still has 74 random bits, so it cannot be guessed. Java's default (the QR Code's own
  id) stands.
- **Dashed QR Code ids on the wire, in or out.** X9.150 defines the 32-character form, and
  consumers already store it.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
