# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Shared by collect.sh and footprint.sh. Sourced, never run.
#
# What is NEVER written into results (S5.1-AC6, NFR2): the host's name, the kube context's name or
# server, node names, and any IP address. The environment is described by CPU, architecture and
# virtualisation layers only; scrub_check refuses a results directory that holds an IPv4 address.

BENCH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$BENCH/../.." && pwd)"
SHA="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"

NAMESPACE="${NAMESPACE:-x9-bench}"
OVERLAY="${OVERLAY:-}"                      # "" = the plan §9.2 limits; "small" = smoke only
JAVA_IMAGE="${JAVA_IMAGE:-x9-qrcode:git-$SHA}"
GO_IMAGE="${GO_IMAGE:-x9-qrcode-go:git-$SHA}"
LIQUIBASE_IMAGE="${LIQUIBASE_IMAGE:-x9-qrcode-liquibase:git-$SHA}"
# Resolved once per campaign: footprint.sh, run later by collect.sh, must use the same images even
# if HEAD moves while the campaign runs.
export JAVA_IMAGE GO_IMAGE LIQUIBASE_IMAGE
K6_IMAGE="grafana/k6:2.4.0"
PUBLIC_IMAGES=("mongo:7.0.45" "postgres:16" "python:3.13.9-slim" "$K6_IMAGE")

log() { printf '%s  %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() { log "FAIL  $*"; exit 1; }
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }

kc() {
  [ -n "${KUBE_CONTEXT:-}" ] || die "KUBE_CONTEXT is not set: name the kube context to run in (it is never defaulted)"
  kubectl --context "$KUBE_CONTEXT" "$@"
}
kn() { kc -n "$NAMESPACE" "$@"; }

image_of() { case "$1" in java-mongo) echo "$JAVA_IMAGE" ;; go-postgres) echo "$GO_IMAGE" ;; *) die "unknown build $1" ;; esac; }

# The manifests of one part (java-mongo, go-postgres, k6), with this run's images in place.
render() {
  local part="$1" dir="$BENCH/k8s/$1"
  [ -n "$OVERLAY" ] && dir="$BENCH/k8s/overlays/$OVERLAY/$1"
  kubectl kustomize "$dir" |
    sed -e "s|image: APP_IMAGE|image: $(image_of "${2:-java-mongo}")|" \
        -e "s|image: LIQUIBASE_IMAGE|image: $LIQUIBASE_IMAGE|"
}

# ─── images ──────────────────────────────────────────────────────────────────────────────────
# The cluster runs only images it already holds (imagePullPolicy Never for ours): nothing is
# pushed to any registry. IMAGE_LOADER is a command that reads a `docker save` tar on stdin and
# imports it into the cluster's container runtime; empty means the cluster already sees this
# Docker's images (Colima with the docker runtime and Kubernetes enabled). See README "Images".

load_images() {
  local image
  for image in "$@"; do
    docker image inspect "$image" >/dev/null 2>&1 || die "image $image is not built (build-images.sh)"
    case "$image" in *:latest) die "refusing to load $image: a :latest tag would move the cluster's latest" ;; esac
    if [ -n "${IMAGE_LOADER:-}" ]; then
      log "loading $image into the cluster runtime"
      docker save "$image" | eval "$IMAGE_LOADER" >/dev/null
    fi
  done
}

preload_public_images() {
  [ "${PRELOAD_PUBLIC_IMAGES:-0}" = 1 ] || return 0
  local image
  for image in "${PUBLIC_IMAGES[@]}"; do
    docker image inspect "$image" >/dev/null 2>&1 || docker pull -q "$image" >/dev/null
  done
  load_images "${PUBLIC_IMAGES[@]}"
}

# image_size IMAGE sizeBytes|compressedSizeBytes: the image's unpacked or compressed size, read from
# `docker save` (others/scripts/image-size.py). Never {{.Size}}: on Docker's containerd image store
# it is the compressed content or the disk usage, depending on the version, not the unpacked layers.
image_size() { python3 -I "$ROOT/others/scripts/image-size.py" "$1" --field "$2"; }

image_json() {
  local size
  size="$(image_size "$1" sizeBytes)"
  docker image inspect "$1" --format \
    '{"image":"{{index .RepoTags 0}}","id":"{{.Id}}","revision":"{{index .Config.Labels "org.opencontainers.image.revision"}}","version":"{{index .Config.Labels "org.opencontainers.image.version"}}","platform":"{{.Os}}/{{.Architecture}}","sizeBytes":'"$size"'}'
}

# ─── the namespace: one system under test at a time, an empty database volume each run ────────

fresh_namespace() {
  teardown
  kc create namespace "$NAMESPACE" >/dev/null
  kn create configmap x9-bench-crl --from-file="$BENCH/build/crl/test-ca.crl" >/dev/null
  kn create configmap x9-bench-truststore \
    --from-file="$BENCH/build/truststore/x9-blackbox-truststore.jks" \
    --from-file="$BENCH/build/truststore/x9-bench-truststore.pem" >/dev/null
  kn create configmap x9-bench-db-bootstrap \
    --from-file="$ROOT/db/local/bootstrap.sql" \
    --from-file="$BENCH/k8s/go-postgres/initdb-bootstrap.sh" >/dev/null
  kn create configmap x9-bench-payers --from-file="$BENCH/build/payers.json" >/dev/null
  kn create configmap x9-bench-k6 \
    --from-file="$BENCH/k6/s1-create.js" --from-file="$BENCH/k6/s2-cycle.js" \
    --from-file=lib.jws.js="$BENCH/k6/lib/jws.js" --from-file=lib.x9.js="$BENCH/k6/lib/x9.js" >/dev/null
}

teardown() {
  if kc get namespace "$NAMESPACE" >/dev/null 2>&1; then
    log "deleting namespace $NAMESPACE (and its database volume)"
    kc delete namespace "$NAMESPACE" --wait=true --timeout=300s >/dev/null
  fi
}

# Deploys a build: the database, (Go) the schema Job, then the app; waits for each.
deploy() {
  local build="$1" manifests
  manifests="$(render "$build" "$build")"
  log "deploying $build: database"
  kn apply -l x9-bench/stage=database -f - <<<"$manifests" >/dev/null
  kn rollout status "statefulset/$(db_name "$build")" --timeout=600s >/dev/null ||
    die "the database never became ready"
  if [ "$build" = go-postgres ]; then
    log "deploying $build: schema (Liquibase)"
    kn apply -l x9-bench/stage=schema -f - <<<"$manifests" >/dev/null
    kn wait --for=condition=Complete job/x9-db-init --timeout=600s >/dev/null || {
      kn logs job/x9-db-init --tail=50 >&2 || true
      die "the schema Job did not complete"
    }
  fi
  log "deploying $build: app $(image_of "$build")"
  kn apply -l x9-bench/stage=app -f - <<<"$manifests" >/dev/null
  kn rollout status deployment/x9-qrcode --timeout=600s >/dev/null || {
    kn logs deployment/x9-qrcode -c app --tail=80 >&2 || true
    die "the app never became ready"
  }
  reachable
}

# A rolled-out Deployment is not yet a reachable Service: the endpoints and the node's routing
# follow a moment later. Without a warm-up (Go's default) k6 would start inside that moment and
# its first request, or S2's setup(), would be refused. Wait until the Service answers from inside
# the cluster, the way k6 will reach it; the probe pod is gone before anything is measured.
reachable() {
  kn delete pod x9-reach --ignore-not-found --wait=true >/dev/null 2>&1 || true
  kn run x9-reach --image=python:3.13.9-slim --image-pull-policy=IfNotPresent --restart=Never \
    --command -- python -c '
import time, urllib.request
deadline = time.time() + 120
while True:
    try:
        for path in ("/actuator/health", "/pub/.well-known/jwks"):
            urllib.request.urlopen("http://x9-qrcode:8080" + path, timeout=2).read()
        break
    except Exception:
        if time.time() > deadline:
            raise SystemExit("the Service never answered")
        time.sleep(0.5)
' >/dev/null
  kn wait --for=jsonpath='{.status.phase}'=Succeeded pod/x9-reach --timeout=180s >/dev/null || {
    kn logs pod/x9-reach >&2 || true
    die "the app is ready but its Service does not answer inside the cluster"
  }
  kn delete pod x9-reach --wait=true >/dev/null
  log "the Service answers inside the cluster"
}

db_name() { case "$1" in java-mongo) echo mongo ;; *) echo postgres ;; esac; }
db_pod() { kn get pod -l x9-bench/role=database -o jsonpath='{.items[0].metadata.name}'; }

# The durability settings as the database reports them (S5.1-AC4, parity).
record_durability() {
  local build="$1" out="$2"
  if [ "$build" = java-mongo ]; then
    kn exec "$(db_pod)" -c database -- mongosh --quiet --eval '
      const rw = db.adminCommand({getDefaultRWConcern: 1});
      print(JSON.stringify({database: "mongodb", version: db.version(),
        writeConcernMajorityJournalDefault: rs.conf().writeConcernMajorityJournalDefault,
        defaultWriteConcern: rw.defaultWriteConcern,
        serviceUri: "w=majority&journal=true"}))' >"$out"
  else
    kn exec "$(db_pod)" -c database -- psql -XAtq -U postgres -d postgres -c "
      SELECT json_build_object('database', 'postgresql', 'version', current_setting('server_version'),
        'synchronous_commit', current_setting('synchronous_commit'), 'fsync', current_setting('fsync'),
        'wal_sync_method', current_setting('wal_sync_method'),
        'max_connections', current_setting('max_connections')::int)" >"$out"
  fi
}

# What the database holds after a run (plan §9.4: on-disk size per 1,000 payments, connections).
record_db_after() {
  local build="$1" out="$2"
  if [ "$build" = java-mongo ]; then
    kn exec "$(db_pod)" -c database -- mongosh --quiet x9-qrcode --eval '
      const s = db.stats(1), status = db.serverStatus();
      const counts = {};
      db.getCollectionNames().forEach(c => counts[c] = db[c].countDocuments({}));
      print(JSON.stringify({dataSize: s.dataSize, storageSize: s.storageSize, indexSize: s.indexSize,
        totalSize: s.totalSize, documents: counts, connections: status.connections}))' >"$out"
  else
    kn exec "$(db_pod)" -c database -- psql -XAtq -U postgres -d postgres -c "
      SELECT json_build_object('databaseSize', pg_database_size('postgres'),
        'tables', (SELECT json_object_agg(c.relname, json_build_object(
                     'rows', c.reltuples::bigint, 'totalBytes', pg_total_relation_size(c.oid)))
                   FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                   WHERE n.nspname = 'x9_qrcode' AND c.relkind = 'r'),
        'connections', (SELECT count(*) FROM pg_stat_activity WHERE datname = 'postgres'))" >"$out"
  fi
}

# After the excluded warm-up: the data goes, the indexes and the running service stay.
clear_data() {
  local build="$1"
  log "clearing the warm-up's data"
  if [ "$build" = java-mongo ]; then
    kn exec "$(db_pod)" -c database -- mongosh --quiet x9-qrcode --eval '
      ["qrcodes", "qrcode_history", "payment_events"].forEach(c => db[c].deleteMany({}))' >/dev/null
  else
    kn exec "$(db_pod)" -c database -- psql -XAtq -U postgres -d postgres -c "
      TRUNCATE x9_qrcode.qr_code_audit_log, x9_qrcode.qr_code_notification, x9_qrcode.payment_event,
               x9_qrcode.qr_code CASCADE" >/dev/null
  fi
}

# Requests, limits and image digests of every container in the namespace (no node names).
record_resources() {
  kn get pods -o json | python3 -I -c '
import json, sys
pods = json.load(sys.stdin)["items"]
out = []
for p in pods:
    statuses = {s["name"]: s for s in p["status"].get("initContainerStatuses", []) + p["status"].get("containerStatuses", [])}
    for c in p["spec"].get("initContainers", []) + p["spec"]["containers"]:
        s = statuses.get(c["name"], {})
        out.append({"pod": p["metadata"]["labels"].get("app.kubernetes.io/name"), "container": c["name"],
                    "image": c["image"], "imageID": s.get("imageID"), "resources": c.get("resources", {}),
                    "env": {e["name"]: e.get("value") for e in c.get("env", []) if e["name"] in
                            ("GOMEMLIMIT", "SPRING_DATA_MONGODB_URI", "DATABASE_POOL_WRITES", "DATABASE_POOL_READS",
                             "X9_TESTING_CLOCKCONTROL", "PROFILE", "STAGES", "SCENARIO")}})
print(json.dumps(out, indent=1))'
}

# ─── per-container CPU and memory every 5 s (plan §9.4), from the kubelet's summary API ────────

start_sampler() {
  local out="$1" node
  node="$(kn get pod -l x9-bench/role=app -o jsonpath='{.items[0].spec.nodeName}')"
  echo "epoch_ms,role,container,cpu_millicores,memory_working_set_bytes" >"$out"
  (
    while true; do
      kc get --raw "/api/v1/nodes/$node/proxy/stats/summary" 2>/dev/null | python3 -I -c '
import json, sys, time
ns, now = sys.argv[1], int(time.time() * 1000)
roles = (("x9-k6", "k6"), ("x9-qrcode", "app"), ("mongo", "database"), ("postgres", "database"),
         ("x9-db-init", "schema"))
for pod in json.load(sys.stdin).get("pods", []):
    ref = pod["podRef"]
    if ref["namespace"] != ns:
        continue
    role = next((r for prefix, r in roles if ref["name"].startswith(prefix)), "other")
    for c in pod.get("containers", []):
        cpu = (c.get("cpu") or {}).get("usageNanoCores")
        mem = (c.get("memory") or {}).get("workingSetBytes")
        print("%d,%s,%s,%s,%s" % (now, role, c["name"], "" if cpu is None else round(cpu / 1e6, 1),
                                  "" if mem is None else mem))
' "$NAMESPACE" >>"$out" || true
      sleep 5
    done
  ) &
  SAMPLER_PID=$!
}

stop_sampler() {
  if [ -n "${SAMPLER_PID:-}" ]; then
    kill "$SAMPLER_PID" 2>/dev/null || true
    wait "$SAMPLER_PID" 2>/dev/null || true
  fi
  SAMPLER_PID=""
}

# ─── k6 ──────────────────────────────────────────────────────────────────────────────────────

# run_k6 <script> <profile> <stages> <out-dir>: one k6 Job, its /out copied to <out-dir>.
run_k6() {
  local script="$1" profile="$2" stages="$3" out="$4" pod code
  mkdir -p "$out"
  kn delete job x9-k6 --ignore-not-found --wait=true >/dev/null
  render k6 | sed -e "s|@SCENARIO@|$script|" -e "s|@PROFILE@|$profile|" -e "s|@STAGES@|$stages|" |
    kn apply -f - >/dev/null
  for _ in $(seq 1 60); do [ -n "$(kn get pod -l job-name=x9-k6 -o name)" ] && break; sleep 1; done
  kn wait --for=condition=Ready pod -l job-name=x9-k6 --timeout=300s >/dev/null || die "the k6 pod never started"
  pod="$(kn get pod -l job-name=x9-k6 -o jsonpath='{.items[0].metadata.name}')"
  log "k6 $script ($profile${stages:+, $stages}) running in $pod"
  until kn exec "$pod" -c k6 -- test -f /out/done 2>/dev/null; do sleep 5; done
  kn cp -c k6 "$pod:/out" "$out" >/dev/null 2>&1 || die "could not copy k6 output"
  kn exec "$pod" -c k6 -- touch /out/collected
  code="$(cat "$out/exit-code" 2>/dev/null || echo unknown)"
  case "$code" in
    0) log "k6 exited 0" ;;
    99) log "k6 exited 99: a threshold was crossed (recorded, not fatal)" ;;
    *) log "k6 exited $code" ;;
  esac
  kn wait --for=condition=Complete job/x9-k6 --timeout=120s >/dev/null || true
  kn delete job x9-k6 --wait=true >/dev/null
  case "$code" in 0 | 99) return 0 ;; *) return 1 ;; esac
}

# ─── the environment, generically (S5.1-AC6) ─────────────────────────────────────────────────

record_environment() {
  local out="$1" cpu host_cpus host_mem
  case "$(uname -s)" in
    Darwin) cpu="$(sysctl -n machdep.cpu.brand_string 2>/dev/null)"; host_cpus="$(sysctl -n hw.ncpu)"
            host_mem="$(sysctl -n hw.memsize)" ;;
    *) cpu="$(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | sed 's/^ //')"
       host_cpus="$(nproc)"; host_mem="$(($(grep MemTotal /proc/meminfo | awk '{print $2}') * 1024))" ;;
  esac
  {
    kc version -o json 2>/dev/null | python3 -I -c 'import json,sys; print(json.dumps({"kubernetes": json.load(sys.stdin)["serverVersion"]["gitVersion"]}))'
    kc get nodes -o json | python3 -I -c '
import json, sys
nodes = json.load(sys.stdin)["items"]
print(json.dumps({"nodes": [{"architecture": n["status"]["nodeInfo"]["architecture"],
  "kernelVersion": n["status"]["nodeInfo"]["kernelVersion"], "osImage": n["status"]["nodeInfo"]["osImage"],
  "containerRuntime": n["status"]["nodeInfo"]["containerRuntimeVersion"],
  "kubelet": n["status"]["nodeInfo"]["kubeletVersion"],
  "cpu": n["status"]["capacity"]["cpu"], "memory": n["status"]["capacity"]["memory"]} for n in nodes]}))'
  } >"$out.parts"
  python3 -I - "$out.parts" "$out" "$cpu" "$host_cpus" "$host_mem" "$(uname -sm)" "$OVERLAY" "${BENCH_VIRTUALISATION:-}" <<'PY'
import json, sys
parts, out, cpu, cpus, mem, os_arch, overlay, virt = sys.argv[1:9]
env = {}
for line in open(parts):
    env.update(json.loads(line))
env.update({"host": {"cpu": cpu, "logicalCpus": int(cpus), "memoryBytes": int(mem), "os": os_arch},
            "virtualisation": virt or "not stated (set BENCH_VIRTUALISATION)",
            "limits": "overlay " + overlay + " (SMOKE ONLY, not a measurement)" if overlay else
                      "plan 9.2: app 2 vCPU / 2 GiB, database 2 vCPU / 2 GiB, k6 2 vCPU",
            "k6": "grafana/k6:2.4.0"})
json.dump(env, open(out, "w"), indent=1)
PY
  rm -f "$out.parts"
}

# Refuses a results directory that holds an IPv4 address (S5.1-AC6): text files and gzipped ones.
scrub_check() {
  local dir="$1" hits
  hits="$( { grep -rIlE '(^|[^0-9.])([0-9]{1,3}\.){3}[0-9]{1,3}([^0-9.]|$)' "$dir" 2>/dev/null || true
             find "$dir" -name '*.gz' -print0 | while IFS= read -r -d '' f; do
               gzip -dc "$f" | grep -qE '(^|[^0-9.])([0-9]{1,3}\.){3}[0-9]{1,3}([^0-9.]|$)' && echo "$f"
             done; } | sort -u)"
  if [ -n "$hits" ]; then
    log "FAIL  an IP address is in these files; do not commit them until it is removed:"
    printf '        %s\n' $hits >&2
    return 1
  fi
}
