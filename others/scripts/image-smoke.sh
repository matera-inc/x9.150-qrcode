#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Checks a built image (Story 7.6) and records its footprint for S7 (Epic 5):
#   - it runs as non-root, and -version prints the stamped version and commit;
#   - against a throwaway PostgreSQL with the changelog applied (db/docker-compose.yml), it reaches
#     /actuator/health UP; the time from `docker run` to that answer is the cold start;
#   - SIGTERM (docker stop) ends it with exit code 0.
# It writes <out>/go-image.json: image, architecture, size in bytes (unpacked and compressed, read
# by others/scripts/image-size.py), cold start in ms.
#
#   others/scripts/image-smoke.sh [image] [out-dir]        # defaults: x9-qrcode:latest, ./go-ci-out
#   X9_PLATFORM=linux/amd64 others/scripts/image-smoke.sh …   # run the image as that platform
#
# Nothing is pushed anywhere.

set -euo pipefail
cd "$(dirname "$0")/../.."

IMAGE="${1:-x9-qrcode:latest}"
OUT="${2:-go-ci-out}"
# The platform to run the image as (publish-image.sh checks each architecture by its own tag).
PLATFORM=()
if [ -n "${X9_PLATFORM:-}" ]; then PLATFORM=(--platform "$X9_PLATFORM"); fi
P="x9goimg$$"
APP="$P-app"
PORT="${X9_SMOKE_PORT:-18080}"

cleanup() {
  docker rm -f "$APP" >/dev/null 2>&1 || true
  docker compose -f db/docker-compose.yml -p "$P" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
fail() { echo "FAIL  $*"; docker logs "$APP" 2>&1 | tail -20 || true; exit 1; }

mkdir -p "$OUT"
user=$(docker image inspect "$IMAGE" --format '{{.Config.User}}')
arch=$(docker image inspect "$IMAGE" --format '{{.Os}}/{{.Architecture}}')
# The image's size from what `docker save` writes, not {{.Size}}, which means the unpacked layers on
# Docker's classic image store and the content size or the disk usage on its containerd store.
sizes=$(python3 -I others/scripts/image-size.py "$IMAGE")
size=$(printf '%s' "$sizes" | python3 -I -c 'import json, sys; print(json.load(sys.stdin)["sizeBytes"])')
compressed=$(printf '%s' "$sizes" | python3 -I -c 'import json, sys; print(json.load(sys.stdin)["compressedSizeBytes"])')
case "$user" in "" | 0 | 0:* | root | root:*) fail "the image runs as root (User=\"$user\")" ;; esac
echo "ok  runs as $user ($arch, $size bytes unpacked, $compressed compressed)"
docker run --rm ${PLATFORM[@]+"${PLATFORM[@]}"} "$IMAGE" -version | tee /dev/stderr | grep -q '^x9-qrcode ' || fail "-version"

echo "### a throwaway PostgreSQL with the changelog applied"
X9_DB_PORT=0 docker compose -f db/docker-compose.yml -p "$P" up -d --build --quiet-pull >/dev/null 2>&1 ||
  X9_DB_PORT=0 docker compose -f db/docker-compose.yml -p "$P" up -d --build
for _ in $(seq 1 120); do
  state=$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$P-liquibase-1" 2>/dev/null || true)
  case "$state" in "exited 0") break ;; exited*) fail "liquibase: $state" ;; esac
  sleep 1
done
[ "$state" = "exited 0" ] || fail "liquibase did not finish"

echo "### cold start to /actuator/health UP"
start=$(now_ms)
docker run -d ${PLATFORM[@]+"${PLATFORM[@]}"} --name "$APP" --network "${P}_default" -p "127.0.0.1:$PORT:8080" \
  -e DATABASE_HOST=postgres -e DATABASE_NAME=postgres -e DATABASE_USER=x9_qrcode_app \
  -e DATABASE_PASSWORD=unused -e DATABASE_SSLMODE=disable -e X9_SERVER_SHUTDOWNDELAYSECONDS=1 \
  "$IMAGE" >/dev/null
up=""
for _ in $(seq 1 600); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/actuator/health")" = 200 ]; then
    up=$(now_ms); break
  fi
  sleep 0.05
done
[ -n "$up" ] || fail "/actuator/health never answered UP"
cold=$((up - start))
echo "ok  $(curl -s "http://127.0.0.1:$PORT/actuator/health") after ${cold} ms"

echo "### SIGTERM"
docker stop -t 30 "$APP" >/dev/null
code=$(docker inspect -f '{{.State.ExitCode}}' "$APP")
[ "$code" = 0 ] || fail "exit code $code after SIGTERM"
docker logs "$APP" 2>&1 | grep -q '"msg":"x9-qrcode stopped"' || fail "no clean stop in the log"
echo "ok  exit code 0"

printf '{"image":"%s","platform":"%s","sizeBytes":%s,"compressedSizeBytes":%s,"coldStartToHealthUpMs":%s}\n' \
  "$IMAGE" "$arch" "$size" "$compressed" "$cold" | tee "$OUT/go-image.json"
