#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# S7 — footprint (S5.1-AC3): what "lighter" means to an operator, for one build.
#
#   footprint.sh java-mongo|go-postgres <out-dir>
#
# Records into <out-dir>/s7.json:
#   - image size: unpacked (the layers' tars) and compressed (each layer gzipped, close to what a
#     registry transfers), both read from `docker save` by others/scripts/image-size.py, the same
#     on Docker's classic and containerd image stores (`docker image inspect`'s {{.Size}} is not:
#     on the containerd store it is the compressed content or the disk usage, by version);
#   - cold start: milliseconds from `docker run` to the first /actuator/health answering 200 UP,
#     polled every 50 ms, with the database already up (the method of others/scripts/image-smoke.sh);
#   - resting memory: the container's memory use (cgroup usage minus inactive file cache, as
#     `docker stats` and the kubelet report it) after IDLE_SECONDS (default 300) with no request,
#     plus a sample every 30 s in idle-memory.csv.
#
# It runs on the host's container engine (the one build-images.sh builds into), with the same
# cgroup limits as the Kubernetes manifests: --cpus 2 --memory 2g (CPUS / MEMORY to change them;
# collect.sh passes 1 / 1g with OVERLAY=small). Run it with nothing else of the benchmark deployed:
# collect.sh tears its namespace down first. Nothing is pushed anywhere.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

BUILD="${1:?usage: footprint.sh java-mongo|go-postgres <out-dir>}"
OUT="${2:?usage: footprint.sh java-mongo|go-postgres <out-dir>}"
IMAGE="$(image_of "$BUILD")"
CPUS="${CPUS:-2}"
MEMORY="${MEMORY:-2g}"
IDLE_SECONDS="${IDLE_SECONDS:-300}"
P="x9bench-s7-$$"
NET="$P-net"
APP="$P-app"
DB="$P-db"

cleanup() {
  # -v: MongoDB's image declares anonymous volumes, which would otherwise outlive every S7 run.
  docker rm -f -v "$APP" "$DB" >/dev/null 2>&1 || true
  docker compose -f "$ROOT/db/docker-compose.yml" -p "$P" down -v >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker image inspect "$IMAGE" >/dev/null 2>&1 || die "image $IMAGE is not built (build-images.sh)"
mkdir -p "$OUT"

log "S7 $BUILD: image size"
size="$(image_size "$IMAGE" sizeBytes)"
compressed="$(image_size "$IMAGE" compressedSizeBytes)"

log "S7 $BUILD: database"
if [ "$BUILD" = java-mongo ]; then
  docker network create "$NET" >/dev/null
  docker run -d --name "$DB" --network "$NET" --network-alias mongo mongo:7.0.45 \
    mongod --replSet rs0 --bind_ip_all >/dev/null
  for _ in $(seq 1 120); do
    docker exec "$DB" mongosh --quiet --eval "
      try { rs.status().ok } catch (e) { rs.initiate({_id: 'rs0', members: [{_id: 0, host: 'mongo:27017'}]}) };
      if (!db.hello().isWritablePrimary) quit(1);
      db.adminCommand({setDefaultRWConcern: 1, defaultWriteConcern: {w: 'majority', j: true}})" \
      >/dev/null 2>&1 && break
    sleep 1
  done
  APP_ENV=(-e "SPRING_DATA_MONGODB_URI=mongodb://mongo:27017/x9-qrcode?replicaSet=rs0&w=majority&journal=true"
           -e X9_PUBLICENDPOINTS_HOST=x9-150.example.com -e X9_TESTING_CLOCKCONTROL=false)
else
  # PostgreSQL 16 with the changelog applied, exactly as image-smoke.sh does.
  X9_DB_PORT=0 docker compose -f "$ROOT/db/docker-compose.yml" -p "$P" up -d --build --quiet-pull >/dev/null 2>&1 ||
    X9_DB_PORT=0 docker compose -f "$ROOT/db/docker-compose.yml" -p "$P" up -d --build >/dev/null
  NET="${P}_default"
  for _ in $(seq 1 180); do
    state=$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$P-liquibase-1" 2>/dev/null || true)
    case "$state" in "exited 0") break ;; exited*) die "liquibase: $state" ;; esac
    sleep 1
  done
  [ "$state" = "exited 0" ] || die "liquibase did not finish"
  # 80% of the memory limit, as in the Kubernetes manifest.
  mem_mib=$(python3 -c "import re,sys; v,u=re.fullmatch(r'(\d+)([gm])','$MEMORY'.lower()).groups(); print(int(int(v)*(1024 if u=='g' else 1)*0.8))")
  APP_ENV=(-e DATABASE_HOST=postgres -e DATABASE_NAME=postgres -e DATABASE_USER=x9_qrcode_app
           -e DATABASE_PASSWORD=unused -e DATABASE_SSLMODE=disable -e "GOMEMLIMIT=${mem_mib}MiB"
           -e X9_PUBLICENDPOINTS_HOST=x9-150.example.com -e X9_TESTING_CLOCKCONTROL=false)
fi

log "S7 $BUILD: cold start to /actuator/health UP (signing $SIGNING)"
if [ "$SIGNING" = es256 ]; then
  # The same identity and settings as k8s/components/signing-es256. The key file is copied into the
  # created container rather than bind-mounted (a VM's Docker may not see this path), so the clock
  # starts before `docker create` and the cold start includes the copy of a 3 KB file.
  identity="$BENCH/build/signing/x9-bench-signing.p12"
  [ -f "$identity" ] || die "SIGNING=es256 but prepare.sh made no signing identity"
  APP_ENV+=(-e X9_CERTIFICATE_JWKALGORITHM=ES256
            -e X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION=file:/tmp/x9-bench-signing.p12
            -e X9_CERTIFICATE_PRIVATEKEYSTORE_TYPE=PKCS12
            -e X9_CERTIFICATE_PRIVATEKEYSTORE_PASSWORD=x9test123
            -e X9_CERTIFICATE_PRIVATEKEYSTORE_PRIVATEKEYPASSWORD=x9test123
            -e X9_CERTIFICATE_PRIVATEKEYSTORE_ALIAS=x9-test-payer-ec)
  start=$(now_ms)
  docker create --name "$APP" --network "$NET" --cpus "$CPUS" --memory "$MEMORY" -p 127.0.0.1::8080 \
    "${APP_ENV[@]}" "$IMAGE" >/dev/null
  docker cp "$identity" "$APP:/tmp/x9-bench-signing.p12" >/dev/null
  docker start "$APP" >/dev/null
else
  start=$(now_ms)
  docker run -d --name "$APP" --network "$NET" --cpus "$CPUS" --memory "$MEMORY" -p 127.0.0.1::8080 \
    "${APP_ENV[@]}" "$IMAGE" >/dev/null
fi
port="$(docker port "$APP" 8080/tcp | head -1 | sed 's/.*://')"
up=""
for _ in $(seq 1 3000); do
  if curl -fs "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q '"UP"'; then
    up=$(now_ms); break
  fi
  sleep 0.05
done
[ -n "$up" ] || { docker logs --tail 60 "$APP" >&2; die "/actuator/health never answered UP"; }
cold=$((up - start))
log "S7 $BUILD: UP after ${cold} ms; idling ${IDLE_SECONDS} s"

mem_bytes() {
  docker stats --no-stream --format '{{.MemUsage}}' "$APP" | python3 -I -c '
import re, sys
value, unit = re.match(r"\s*([\d.]+)\s*([KMGT]?i?B)", sys.stdin.read()).groups()
scale = {"B": 1, "KiB": 1024, "MiB": 1024**2, "GiB": 1024**3, "TiB": 1024**4, "kB": 1000, "KB": 1000, "MB": 1000**2, "GB": 1000**3}
print(int(float(value) * scale[unit]))'
}
echo "seconds_idle,memory_bytes" >"$OUT/idle-memory.csv"
for ((t = 0; t < IDLE_SECONDS; t += 30)); do
  echo "$t,$(mem_bytes)" >>"$OUT/idle-memory.csv"
  sleep $((IDLE_SECONDS - t < 30 ? IDLE_SECONDS - t : 30))
done
resting="$(mem_bytes)"
echo "$IDLE_SECONDS,$resting" >>"$OUT/idle-memory.csv"
log "S7 $BUILD: resting memory $((resting / 1024 / 1024)) MiB"

python3 -I - "$OUT/s7.json" <<PY
import json, sys
image = json.loads('''$(image_json "$IMAGE")''')
json.dump({"scenario": "S7", "build": "$BUILD", "image": image,
           "sizeBytes": $size, "compressedSizeBytes": $compressed,
           "coldStartToHealthUpMs": $cold, "restingMemoryBytes": $resting, "idleSeconds": $IDLE_SECONDS,
           "limits": {"cpus": "$CPUS", "memory": "$MEMORY"}, "signing": "$SIGNING",
           "method": "docker run to the first 200 UP from /actuator/health, polled every 50 ms, database "
                     "already up; resting memory from docker stats (usage minus inactive file cache) "
                     "after the idle period, no request in between"},
          open(sys.argv[1], "w"), indent=1)
PY
cat "$OUT/s7.json"
