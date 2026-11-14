#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Story 6.2: each of the four roles can do its own job and nothing else (S6.2-AC1..AC5).
#
# Runs against a THROWAWAY database where the changelog has just been applied, logging in as each
# role over the local socket (the postgres image trusts it), so a role's own search_path is what a
# real login sees. Run it inside the postgres container:
#
#   docker exec <pg-container> bash /db/test/roles-test.sh
#
# Names come from the environment, with the changelog's defaults:
#   X9_DB X9_SCHEMA X9_ROLE_OWNER X9_ROLE_APP X9_ROLE_READONLY X9_ROLE_EVENT_READER
#
# It writes one payment_event row (seq -42) and deletes it again at the end.

set -uo pipefail

DB="${X9_DB:-postgres}"
SCHEMA="${X9_SCHEMA:-x9_qrcode}"
OWNER="${X9_ROLE_OWNER:-x9_qrcode_owner}"
APP="${X9_ROLE_APP:-x9_qrcode_app}"
RO="${X9_ROLE_READONLY:-x9_qrcode_readonly}"
ER="${X9_ROLE_EVENT_READER:-x9_qrcode_event_reader}"
SU="${X9_SUPERUSER:-postgres}"

TABLES="qr_code_status event_type event_read_status qr_code qr_code_audit_log qr_code_notification event_sequence payment_event payment_event_legacy_cursor"

pass=0
fail=0

ok()  { pass=$((pass + 1)); echo "PASS  $1"; }
bad() { fail=$((fail + 1)); echo "FAIL  $1"; [ -n "${2:-}" ] && echo "      $2"; }

# q ROLE SQL -> prints the single value, exit code of psql
q() { psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$1" -d "$DB" -c "$2" 2>&1; }

# allowed ID ROLE SQL: the statement succeeds (writes are rolled back)
allowed() {
  local out
  if out=$(psql -X -q -v ON_ERROR_STOP=1 -U "$2" -d "$DB" -c "BEGIN" -c "$3" -c "ROLLBACK" 2>&1); then
    ok "$1"
  else
    bad "$1" "$out"
  fi
}

# denied ID ROLE SQL: the statement fails with a privilege error (42501 insufficient_privilege)
denied() {
  local out
  out=$(psql -X -q -v ON_ERROR_STOP=1 -v VERBOSITY=verbose -U "$2" -d "$DB" \
        -c "\\set VERBOSITY verbose" -c "BEGIN" -c "$3" -c "ROLLBACK" 2>&1)
  if [ $? -ne 0 ] && grep -q "42501" <<<"$out"; then
    ok "$1"
  else
    bad "$1" "expected a permission error (42501), got: $out"
  fi
}

# first_column TABLE
first_column() {
  q "$SU" "SELECT column_name FROM information_schema.columns WHERE table_schema = '$SCHEMA' AND table_name = '$1'
            ORDER BY ordinal_position LIMIT 1"
}

# equals ID ACTUAL EXPECTED
equals() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected [$3], got [$2]"; fi; }

echo "== database $DB, schema $SCHEMA; roles $OWNER, $APP, $RO, $ER"

# A pending event to read, mark and purge. Inserted by the application role, as a transition does.
EVENT_INSERT="INSERT INTO payment_event (seq, event_id, type_code, occurred_at, qr_code_id, qr_code_revision,
  location_id, schema_version) VALUES (-42, '01900000-0000-7000-8000-000000000042', 'INIT',
  TIMESTAMP '2026-11-14 12:00:00', '01900000-0000-7000-8000-0000000000a1', 0,
  '4f1c2b0e-8d6a-4c55-9a51-2b7c3d4e5f60', '1.1')"

# --- S6.2-AC1: four roles, explicit grants, default privileges -------------------------------------
for r in "$OWNER" "$APP" "$RO" "$ER"; do
  equals "S6.2-AC1 role $r exists" "$(q "$SU" "SELECT count(*) FROM pg_roles WHERE rolname = '$r'")" "1"
done
for r in "$APP" "$RO" "$ER"; do
  equals "S6.2-AC1 $r is neither superuser nor CREATEDB/CREATEROLE/REPLICATION/BYPASSRLS" \
    "$(q "$SU" "SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname = '$r'")" "f"
done
equals "S6.2-AC1 the owner owns every table of the schema" \
  "$(q "$SU" "SELECT count(*) FROM pg_tables WHERE schemaname = '$SCHEMA' AND tableowner <> '$OWNER'")" "0"
equals "S6.2-AC1 the owner owns the schema" \
  "$(q "$SU" "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = '$SCHEMA'")" "$OWNER"
equals "S6.2-AC1 default privileges: new tables give $APP SELECT+INSERT and $RO SELECT, nothing else" \
  "$(q "$SU" "SELECT string_agg(a, ' ' ORDER BY a) FROM (SELECT unnest(defaclacl)::text AS a FROM pg_default_acl d
        JOIN pg_namespace n ON n.oid = d.defaclnamespace
       WHERE n.nspname = '$SCHEMA' AND d.defaclrole = '$OWNER'::regrole AND d.defaclobjtype = 'r') x")" \
  "$(printf '%s\n' "$APP=ar/$OWNER" "$RO=r/$OWNER" | sort | tr '\n' ' ' | sed 's/ $//')"
equals "S6.2-AC1 a table created later by the owner reaches $APP and $RO" \
  "$(q "$OWNER" "BEGIN; CREATE TABLE $SCHEMA.zz_default_privileges_probe (i INT);
       SELECT has_table_privilege('$APP', '$SCHEMA.zz_default_privileges_probe', 'INSERT')
           AND has_table_privilege('$RO', '$SCHEMA.zz_default_privileges_probe', 'SELECT')
           AND NOT has_table_privilege('$RO', '$SCHEMA.zz_default_privileges_probe', 'INSERT')
           AND NOT has_table_privilege('$ER', '$SCHEMA.zz_default_privileges_probe', 'SELECT');
       ROLLBACK;")" "t"
equals "S6.2-AC1 PUBLIC has no privilege on any table of the schema" \
  "$(q "$SU" "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace,
       aclexplode(c.relacl) a WHERE n.nspname = '$SCHEMA' AND a.grantee = 0")" "0"

# --- S6.2-AC2: the application role --------------------------------------------------------------
equals "S6.2-AC2 ALTER ROLE $APP SET search_path is stored" \
  "$(q "$SU" "SELECT array_to_string(setconfig, ',') FROM pg_db_role_setting WHERE setrole = '$APP'::regrole AND setdatabase = 0")" \
  "search_path=$SCHEMA"
equals "S6.2-AC2 $APP logs in with current_schema() = $SCHEMA" "$(q "$APP" "SELECT current_schema()")" "$SCHEMA"
allowed "S6.2-AC2 $APP reads every table unqualified" "$APP" \
  "SELECT (SELECT count(*) FROM qr_code_status) + (SELECT count(*) FROM event_type) + (SELECT count(*) FROM event_read_status)
        + (SELECT count(*) FROM qr_code) + (SELECT count(*) FROM qr_code_audit_log) + (SELECT count(*) FROM qr_code_notification)
        + (SELECT count(*) FROM event_sequence) + (SELECT count(*) FROM payment_event) + (SELECT count(*) FROM payment_event_legacy_cursor)"
allowed "S6.2-AC2 $APP reads databasechangelog (startup schema check)" "$APP" "SELECT max(orderexecuted) FROM databasechangelog"
allowed "S6.2-AC2 $APP inserts and updates a QR Code" "$APP" \
  "INSERT INTO qr_code (id, location_id, emv_content, valid_until, status_code, revision, lock_version, reserved_until,
     reserved_by_payer_info, reserved_by_signer_subject, end_to_end_id, payment_network, request_json, created_at, revised_at)
   VALUES ('01900000-0000-7000-8000-0000000000b1', '4f1c2b0e-8d6a-4c55-9a51-2b7c3d4e5f61', 'emv', TIMESTAMP '2026-12-01 00:00:00',
     'ACTV', 0, 0, TIMESTAMP '9999-12-31 00:00:00', '', '', '', '', '{}', TIMESTAMP '2026-11-14 12:00:00', TIMESTAMP '2026-11-14 12:00:00');
   UPDATE qr_code SET lock_version = lock_version + 1 WHERE id = '01900000-0000-7000-8000-0000000000b1';
   INSERT INTO qr_code_audit_log VALUES ('01900000-0000-7000-8000-0000000000b1', 0, TIMESTAMP '2026-11-14 12:00:01', 'bill', '{}')"
allowed "S6.2-AC2 $APP takes the next event number and inserts the event (ADR-0025)" "$APP" \
  "UPDATE event_sequence SET last_seq = last_seq + 1 WHERE id = 1;
   INSERT INTO payment_event (seq, event_id, type_code, occurred_at, qr_code_id, qr_code_revision, location_id, schema_version)
   SELECT last_seq, '01900000-0000-7000-8000-0000000000c1', 'INIT', TIMESTAMP '2026-11-14 12:00:00',
          '01900000-0000-7000-8000-0000000000b1', 0, '4f1c2b0e-8d6a-4c55-9a51-2b7c3d4e5f61', '1.1' FROM event_sequence WHERE id = 1"
denied "S6.2-AC2 $APP cannot CREATE a table in the schema" "$APP" "CREATE TABLE $SCHEMA.zz_probe (i INT)"
denied "S6.2-AC2 $APP cannot CREATE a table unqualified" "$APP" "CREATE TABLE zz_probe (i INT)"
denied "S6.2-AC2 $APP cannot CREATE a table in public" "$APP" "CREATE TABLE public.zz_probe (i INT)"
denied "S6.2-AC2 $APP cannot CREATE an index" "$APP" "CREATE INDEX zz_probe ON qr_code (created_at)"
denied "S6.2-AC2 $APP cannot ALTER a table" "$APP" "ALTER TABLE qr_code ADD COLUMN zz INT"
denied "S6.2-AC2 $APP cannot DROP a table" "$APP" "DROP TABLE payment_event_legacy_cursor"
denied "S6.2-AC2 $APP cannot TRUNCATE" "$APP" "TRUNCATE payment_event"
denied "S6.2-AC2 $APP cannot write databasechangelog" "$APP" "DELETE FROM databasechangelog"
denied "S6.2-AC2 $APP cannot UPDATE payment_event after the insert (ADR-0037)" "$APP" "UPDATE payment_event SET read_status_code = 'DONE'"
denied "S6.2-AC2 $APP cannot UPDATE the append-only qr_code_audit_log" "$APP" "UPDATE qr_code_audit_log SET changed_fields = ''"
denied "S6.2-AC2 $APP cannot UPDATE the append-only qr_code_notification" "$APP" "UPDATE qr_code_notification SET deviations = ''"
denied "S6.2-AC2 $APP cannot UPDATE payment_event_legacy_cursor" "$APP" "UPDATE payment_event_legacy_cursor SET seq = 0"
denied "S6.2-AC2 $APP cannot DELETE from event_sequence" "$APP" "DELETE FROM event_sequence"
for t in qr_code_status event_type event_read_status; do
  denied "S6.2-AC2 $APP cannot write the lookup $t" "$APP" "DELETE FROM $t"
done
for t in qr_code qr_code_audit_log qr_code_notification payment_event payment_event_legacy_cursor; do
  allowed "S6.2-AC2 $APP may DELETE from $t through the retention purge's grant (ADR-0029)" "$APP" "DELETE FROM $t WHERE false"
done

# --- S6.2-AC3: the read-only role ----------------------------------------------------------------
equals "S6.2-AC3 $RO logs in with current_schema() = $SCHEMA" "$(q "$RO" "SELECT current_schema()")" "$SCHEMA"
for t in $TABLES databasechangelog; do
  allowed "S6.2-AC3 $RO reads $t" "$RO" "SELECT count(*) FROM $t"
done
for t in $TABLES; do
  denied "S6.2-AC3 $RO cannot INSERT into $t" "$RO" "INSERT INTO $t DEFAULT VALUES"
  c=$(first_column "$t")
  denied "S6.2-AC3 $RO cannot UPDATE $t" "$RO" "UPDATE $t SET $c = $c"
  denied "S6.2-AC3 $RO cannot DELETE from $t" "$RO" "DELETE FROM $t"
  denied "S6.2-AC3 $RO cannot TRUNCATE $t" "$RO" "TRUNCATE $t"
done
denied "S6.2-AC3 $RO cannot CREATE a table" "$RO" "CREATE TABLE $SCHEMA.zz_probe (i INT)"
denied "S6.2-AC3 $RO cannot write databasechangelog" "$RO" "DELETE FROM databasechangelog"
denied "S6.2-AC3 $RO cannot take a row lock (SELECT … FOR UPDATE)" "$RO" "SELECT seq FROM payment_event FOR UPDATE"

# --- S6.2-AC4: the event reader (ADR-0037) -------------------------------------------------------
q "$APP" "$EVENT_INSERT" >/dev/null || bad "setup: $APP inserts the test event" "$(q "$APP" "$EVENT_INSERT")"
equals "S6.2-AC4 $ER logs in with current_schema() = $SCHEMA" "$(q "$ER" "SELECT current_schema()")" "$SCHEMA"
allowed "S6.2-AC4 $ER reads payment_event and its lookups" "$ER" \
  "SELECT e.seq, t.name, r.description FROM payment_event e JOIN event_type t ON t.code = e.type_code
     JOIN event_read_status r ON r.code = e.read_status_code"
allowed "S6.2-AC4 $ER claims pending rows with FOR UPDATE SKIP LOCKED" "$ER" \
  "SELECT seq FROM payment_event WHERE read_status_code = 'PEND' ORDER BY seq LIMIT 100 FOR UPDATE SKIP LOCKED"
allowed "S6.2-AC4 $ER marks a row DONE (read_status_code, read_at)" "$ER" \
  "UPDATE payment_event SET read_status_code = 'DONE', read_at = TIMESTAMP '2026-11-14 12:00:05' WHERE seq = -42"
for c in "seq = seq" "event_id = event_id" "type_code = 'CLRD'" "occurred_at = occurred_at" "qr_code_id = qr_code_id" \
         "amount = 1" "tip_amount = 1" "payer_info = 'x'" "currency = 'USD'" "network = 'rtp'" "transaction_id = 'x'" \
         "invoice_number = 'x'" "order_number = 'x'" "reason = 'x'" "schema_version = '9.9'" "deviations = 'x'" \
         "received_in_status_code = 'PAID'" "location_id = location_id" "qr_code_revision = 1"; do
  denied "S6.2-AC4 $ER cannot UPDATE payment_event SET $c" "$ER" "UPDATE payment_event SET $c WHERE seq = -42"
done
denied "S6.2-AC4 $ER cannot UPDATE read_status_code together with another column" "$ER" \
  "UPDATE payment_event SET read_status_code = 'DONE', amount = 0 WHERE seq = -42"
denied "S6.2-AC4 $ER cannot INSERT an event" "$ER" "$EVENT_INSERT"
denied "S6.2-AC4 $ER cannot DELETE an event" "$ER" "DELETE FROM payment_event WHERE seq = -42"
denied "S6.2-AC4 $ER cannot TRUNCATE payment_event" "$ER" "TRUNCATE payment_event"
for t in qr_code qr_code_audit_log qr_code_notification event_sequence payment_event_legacy_cursor databasechangelog; do
  denied "S6.2-AC4 $ER cannot read $t" "$ER" "SELECT count(*) FROM $t"
done
denied "S6.2-AC4 $ER cannot CREATE a table" "$ER" "CREATE TABLE $SCHEMA.zz_probe (i INT)"
q "$APP" "DELETE FROM payment_event WHERE seq = -42" >/dev/null || bad "teardown: $APP purges the test event"

# --- S6.2-AC5: no name or password hard-coded ----------------------------------------------------
equals "S6.2-AC5 no role of this changelog has a password set by it" \
  "$(q "$SU" "SELECT count(*) FROM pg_authid WHERE rolname IN ('$APP', '$RO', '$ER') AND rolpassword IS NOT NULL")" "0"

CHANGELOG_DIR="${X9_CHANGELOG_DIR:-/db/changelog}"
if [ -d "$CHANGELOG_DIR" ]; then
  equals "S6.2-AC5 no changelog statement carries a PASSWORD clause (comments aside)" \
    "$(grep -rh -v '^[[:space:]]*#' "$CHANGELOG_DIR" | grep -c 'PASSWORD')" "0"
  equals "S6.2-AC5 no changelog statement names a role literally (only \${x9.role.*})" \
    "$(grep -rh -v '^[[:space:]]*#' "$CHANGELOG_DIR"/changes | grep -cE 'x9_qrcode_(owner|app|readonly|event_reader)')" "0"
fi

echo "== $pass passed, $fail failed"
[ "$fail" -eq 0 ]
