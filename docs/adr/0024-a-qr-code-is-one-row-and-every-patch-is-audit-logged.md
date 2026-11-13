# 24. A QR Code is one row, and every PATCH is audit-logged

Date: 2026-11-13

## Status

Proposed. Part of [0022](0022-rewrite-in-go-as-a-standalone-product.md).
Keeps [ADR-0016](0016-a-revision-is-a-version-of-the-request-not-of-its-status.md). Deliberately
**not** a temporal (versioned) model.

## Context

This service stores the JSON payload of a payment request, signs it on demand, and tracks what
happens to it. The Java build moves that request through about five representations, using 40
mappers.

**PATCH overwrites the request with no trace today.** For an artefact that asks for money, "what
did it ask for before, and when?" is an audit question, not a versioning one. A QR Code lives for
minutes or days, and nobody reads it "as of" an instant.

**PATCH in Java** (`PatchQRCodeUseCase.java`):

- **Allowed** only when the stored status is `ACTIVE` or `PAYMENT_INITIATED` (lines 55-57).
- **Six top-level tags can change:** `locationId`, `validUntil`, `unstructured`,
  `additionalInformation`, `bill` and `paymentMethods`.
- **What counts as changed:** a tag counts when it is **present**; `paymentMethods` counts when its
  value differs (lines 82-86).
- **A PATCH that changes nothing is refused.**

**Some data lives outside the request JSON, and must stay consistent with it:**

- **The location** is not bill content. It is a *binding* of a printed QR Code to a payload:
  - looked up by `/loc/{id}`;
  - unique;
  - on create, defaults to the QR Code's own id (`QRCodeEntity.java:185-187`);
  - re-pointable by PATCH;
  - taken over by a rebind.
- **The EMV string** is built from the location **and configuration**: the public host and the EMV
  settings, where tag 54 is a configured constant, `"0"` by default (`EmvProperties.java`,
  `MateraAdoptQRCodeEMVService.java:47-60`). It does **not** carry the bill amount. It is
  byte-compared on payload retrieval, so a printed code stays valid only while its stored string is
  unchanged.
- **`valid_until`** drives expiry and retention.

## Decision

**Priorities, in order:**

1. reading the current QR Code is one row;
2. the audit log is as small as possible;
3. rebuilding the past may be slow.

**Tables.** There are four, plus the event sequence and lookups:

| Table | Holds | Mutable? |
|---|---|---|
| `qr_code` | `id`; **`location_id`** (unique across all rows); `emv_content`; `valid_until`; `status_code`, `revision`, `lock_version`; the reservation (`reserved_until`, `reserved_by_payer_info`, `reserved_by_signer_subject`); payment details (`end_to_end_id`, `payment_network`); **`request_json`**; `created_at`, `revised_at` | conditional `UPDATE` only |
| `qr_code_audit_log` | PK `(qr_code_id, revision)`, where `revision` is the number **before** the increment; `changed_at`; `changed_fields`; `previous_values` (JSON holding only those tags, with their previous values) | append-only |
| `qr_code_notification` | `id` (UUIDv7, gives the order); `qr_code_id`; `received_at`; the columns transitions decide on (phase, network, currency, transaction id, payer info, signer subject); **`notification_json`** (the parsed notification exactly as accepted, so optional fields stay absent rather than becoming 0); the raw compact JWS when there is one | append-only |
| `payment_event`, `event_sequence`, `payment_event_legacy_cursor` | [0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md), [0026](0026-identifiers-and-the-event-cursor.md) | append-only |

**Column ownership.** Each column has exactly one owner:

- **`request_json`** holds the bill content the create and PATCH bodies carry. **It does not hold
  the location:** that is a column, and GET composes the response from the column and the JSON. It
  never holds lifecycle data either (status, reservation, payment details, notifications).
- **`valid_until`** is computed from `request_json` and written in the same statement by create and
  PATCH.
- **`location_id`** is written by create (requested value, or the QR Code's id when none is given,
  as Java does), by PATCH, and by a rebind. Nothing else writes it.
- **`emv_content`** is `domain.EMV(location_id, emvConfig)`. It is written **in the same statement
  as every `location_id` write**, and only then. No other write path recomputes it.
- **Lifecycle columns** are written only by the transitions of
  [0035](0035-one-event-builder-and-a-written-transition-map.md). **Every** transition also sets
  `revised_at`, as Java's `touch()` does. PATCH bumps `revision`; no other transition does
  (ADR-0016).

**PATCH,** one transaction, in this order:

1. **Read** the row: `lock_version` *v*, `revision` *n*, stored status.
2. **Decide in the domain:**
   - stored status `ACTIVE` or `PAYMENT_INITIATED`, otherwise 400 as in Java;
   - changed tags by Java's rule; none changed → 400;
   - if `locationId` changes, the location rule below.
3. **If a holder must release the location:** `UPDATE` the holder first, guarded by its
   `lock_version`, writing a fresh random `location_id` and its `emv_content`. The holder's revision
   does not move, as in Java. Its location change is recorded on its own row only, like any other
   lifecycle change.
4. **Update this row:** `UPDATE qr_code SET request_json, valid_until, location_id, emv_content,
   revision = n+1, lock_version = v+1, revised_at WHERE id = ? AND lock_version = v`. On 0 rows,
   re-run from step 1 ([0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md)).
5. **Insert the audit row:** `(id, n, now, changed tags, their previous values)`. A previous
   `locationId` is taken from the column.

The holder is released before this row takes the location, so the unique constraint never fires on
a correct path. A `23505` that still occurs (a concurrent rebind) is answered as Java answers a
refused rebind (400), after the re-runs.

**Location rule.** It is Java's, with one gap closed:

- **`/loc/{id}` resolves exactly one row:** `location_id` is unique across all rows.
- **A create or PATCH may take a location held by another QR Code** only when the creditor is the
  same **and** the holder is not live. "Not live" means stored status `PAID` or `CANCELLED`, **or
  `valid_until` in the past**.
- **The gap closed:** Java treated an expired-but-`ACTIVE` holder as releasable only because MongoDB
  had already deleted it. Rows are now retained ([0029](0029-time-is-applied-on-read-and-retention-is-the-only-job.md)),
  so the expiry is checked explicitly.

**The API always serves current values.** No endpoint reads the audit log. `GET ?revision=` keeps
today's contract: it matches the current revision only.

**One struct, end to end.** The type decoded from the HTTP body is the one validated, stored in
`request_json` (with the location moved into its column) and read back. Enums are typed strings
parsed at the boundary. PATCH presence is tracked on the same struct.

## Consequences

- Every PATCH's previous values are audited, in the smallest form.
- Reading the current QR Code is one row.
- A printed QR Code's EMV string changes only when its location changes. Migration copies it
  verbatim ([0030](0030-a-black-box-suite-defines-done-and-migration-writes-only-runtime-valid-rows.md)).
- Rebuilding the request at time T walks the log backwards. It is slow, and acceptable for an audit.

## Alternatives rejected

- **A temporal model.** Sized for long-lived data read as of an instant.
- **The whole previous request per PATCH.** It copies the creditor, which is not patchable, and
  doesn't say what changed.
- **Columns or key/value rows per field.** About 40 leaf fields and four lists.
- **Value-based change detection for every tag.** It needs a canonical JSON form, and it diverges
  from Java.
- **The location inside `request_json` as well as in a column.** Two owners of one value: a rebind
  would have to rewrite the holder's JSON, or leave it stale.
- **Recomputing `emv_content` on every write.** A configuration change would silently invalidate
  printed codes.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
