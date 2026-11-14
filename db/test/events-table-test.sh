#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Story 6.5, S6.5-AC5: a payment system reads the event table with the event-reader role, as
# docs/EVENTS-TABLE.md tells it to.
#
#   1. As the application role, one fresh transition is committed the way ADR-0025 writes it: a
#      post-commit reaching an ACTIVE QR Code on Solana (ADR-0036), which reserves it and emits
#      payment.initiated then payment.sent, each through the event_sequence batch, with UUIDv7
#      event ids minted in seq order. A second transition (payment.cleared) follows.
#   2. As the event reader, worker 1 claims the pending rows (FOR UPDATE SKIP LOCKED) and holds
#      them; worker 2, claiming at the same time, gets none of them.
#   3. Worker 1 marks them DONE in the same transaction and commits.
#   4. The rows are DONE with read_at set, nothing of the transition is left pending, and their seq
#      order is their event_id order.
#
# Until the black-box suite exists (Epic 3), this runs inside a THROWAWAY postgres container
# where the changelog was just applied (db/ci/apply-and-check.sh does it):
#
#   docker exec <pg-container> bash /db/test/events-table-test.sh
#
# Names come from the environment, with the changelog's defaults: X9_DB X9_ROLE_APP X9_ROLE_EVENT_READER

set -uo pipefail

DB="${X9_DB:-postgres}"
APP="${X9_ROLE_APP:-x9_qrcode_app}"
ER="${X9_ROLE_EVENT_READER:-x9_qrcode_event_reader}"

pass=0
fail=0
ok()  { pass=$((pass + 1)); echo "PASS  $1"; }
bad() { fail=$((fail + 1)); echo "FAIL  $1"; [ -n "${2:-}" ] && echo "      $2"; }
equals() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected [$3], got [$2]"; fi; }
psql_as() { local role=$1; shift; psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$role" -d "$DB" "$@"; }

# UUIDv7: 48 bits of Unix milliseconds, version 7, a 12-bit counter (rand_a) so ids minted in the
# same millisecond still sort in minting order, variant 10, then random bits.
NOW_MS=$(date +%s%3N)
uuid7() {
  local ms=$1 counter=$2 hex
  hex=$(printf '%012x' "$ms")
  printf '%s-%s-7%03x-%s-%s' "${hex:0:8}" "${hex:8:4}" "$counter" \
    "$(printf '%04x' $(( (RANDOM & 0x3fff) | 0x8000 )))" "$(printf '%04x%04x%04x' $RANDOM $RANDOM $RANDOM)"
}
QR=$(uuid7 "$NOW_MS" 0)
LOC=$(cat /proc/sys/kernel/random/uuid)
E1=$(uuid7 "$NOW_MS" 1)
E2=$(uuid7 "$NOW_MS" 2)
E3=$(uuid7 $((NOW_MS + 1)) 0)
T="TIMESTAMP '2026-11-14 12:00:00'"
TX="5VfydnLu4XwV2H5hTy3aYc7xQXzR6s4bQ1k8nUe2pWm9JdFgCtA3rBvKoLhSyNiE"

# --- 1. the application commits a fresh transition, as ADR-0025 writes it -------------------------
# event NAME CODE ID: the last batch of a transition (increment the sequence, insert from it)
event_batch() {
  cat <<SQL
UPDATE event_sequence SET last_seq = last_seq + 1 WHERE id = 1;
INSERT INTO payment_event (seq, event_id, type_code, occurred_at, qr_code_id, qr_code_revision, location_id,
                           amount, tip_amount, payer_info, currency, network, transaction_id, invoice_number,
                           schema_version, deviations, received_in_status_code)
SELECT last_seq, '$2', '$1', $T, '$QR', 0, '$LOC', 22500, -1, 'payer-42', 'USDC', 'solana', '$3', 'INV-1',
       '1.1', '$4', '$5'
  FROM event_sequence WHERE id = 1;
SQL
}

if psql_as "$APP" >/dev/null <<SQL
BEGIN ISOLATION LEVEL READ COMMITTED;
INSERT INTO qr_code (id, location_id, emv_content, valid_until, status_code, revision, lock_version, reserved_until,
  reserved_by_payer_info, reserved_by_signer_subject, end_to_end_id, payment_network, request_json, created_at, revised_at)
VALUES ('$QR', '$LOC', 'emv', TIMESTAMP '2026-12-01 00:00:00', 'ACTV', 0, 0, TIMESTAMP '9999-12-31 00:00:00',
  '', '', '', '', '{}', $T, $T);
COMMIT;

-- A post-commit on an ACTIVE QR Code (ADR-0036): reserve it, store the notification, two events.
BEGIN ISOLATION LEVEL READ COMMITTED;
SET LOCAL lock_timeout = '2s';
UPDATE qr_code SET status_code = 'INIT', reserved_until = TIMESTAMP '9999-12-31 00:00:00',
       reserved_by_payer_info = 'payer-42', reserved_by_signer_subject = 'CN=payer', lock_version = lock_version + 1,
       revised_at = $T
 WHERE id = '$QR' AND lock_version = 0;
INSERT INTO qr_code_notification (id, qr_code_id, received_at, received_in_status_code, deviations, phase, network,
  currency, amount, transaction_id, payer_info, signer_subject, notification_json)
VALUES ('$(uuid7 "$NOW_MS" 9)', '$QR', $T, 'ACTV', 'RECEIVED_IN_WRONG_STATUS', 'POST_COMMIT', 'solana', 'USDC', 22500,
  '$TX', 'payer-42', 'CN=payer', '{}');
$(event_batch INIT "$E1" "" RECEIVED_IN_WRONG_STATUS ACTV)
$(event_batch SENT "$E2" "$TX" RECEIVED_IN_WRONG_STATUS ACTV)
COMMIT;

-- The payment system marks it PAID through status-update: payment.cleared.
BEGIN ISOLATION LEVEL READ COMMITTED;
UPDATE qr_code SET status_code = 'PAID', lock_version = lock_version + 1, revised_at = $T
 WHERE id = '$QR' AND lock_version = 1;
$(event_batch CLRD "$E3" "" "" "")
COMMIT;
SQL
then ok "S6.5-AC5 setup: $APP committed two transitions (three events) the way ADR-0025 writes them"
else bad "S6.5-AC5 setup: the application role could not commit the transitions"; fi

equals "S6.5-AC5 the transition's events are PEND with the read_at sentinel" \
  "$(psql_as "$ER" -c "SELECT string_agg(read_status_code || '@' || read_at, ',' ORDER BY seq)
                         FROM payment_event WHERE qr_code_id = '$QR'")" \
  "PEND@9999-12-31 00:00:00,PEND@9999-12-31 00:00:00,PEND@9999-12-31 00:00:00"

DOC_CLAIM="SELECT e.seq, e.event_id, t.name AS type, e.occurred_at, e.qr_code_id, e.amount, e.currency,
       e.network, e.transaction_id, e.deviations, e.received_in_status_code
  FROM payment_event e
  JOIN event_type t ON t.code = e.type_code
 WHERE e.read_status_code = 'PEND'
 ORDER BY e.seq
 LIMIT 100
 FOR UPDATE OF e SKIP LOCKED"
equals "S6.5-AC2 the claim query of docs/EVENTS-TABLE.md runs as $ER and returns the transition's events" \
  "$(psql_as "$ER" -c "BEGIN" -c "SELECT count(*) FROM ($DOC_CLAIM) c WHERE qr_code_id = '$QR'" -c "ROLLBACK")" "3"

# --- 2. two workers claim at once ---------------------------------------------------------------
CLAIM="SELECT seq FROM payment_event WHERE read_status_code = 'PEND' ORDER BY seq LIMIT 100 FOR UPDATE SKIP LOCKED"
FIFO=$(mktemp -u); mkfifo "$FIFO"
OUT1=$(mktemp)
# Worker 1: claim, report, wait for worker 2, then mark and commit (one transaction).
(
  psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$ER" -d "$DB" <<SQL >"$OUT1" 2>&1
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT string_agg(seq::text, ',' ORDER BY seq) AS claimed FROM ($CLAIM) c \\gset
\\echo claimed::claimed
\\! cat $FIFO >/dev/null
UPDATE payment_event SET read_status_code = 'DONE', read_at = TIMESTAMP '2026-11-14 12:00:05'
 WHERE seq IN (:claimed);
COMMIT;
SQL
) &
W1=$!
for _ in $(seq 1 50); do grep -q '^claimed:' "$OUT1" 2>/dev/null && break; sleep 0.1; done
CLAIMED1=$(grep '^claimed:' "$OUT1" | cut -d: -f2)
OURS=$(psql_as "$ER" -c "SELECT string_agg(seq::text, ',' ORDER BY seq) FROM payment_event WHERE qr_code_id = '$QR'")
equals "S6.5-AC5 worker 1 claims the pending rows of the transition (FOR UPDATE SKIP LOCKED), in seq order" \
  "$(tr ',' '\n' <<<"$CLAIMED1" | grep -xF -f <(tr ',' '\n' <<<"$OURS") | paste -sd, -)" "$OURS"
CLAIMED2=$(psql_as "$ER" -c "BEGIN" -c "SELECT coalesce(string_agg(seq::text, ','), '') FROM ($CLAIM) c" -c "ROLLBACK")
equals "S6.5-AC5 worker 2, claiming meanwhile, skips every row worker 1 holds" \
  "$(tr ',' '\n' <<<"$CLAIMED2" | grep -cxF -f <(tr ',' '\n' <<<"$CLAIMED1"))" "0"
echo go >"$FIFO"
wait "$W1"; w1=$?
rm -f "$FIFO"
equals "S6.5-AC5 worker 1 marks its rows DONE and commits" "$w1" "0"
[ "$w1" -eq 0 ] || cat "$OUT1"
rm -f "$OUT1"

# --- 3. what the reader sees afterwards ----------------------------------------------------------
equals "S6.5-AC5 the transition's rows are DONE, with read_at set" \
  "$(psql_as "$ER" -c "SELECT string_agg(read_status_code || '@' || read_at, ',' ORDER BY seq)
                         FROM payment_event WHERE qr_code_id = '$QR'")" \
  "DONE@2026-11-14 12:00:05,DONE@2026-11-14 12:00:05,DONE@2026-11-14 12:00:05"
equals "S6.5-AC5 nothing of the transition is left to claim" \
  "$(psql_as "$ER" -c "SELECT count(*) FROM payment_event WHERE qr_code_id = '$QR' AND read_status_code = 'PEND'")" "0"
equals "S6.5-AC5 in seq order the events are payment.initiated, payment.sent, payment.cleared" \
  "$(psql_as "$ER" -c "SELECT string_agg(t.name, ',' ORDER BY e.seq) FROM payment_event e
                         JOIN event_type t ON t.code = e.type_code WHERE e.qr_code_id = '$QR'")" \
  "payment.initiated,payment.sent,payment.cleared"
equals "S6.5-AC5 seq order and event_id order are the same order" \
  "$(psql_as "$ER" -c "SELECT string_agg(event_id::text, ',' ORDER BY seq) FROM payment_event WHERE qr_code_id = '$QR'")" \
  "$(psql_as "$ER" -c "SELECT string_agg(event_id::text, ',' ORDER BY event_id) FROM payment_event WHERE qr_code_id = '$QR'")"
equals "S6.5-AC5 seq is gapless across the two transitions" \
  "$(psql_as "$ER" -c "SELECT max(seq) - min(seq) + 1 = count(*) FROM payment_event WHERE qr_code_id = '$QR'")" "t"
equals "S6.5-AC5 the hash and its deviations reached the reader unchanged" \
  "$(psql_as "$ER" -c "SELECT transaction_id || '|' || deviations || '|' || received_in_status_code FROM payment_event
                         WHERE qr_code_id = '$QR' AND type_code = 'SENT'")" "$TX|RECEIVED_IN_WRONG_STATUS|ACTV"
psql_as "$ER" -c "UPDATE payment_event SET amount = 0 WHERE qr_code_id = '$QR'" >/dev/null 2>&1 \
  && bad "S6.5-AC5 the reader cannot change what an event says" \
  || ok "S6.5-AC5 the reader cannot change what an event says"

echo "== $pass passed, $fail failed"
[ "$fail" -eq 0 ]
