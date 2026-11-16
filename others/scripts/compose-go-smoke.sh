#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Brings docker-compose.go.yml up as a clean machine would, and checks it (Story 14.2, S14.2-AC2):
#   - every service starts in order: PostgreSQL, the bootstrap, Liquibase, then the Go service;
#   - Liquibase exited 0 and the service reports health UP;
#   - one create (a playground sample) answers 201.
# Then it takes everything down, volumes included. CI runs exactly this; so can a laptop.
#
#   others/scripts/compose-go-smoke.sh                     # builds the Go image from the Dockerfile
#   X9_IMAGE=x9-qrcode:go others/scripts/compose-go-smoke.sh   # an image you already have
#
# Its own project name and a free host port, so it never touches a stack already running.

set -euo pipefail
cd "$(dirname "$0")/../.."

P="x9gocompose$$"
export X9_PORT="${X9_PORT:-$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')}"
SAMPLE="${X9_SMOKE_SAMPLE:-playground/requests/qr-burger-createqr.json}"
compose() { docker compose -f docker-compose.go.yml -p "$P" "$@"; }

BUILT=""
cleanup() {
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  if [ -n "$BUILT" ]; then docker image rm "$BUILT" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
fail() {
  echo "FAIL  $*"
  compose ps -a || true
  compose logs --no-color --tail 40 || true
  exit 1
}

if [ -z "${X9_IMAGE:-}" ]; then
  echo "### the Go image, from the Dockerfile (make go-image)"
  export X9_IMAGE="x9-qrcode:go-compose-$P"
  BUILT="$X9_IMAGE"
  make go-image GO_IMAGE="$X9_IMAGE"
fi
echo "### bootstrap and liquibase images; the Go image is $X9_IMAGE"
compose build bootstrap liquibase

echo "### up, waiting for health"
compose up -d --no-build --wait --wait-timeout 180 || fail "the stack did not come up healthy"

lb=$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$(compose ps -aq liquibase)")
[ "$lb" = "exited 0" ] || fail "liquibase: $lb"
echo "ok  liquibase applied the changelog and exited 0"

health=$(curl -fsS "http://127.0.0.1:$X9_PORT/actuator/health") || fail "/actuator/health"
case "$health" in *'"status":"UP"'*) ;; *) fail "health is $health" ;; esac
echo "ok  /actuator/health: $health"

echo "### one create ($SAMPLE)"
out=$(mktemp)
code=$(curl -sS -o "$out" -w '%{http_code}' -H 'Content-Type: application/json' \
  --data-binary "@$SAMPLE" "http://127.0.0.1:$X9_PORT/api/v1/payment-request")
[ "$code" = 201 ] || { cat "$out"; echo; fail "create answered $code, want 201"; }
grep -q '"id"' "$out" || { cat "$out"; echo; fail "the 201 carries no id"; }
echo "ok  create answered 201"
rm -f "$out"

echo "PASS  docker-compose.go.yml: liquibase, then the Go service, health UP, create 201"
