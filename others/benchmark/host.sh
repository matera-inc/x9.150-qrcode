#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Prepares a macOS machine to run a campaign: a dedicated Colima profile with Kubernetes on the
# docker runtime, so the images build-images.sh makes are the images the cluster runs, and nothing
# is loaded, pulled by the cluster or pushed anywhere. Any machine, any number of times.
#
#   others/benchmark/host.sh check     tools present, versions, the machine's size
#   others/benchmark/host.sh up        create or start the profile, then verify the cluster
#   others/benchmark/host.sh env       print the variables build-images.sh and collect.sh need
#   others/benchmark/host.sh down      stop the profile (kept for the next campaign)
#   others/benchmark/host.sh delete    delete the profile and its disk
#
# Typical campaign:
#   others/benchmark/host.sh up
#   eval "$(others/benchmark/host.sh env)"
#   others/benchmark/build-images.sh
#   SEQUENCE="J G J G J G" GO_SERVES_API=1 others/benchmark/collect.sh
#   others/benchmark/host.sh down
#
# Environment (all optional):
#   BENCH_PROFILE   Colima profile name, default "bench" (kube context "colima-<profile>")
#   BENCH_CPUS      VM vCPUs, default 8      (the pods need 6 vCPU allocatable on one node)
#   BENCH_MEMORY    VM memory in GiB, default 10  (the pods need about 6.2 GiB allocatable)
#   BENCH_DISK      VM disk in GiB, default 60
#   ALLOW_OTHER_VMS=1  start even while another Colima profile runs (it shares the machine's CPUs
#                      and memory, so the campaign is then a rehearsal, never a measurement)
#
# Nothing here writes the machine's name, the context's server or any address into results.
set -euo pipefail

PROFILE="${BENCH_PROFILE:-bench}"
CPUS="${BENCH_CPUS:-8}"
MEMORY="${BENCH_MEMORY:-10}"
DISK="${BENCH_DISK:-60}"
CONTEXT="colima-$PROFILE"
[ "$PROFILE" = default ] && CONTEXT="colima"
SOCKET="$HOME/.colima/$PROFILE/docker.sock"

say() { printf '%s\n' "$*" >&2; }
die() { say "FAIL  $*"; exit 1; }

check() {
  [ "$(uname -s)" = Darwin ] || die "this script sets up Colima on macOS; on Linux use any Kubernetes 1.29+ (README)"
  local missing=() tool
  for tool in colima docker kubectl python3 openssl keytool java mvn curl gzip git; do
    command -v "$tool" >/dev/null || missing+=("$tool")
  done
  if [ ${#missing[@]} -gt 0 ]; then
    say "missing: ${missing[*]}"
    say "install with Homebrew: brew install colima docker kubectl python openssl git maven && brew install --cask temurin@25"
    exit 1
  fi
  local jv
  jv="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
  [ "${jv:-0}" -ge 25 ] || die "Java 25 or later is needed to build the Java image (found ${jv:-none})"
  local cpus mem_gib
  cpus="$(sysctl -n hw.ncpu)"; mem_gib=$(( $(sysctl -n hw.memsize) / 1073741824 ))
  say "machine: $(sysctl -n machdep.cpu.brand_string), $cpus logical CPUs, $mem_gib GiB; colima $(colima version | head -1 | awk '{print $3}')"
  [ "$CPUS" -lt "$cpus" ] || die "BENCH_CPUS=$CPUS leaves nothing for macOS on a $cpus-CPU machine"
  [ $(( MEMORY + 4 )) -le "$mem_gib" ] || die "BENCH_MEMORY=$MEMORY GiB leaves under 4 GiB for macOS on a $mem_gib GiB machine"
}

others_running() {
  colima list --json 2>/dev/null | python3 -I -c '
import json, sys
names = [p["name"] for line in sys.stdin if line.strip() for p in [json.loads(line)]
         if p.get("status") == "Running" and p["name"] != sys.argv[1]]
print(" ".join(names))' "$PROFILE"
}

up() {
  check
  local others
  others="$(others_running)"
  if [ -n "$others" ] && [ "${ALLOW_OTHER_VMS:-0}" != 1 ]; then
    die "other Colima profiles are running ($others): stop them (colima stop -p <name>) or set ALLOW_OTHER_VMS=1 for a rehearsal"
  fi
  if colima status -p "$PROFILE" >/dev/null 2>&1; then
    say "profile $PROFILE is already running"
  else
    say "starting Colima profile $PROFILE: $CPUS vCPU, $MEMORY GiB, $DISK GiB disk, Kubernetes on docker"
    local previous
    previous="$(kubectl config current-context 2>/dev/null || true)"
    local previous_docker
    previous_docker="$(DOCKER_HOST="" docker context show 2>/dev/null || true)"
    colima start -p "$PROFILE" --cpu "$CPUS" --memory "$MEMORY" --disk "$DISK" \
      --runtime docker --kubernetes --vm-type vz >&2
    # Colima makes its context the current one; the campaign never relies on that (KUBE_CONTEXT),
    # so give the operator's kubectl back the context it had.
    if [ -n "$previous" ] && [ "$previous" != "$CONTEXT" ]; then
      kubectl config use-context "$previous" >/dev/null
    fi
    # Colima switches the Docker CLI's context too; the campaign uses DOCKER_HOST (env), so give
    # the operator's Docker back the context it had, or every other tool talks to this VM.
    if [ -n "$previous_docker" ] && [ "$previous_docker" != "$CONTEXT" ]; then
      DOCKER_HOST="" docker context use "$previous_docker" >/dev/null 2>&1 || true
    fi
  fi
  verify
}

verify() {
  kubectl --context "$CONTEXT" wait --for=condition=Ready node --all --timeout=180s >/dev/null
  kubectl --context "$CONTEXT" version -o json | python3 -I -c '
import json, re, sys
v = json.load(sys.stdin)["serverVersion"]
major, minor = int(v["major"]), int(re.sub(r"\D", "", v["minor"]))
assert (major, minor) >= (1, 29), f"Kubernetes {major}.{minor}: native sidecars need 1.29 or later"
print("kubernetes " + v["gitVersion"] + ": ok", file=sys.stderr)'
  kubectl --context "$CONTEXT" get nodes -o json | python3 -I -c '
import json, sys
def cpu(q): return int(q[:-1]) / 1000 if q.endswith("m") else float(q)
def mem(q):
    units = {"Ki": 2**10, "Mi": 2**20, "Gi": 2**30}
    for u, f in units.items():
        if q.endswith(u): return int(q[:-2]) * f
    return int(q)
nodes = json.load(sys.stdin)["items"]
best = max(nodes, key=lambda n: cpu(n["status"]["allocatable"]["cpu"]))
c, m = cpu(best["status"]["allocatable"]["cpu"]), mem(best["status"]["allocatable"]["memory"]) / 2**30
assert c >= 6 and m >= 6.3, f"a node has {c} vCPU / {m:.1f} GiB allocatable; the campaign needs 6 vCPU / 6.3 GiB"
print(f"allocatable on one node: {c:g} vCPU, {m:.1f} GiB: ok", file=sys.stderr)'
  # k3s installs its local-path StorageClass a little after the node is Ready.
  local tries=0
  until kubectl --context "$CONTEXT" get storageclass -o name 2>/dev/null | grep -q . || [ $tries -ge 60 ]; do
    tries=$((tries + 1)); sleep 2
  done
  kubectl --context "$CONTEXT" get storageclass -o json | python3 -I -c '
import json, sys
d = [s["metadata"]["name"] for s in json.load(sys.stdin)["items"]
     if s["metadata"].get("annotations", {}).get("storageclass.kubernetes.io/is-default-class") == "true"]
assert d, "no default StorageClass: the databases claim their volume from it"
print("default StorageClass: ok", file=sys.stderr)'
  DOCKER_HOST="unix://$SOCKET" docker info >/dev/null || die "the profile's Docker does not answer"
  say "ready. Next: eval \"\$(others/benchmark/host.sh env)\""
}

env_vars() {
  local note="" size
  # The VM as it actually runs, not as this shell's variables say it would be created.
  size="$(colima list --json 2>/dev/null | python3 -I -c '
import json, sys
for line in sys.stdin:
    p = json.loads(line) if line.strip() else {}
    if p.get("name") == sys.argv[1]:
        print(str(p["cpus"]) + " vCPU / " + str(p["memory"] // 2**30) + " GiB")' "$PROFILE")"
  [ -n "$size" ] || die "profile $PROFILE is not running: others/benchmark/host.sh up"
  [ -n "$(others_running)" ] && note=", other VMs running (rehearsal, not a measurement)"
  cat <<EOF
export DOCKER_HOST="unix://$SOCKET"
export KUBE_CONTEXT="$CONTEXT"
export IMAGE_LOADER=""
export BENCH_VIRTUALISATION="Linux VM (Colima, Apple Virtualization.framework) on macOS, k3s on Docker, $size$note"
EOF
}

case "${1:-}" in
  check) check ;;
  up) up ;;
  verify) verify ;;
  env) env_vars ;;
  down) colima stop -p "$PROFILE" ;;
  delete) colima delete -p "$PROFILE" --force ;;
  *) sed -n '9,16p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
esac
