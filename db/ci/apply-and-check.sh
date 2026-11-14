#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Story 6.3: apply the changelog to a THROWAWAY PostgreSQL and check the result. CI runs it on
# PostgreSQL 16 and on the newest major; it runs the same on a laptop:
#
#   db/ci/apply-and-check.sh [postgres-image] [output-dir]      # defaults: postgres:16, ./db-out
#
# It copies db/ into the containers (docker cp) instead of mounting it, so it works on Docker hosts
# that cannot mount the repository's directory. Containers and network are removed on exit.
#
#   A. Liquibase applies the changelog (update), then again: the second run applies nothing.
#      Smoke query (tables, comments, roles, search_path, nothing in public), then the role tests.
#   B. The DBA's path, in a second database with other schema and role names: `update-sql` writes
#      the SQL to <output-dir>/update-sql.sql (S6.3-AC3), psql runs exactly that file as the owner,
#      and Liquibase then finds nothing left to apply. Smoke query and role tests again.

set -euo pipefail

IMAGE="${1:-postgres:16}"
OUT="${2:-db-out}"
LIQUIBASE_IMAGE="liquibase/liquibase:4.33.0"
HERE="$(cd "$(dirname "$0")/.." && pwd)"   # the db/ directory
ID="x9db-$$"
NET="$ID-net"; PG="$ID-pg"; LB="$ID-lb"

cleanup() {
  docker rm -f "$PG" "$LB" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

step() { echo; echo "### $*"; }

mkdir -p "$OUT"
docker network create "$NET" >/dev/null
docker create --name "$PG" --network "$NET" -e POSTGRES_HOST_AUTH_METHOD=trust "$IMAGE" >/dev/null
docker cp "$HERE" "$PG:/db"
docker start "$PG" >/dev/null
docker create --name "$LB" --network "$NET" --entrypoint sleep "$LIQUIBASE_IMAGE" infinity >/dev/null
docker cp "$HERE" "$LB:/liquibase/x9"
docker start "$LB" >/dev/null

for _ in $(seq 1 60); do
  if docker exec "$PG" pg_isready -U postgres -q 2>/dev/null && docker exec "$PG" psql -X -q -U postgres -c "SELECT 1" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec "$PG" psql -X -q -t -A -U postgres -c "SELECT version()" | tee "$OUT/server-version.txt"

psql_su() { docker exec "$PG" psql -X -q -v ON_ERROR_STOP=1 -U postgres "$@"; }

# lb DATABASE SCHEMA OWNER COMMAND [-Dname=value…]
lb() {
  local db=$1 schema=$2 owner=$3; shift 3
  docker exec "$LB" liquibase --show-banner=false --search-path=/liquibase/x9 \
    --changelog-file=changelog/db.changelog-master.yaml \
    --url="jdbc:postgresql://$PG:5432/$db" --username="$owner" \
    --default-schema-name="$schema" --liquibase-schema-name="$schema" "$@"
}

# checks DATABASE SCHEMA OWNER APP READONLY EVENT_READER
checks() {
  psql_su -d "$1" -v schema="$2" -v owner="$3" -v app="$4" -v readonly="$5" -v event_reader="$6" -f /db/test/smoke.sql
  docker exec -e X9_DB="$1" -e X9_SCHEMA="$2" -e X9_ROLE_OWNER="$3" -e X9_ROLE_APP="$4" \
    -e X9_ROLE_READONLY="$5" -e X9_ROLE_EVENT_READER="$6" "$PG" bash /db/test/roles-test.sh
}

# --- A. Liquibase update, twice ------------------------------------------------------------------
step "A1. bootstrap (owner role + schema), then liquibase update"
psql_su -d postgres -v owner=x9_qrcode_owner -v schema=x9_qrcode -f /db/local/bootstrap.sql
lb postgres x9_qrcode x9_qrcode_owner update | tee "$OUT/update-1.log"
grep -Eq "^Run: +[1-9]" "$OUT/update-1.log" || { echo "FAIL  the first update applied nothing"; exit 1; }

step "A2. liquibase update again: applies nothing"
lb postgres x9_qrcode x9_qrcode_owner update | tee "$OUT/update-2.log"
grep -Eq "^Run: +0$" "$OUT/update-2.log" || { echo "FAIL  the second update applied something"; exit 1; }

step "A3. smoke query and role tests"
checks postgres x9_qrcode x9_qrcode_owner x9_qrcode_app x9_qrcode_readonly x9_qrcode_event_reader

# --- B. The DBA's path: review the SQL, run exactly it ------------------------------------------
NAMES=(-Dx9.schema=bank_x9 -Dx9.role.owner=bank_x9_owner -Dx9.role.app=bank_x9_app
       -Dx9.role.readonly=bank_x9_readonly -Dx9.role.event_reader=bank_x9_event_reader)

step "B1. a second database with other names: update-sql writes the SQL a DBA reviews"
psql_su -c "CREATE DATABASE dba_review"
psql_su -d dba_review -v owner=bank_x9_owner -v schema=bank_x9 -f /db/local/bootstrap.sql
lb dba_review bank_x9 bank_x9_owner update-sql "${NAMES[@]}" > "$OUT/update-sql.sql"
grep -c "" "$OUT/update-sql.sql" | sed 's/^/lines of SQL: /'
for must in "CREATE TABLE bank_x9.payment_event" "COMMENT ON COLUMN bank_x9.payment_event.read_status_code" \
            "CREATE ROLE bank_x9_app" "GRANT UPDATE (read_status_code, read_at) ON bank_x9.payment_event TO bank_x9_event_reader" \
            "ALTER DEFAULT PRIVILEGES FOR ROLE bank_x9_owner"; do
  grep -qF "$must" "$OUT/update-sql.sql" || { echo "FAIL  update-sql.sql lacks: $must"; exit 1; }
done
echo "ok  update-sql.sql holds the tables, comments, roles, grants and default privileges"

step "B2. the DBA runs exactly that file, as the owner"
docker cp "$OUT/update-sql.sql" "$PG:/tmp/update-sql.sql"
docker exec "$PG" psql -X -q -v ON_ERROR_STOP=1 -U bank_x9_owner -d dba_review -f /tmp/update-sql.sql >/dev/null
echo "ok  update-sql.sql ran without error"

step "B3. Liquibase finds nothing left to apply"
lb dba_review bank_x9 bank_x9_owner update "${NAMES[@]}" | tee "$OUT/update-3.log"
grep -Eq "^Run: +0$" "$OUT/update-3.log" || { echo "FAIL  the reviewed SQL did not apply everything"; exit 1; }

step "B4. smoke query and role tests"
checks dba_review bank_x9 bank_x9_owner bank_x9_app bank_x9_readonly bank_x9_event_reader

echo
echo "ALL CHECKS PASSED on $(cat "$OUT/server-version.txt" | cut -d' ' -f1-2)"
