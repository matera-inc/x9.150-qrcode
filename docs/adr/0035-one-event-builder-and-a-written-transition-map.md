# 35. One event builder, and a written transition map

Date: 2026-11-13

## Status

Accepted 2026-11-13. Part of [0022](0022-rewrite-in-go-as-a-standalone-product.md).
Completes [0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md).

## Context

Events are the integration point that matters most. In Java, which transition emits what, and with
which body, is spread across `QRCodeEntity` (`pay`, `cancel`, `initiatePayment`, `reactivate`,
`emitForNotification`, `emit`). If two Go packages each built "their" events, they would disagree
on the body. One might take the amount from the payer's notification and another from the QR Code;
one might emit on a repeat announcement and another not.

Java's actual rules (`QRCodeEntity`, the payer-notification block, after Story 2.4):

- **The body** comes from the **last stored notification** if there is one. Otherwise it comes from
  the first payment method and the payment details. *(Refined 2026-11-15, owner: the latest stored
  notification that carries a `transactionId` comes first. See the amendment below.)*
- **A repeat announcement** by the holder refreshes the reservation and emits nothing.
- **A post-commit** never changes a closed QR Code, and reserves an `ACTIVE` one (ADR-0036).
- **Every event a payer's notification produces** carries `receivedInStatus` and, when the payer
  broke the protocol, `deviations` (ADR-0036; `schemaVersion` `1.1`).
- **`reactivate`** emits nothing.

## Decision

**One function builds every event: `domain.BuildEvent(type, row, lastNotification, now,
reason)`.** No other code constructs a `payment_event`. The body follows Java's rule above, field
for field. The event contract is unchanged except for ADR-0036's additive `deviations` and
`receivedInStatus` (`schemaVersion` `1.1`).

**The transition map is normative.** The suite pins every row:

| Trigger | Allowed from (status read) | Status after | Event |
|---|---|---|---|
| create | — | `ACTIVE` | none |
| PATCH | `ACTIVE` or `PAYMENT_INITIATED` (stored; `PatchQRCodeUseCase.java:55-57`) | unchanged | none (audit row, [0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)) |
| status-update `PAYMENT_INITIATED` | `ACTIVE` (effective) | `PAYMENT_INITIATED`, unattributed reservation | `payment.initiated` |
| status-update `PAID` | `ACTIVE` or `PAYMENT_INITIATED` (stored, ADR-0020) | `PAID` | `payment.cleared` |
| status-update `CANCELLED` | `ACTIVE` or `PAYMENT_INITIATED` (stored) | `CANCELLED` | `payment.cancelled` |
| status-update `ACTIVE` (reactivate) | `PAYMENT_INITIATED` (effective) | `ACTIVE` | **none** |
| status-update to the status it already has (stored and effective) | any | unchanged: 200, nothing written, no revision ([0020](0020-the-payee-states-the-outcome-of-its-own-receivable.md): a retry) | **none** |
| pre-commit, any rail (no `transactionId`), new holder | `ACTIVE` (effective; a lapsed reservation counts) | `PAYMENT_INITIATED`, held by the sender | `payment.initiated` (also on a takeover of a lapsed reservation) |
| pre-commit, any rail, same holder (retry) | `PAYMENT_INITIATED` (effective), held by the sender | unchanged, window refreshed (never restarted once a hash is registered) | **none** |
| pre-commit, any rail, once a hash is registered (holder included) | `PAYMENT_INITIATED` (effective), hash registered | refused, 409 | none |
| pre-commit, any rail, same holder, **refused** by the gate | `PAYMENT_INITIATED` (effective), held by the sender | `ACTIVE`, reservation cleared, in the same transaction (400 to the payer) | blockchain: `payment.failed`; bank rail: none |
| pre-commit, any rail, another payer | `PAYMENT_INITIATED` (effective), held by someone else | refused, 409 | none |
| pre-commit, any rail | `PAID` or `CANCELLED` | refused, 400 | none |
| post-commit, bank rail (`transactionId` present) | `ACTIVE` (effective) | `PAYMENT_INITIATED`, held by the sender, window stopped | `payment.initiated`, flagged `RECEIVED_IN_WRONG_STATUS` |
| post-commit, blockchain | `ACTIVE` (effective) | `PAYMENT_INITIATED`, held by the sender, window stopped | `payment.initiated` then `payment.sent`, both flagged `RECEIVED_IN_WRONG_STATUS` |
| post-commit, any rail | `PAYMENT_INITIATED`, held by the sender | unchanged, window stopped | blockchain: `payment.sent`; bank rail: none |
| post-commit, any rail | `PAYMENT_INITIATED`, held by someone else | unchanged, the holder keeps it, window stopped | blockchain: `payment.sent`, flagged; bank rail: none |
| post-commit, any rail | `PAID` or `CANCELLED` | unchanged (never reopens) | blockchain: `payment.sent`, flagged; bank rail: none |
| blockchain `PAYMENT_INITIATED` **with** a hash | any | as the post-commit row for that status | as that row, plus `ACTION_CONTRADICTS_HASH`; a warning names the payer |
| blockchain `SENT` without a hash | any | refused, 400 | none |
| blockchain `NOT_SENT` (holder) | `PAYMENT_INITIATED` (effective), or a lapsed reservation of the sender's | `ACTIVE` | `payment.failed` |
| blockchain `NOT_SENT` (holder), once a hash is registered | `PAYMENT_INITIATED` (effective), hash registered | unchanged; `ACTION_CONTRADICTS_HASH` in the warning | none |
| blockchain `NOT_SENT` **with** a `transactionId` | any | as the post-commit row for that status | as that row, plus `ACTION_CONTRADICTS_HASH` |
| blockchain `NOT_SENT` | held by someone else: 409; `ACTIVE` with no reservation of the sender's, `PAID`, `CANCELLED`: 400 | unchanged | none |
| location released by a rebind | not live ([0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)) | unchanged, new random location | none |
| reservation lapse | — (on read only) | reads as `ACTIVE`; never once a hash is registered, nor when `x9.reservation.ttl-seconds` is `0` (ADR-0036) | **none** (ADR-0019) |

Phase 1 verifies the table against the Java code and the suite. Any row Java contradicts is
corrected here before Go is built.

**Notification storage.** Each accepted notification is appended to `qr_code_notification` **before**
the event is built, in the same transaction. "Last stored notification" means the newest row for the
QR Code by its UUIDv7 `id`, read **after** that insert, so `payment.sent` carries the hash it
reports (as Java assigns the notification before emitting, `QRCodeEntity.java:645-650`). Optional
fields come from `notification_json`, so an absent tip stays absent.

**In Go (Story 9.3)** the builder is `domain.BuildEvent(type, row, lastNotification, now, cause)`
in `internal/domain`. `cause` carries the `reason` and, for an event a payer's notification
produced, its receipt (`receivedInStatus` and `deviations`). The receipt is passed in, not read
from the last stored notification, because the two can differ: the `payment.failed` of a refused
retry takes its body from the last *stored* notification (the accepted first pre-commit, received
in `ACTIVE`) but was received in `PAYMENT_INITIATED`, and an event the status-update API produces
carries no receipt even when a notification is stored. Each trigger of the map is one function in
the same package, and a table test there has a case for every key below and every cell of
ADR-0036's matrix.

**Amended 2026-11-15 (owner): which stored notification the body comes from.** Since ADR-0036 a
`NOT_SENT` that arrives once a hash is registered is **stored** (flagged `ACTION_CONTRADICTS_HASH`)
but carries no `transactionId`. Under "the last stored notification" the next event (a
`payment.cleared` or `payment.cancelled` from the status-update API) would then lose the hash the
payer reported earlier, and the payment system would receive a closing event with no reference to
match. So the body comes from, in this order:

1. the **latest stored notification that carries a `transactionId`**;
2. else the **last stored notification**;
3. else the **first payment method** and the payment details.

On every path that does not involve a contradictory notification this picks the same notification
as before, because a post-commit is the last one stored until something without a hash follows it.
It is also what Java sends: Java does not record a contradictory `NOT_SENT` at all, so its "last
notification" is still the post-commit. In Go the choice is `domain.EventBodySource`, a pure
function over every stored notification in arrival order; the store reads them all and SQL does not
choose.

**Same holder** means `payer.info` present on both and equal, and the same certificate subject;
without `payer.info` the sender is never the holder (ADR-0036).

**Every transition sets `revised_at`;** only PATCH moves `revision` ([0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)). The `request_json` never holds
notification data ([0024](0024-a-qr-code-is-one-row-and-every-patch-is-audit-logged.md)).

## Consequences

- Every package emits through one door, so the bodies cannot drift.
- `payment.cleared` after a Solana `SENT` carries the payer's amount, tip and hash, exactly as in
  Java. Consumers already reconcile on that (ADR-0014).
- Adding a transition means adding a row here and a test in the suite.

## Alternatives rejected

- **Each package builds its own events.** Two owners of one shape.
- **Bodies always from the QR Code row.** It changes what consumers receive today.
- **Emitting on a lapse or a reactivate.** Contrary to ADR-0019 and to today's stream.

## Verification against Java (Story 4.1, 2026-11-14)

This section records the tests; it does not change the decision above. Each row of the map has a
key, and one black-box test per key in
[`others/blackbox/tests/test_transition_map.py`](../../others/blackbox/tests/test_transition_map.py)
(`test_map_NNN_...`). Each test asserts the resulting status, every event for the QR Code exactly
and in order, what did not change, and that each event body comes from the last stored
notification (since 2026-11-15, the latest one carrying a `transactionId` first), else the first
payment method. Where ADR-0036's matrix covers more cells than the row,
the extra cells are cases of the same test. `test_every_map_row_has_a_test` fails if a key here has
no test, or a test no key.

| Key | Row | Tests | Java |
|---|---|---|---|
| X9-MAP-001 | create | `test_map_001_create` | agrees |
| X9-MAP-002 | PATCH | `test_map_002_patch` (from ACTIVE, PAYMENT_INITIATED; refused from PAID, CANCELLED) | agrees |
| X9-MAP-010 | status-update `PAYMENT_INITIATED` | `test_map_010_status_update_payment_initiated`, `..._on_a_lapsed_reservation` | agrees (after the amendment and BUG-002, see below) |
| X9-MAP-011 | status-update `PAID` | `test_map_011_status_update_paid` | agrees (after the amendment, see below) |
| X9-MAP-012 | status-update `CANCELLED` | `test_map_012_status_update_cancelled` | agrees (after the amendment, see below) |
| X9-MAP-013 | status-update `ACTIVE` (reactivate) | `test_map_013_status_update_active_reactivates`, `..._on_a_lapsed_reservation_is_refused` | agrees (after the amendment, see below) |
| X9-MAP-020 | pre-commit, new holder | `test_map_020_pre_commit_new_holder` (Solana, FedNow, RTP, ACH), `..._takes_over_a_lapsed_reservation` | agrees |
| X9-MAP-021 | pre-commit, same holder (retry) | `test_map_021_pre_commit_retry_by_the_holder`, `test_map_021_a_retry_refreshes_the_window` | agrees |
| X9-MAP-022 | pre-commit once a hash is registered | `test_map_022_pre_commit_once_a_hash_is_registered` | agrees |
| X9-MAP-023 | pre-commit, same holder, refused by the gate | `test_map_023_pre_commit_retry_refused_by_the_gate` | agrees |
| X9-MAP-024 | pre-commit, another payer | `test_map_024_pre_commit_from_another_payer` (other `payer.info`, other certificate, no `payer.info`, unattributed) | agrees |
| X9-MAP-025 | pre-commit on `PAID` or `CANCELLED` | `test_map_025_pre_commit_on_a_closed_qr_code` | agrees |
| X9-MAP-030 | post-commit, bank rail, on `ACTIVE` | `test_map_030_post_commit_bank_rail_on_active` (FedNow, RTP, ACH) | agrees |
| X9-MAP-031 | post-commit, blockchain, on `ACTIVE` | `test_map_031_post_commit_blockchain_on_active`, `..._on_a_lapsed_reservation_counts_as_active` | agrees |
| X9-MAP-032 | post-commit, held by the sender | `test_map_032_post_commit_by_the_holder` | agrees |
| X9-MAP-033 | post-commit, held by someone else | `test_map_033_post_commit_while_someone_else_holds_it` | agrees |
| X9-MAP-034 | post-commit on `PAID` or `CANCELLED` | `test_map_034_post_commit_on_a_closed_qr_code` | agrees |
| X9-MAP-040 | blockchain `PAYMENT_INITIATED` with a hash | `test_map_040_payment_initiated_with_a_hash` | agrees |
| X9-MAP-041 | blockchain `SENT` without a hash | `test_map_041_sent_without_a_hash` | agrees |
| X9-MAP-042 | blockchain `NOT_SENT` (holder) | `test_map_042_not_sent_by_the_holder`, `..._of_a_lapsed_reservation` | agrees |
| X9-MAP-043 | blockchain `NOT_SENT` (holder), once a hash is registered | `test_map_043_not_sent_once_a_hash_is_registered` | agrees |
| X9-MAP-044 | blockchain `NOT_SENT` with a `transactionId` | `test_map_044_not_sent_with_a_transaction_id` | agrees |
| X9-MAP-045 | blockchain `NOT_SENT`, refused | `test_map_045_not_sent_refused` | agrees |
| X9-MAP-050 | location released by a rebind | `test_map_050_location_released_by_a_rebind` | agrees |
| X9-MAP-051 | reservation lapse | `test_map_051_a_reservation_lapses_on_read_only`, `test_map_051_never_once_a_hash_is_registered` | agrees (`ttl-seconds: 0` is pinned by JUnit, not here) |

**Where Java contradicted the map** (S4.1-AC2), and how each was settled by the owner. A future
contradiction is a strict expected failure carrying its key, so it turns red the day Java and this
map agree.

- **X9-MAP-010, -011, -012, -013: a status-update to the status already stored.** ADR-0036's
  matrix answers 409 (`PAYMENT_INITIATED` on a held QR Code) or 400 (`PAID` on `PAID`, `CANCELLED`
  on `CANCELLED`, `ACTIVE` on `ACTIVE`). Java answers 200 and changes nothing, no event, as
  [ADR-0020](0020-the-payee-states-the-outcome-of-its-own-receivable.md) decided for retries
  (X9-LIFE-127). **Amended 2026-11-14 (owner):** the matrix was wrong; a same-status update is a
  retry, 200 and nothing changes. The map's last status-update row says so, ADR-0036's matrix is
  corrected, and the tests assert it.
- **X9-MAP-010: status-update `PAYMENT_INITIATED` on a lapsed reservation.** The row allows it from
  `ACTIVE` (effective), so it should reserve the QR Code again, unattributed, with
  `payment.initiated`. Java compares the *stored* status for its X-to-X no-op, answers 200, writes
  nothing and emits nothing: the QR Code still reads `ACTIVE` and the next payer takes it
  ([BUG-002](../../known-bugs.md)). **Fixed 2026-11-14:** the no-op now requires the stored and
  the effective status both to match, and the test asserts the row.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
