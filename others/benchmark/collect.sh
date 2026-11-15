#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Runs a benchmark campaign (S5.1-AC5): the interleaved sequence J-G-J-G-J-G by default, each run
# S1, S2 and S7, each scenario on a fresh namespace with an empty database volume, one system under
# test at a time. Results go to results/<build>/<campaign>-r<n>/ with the raw k6 output, the image
# tags (git-<sha>) and revisions, the versions and the resource settings.
#
#   KUBE_CONTEXT=<your context> others/benchmark/collect.sh
#
# Environment (all optional except KUBE_CONTEXT):
#   KUBE_CONTEXT       the kube context to run in. Never defaulted, never written into results.
#   SEQUENCE           "J G J G J G" (default). J = java-mongo, G = go-postgres. "J J J" for the
#                      Java baseline alone (Story 5.2).
#   SCENARIOS          "s1 s2 s7" (default)
#   GO_SERVES_API      0 (default): the Go build has no API yet (Epics 9-10), so G runs only S7 and
#                      S1/S2 are skipped with a note in the run. Set 1 once it serves the API.
#   PROFILE / STAGES   the k6 load profile (lib/x9.js): readme (default), smoke, or explicit stages
#   JAVA_WARMUP        k6 stages of the Java build's JIT warm-up, excluded from measurement (plan
#                      §9.2): default the `warmup` profile, 2 minutes. "none" to skip.
#   GO_WARMUP          the same for Go: default "none" (the plan warms up the JVM only)
#   IDLE_SECONDS       S7's idle period before resting memory, default 300
#   OVERLAY            "" (default, the plan §9.2 limits) or "small" (SMOKE ONLY: see k8s/overlays)
#   CAMPAIGN           the campaign id, default the UTC start time (2026-11-15T1200Z)
#   IMAGE_LOADER       a command that imports a `docker save` tar from stdin into the cluster's
#                      runtime; empty when the cluster already sees this Docker's images (README)
#   PRELOAD_PUBLIC_IMAGES=1  also pull and load mongo, postgres, python and k6
#   BENCH_VIRTUALISATION     a one-line, generic description of the virtualisation layers
#                            ("Linux VM on a macOS hypervisor, k3s"), recorded as given
#   JAVA_IMAGE / GO_IMAGE / LIQUIBASE_IMAGE   default x9-qrcode[-go|-liquibase]:git-<HEAD's sha>
set -euo pipefail
source "$(dirname "$0")/lib.sh"

SEQUENCE="${SEQUENCE:-J G J G J G}"
SCENARIOS="${SCENARIOS:-s1 s2 s7}"
GO_SERVES_API="${GO_SERVES_API:-0}"
PROFILE="${PROFILE:-readme}"
STAGES="${STAGES:-}"
JAVA_WARMUP="${JAVA_WARMUP:-warmup}"
GO_WARMUP="${GO_WARMUP:-none}"
CAMPAIGN="${CAMPAIGN:-$(date -u +%Y-%m-%dT%H%MZ)}"
RESULTS="${RESULTS_DIR:-$BENCH/results}"

[ -n "${KUBE_CONTEXT:-}" ] || die "KUBE_CONTEXT is not set: name the kube context to run in"
for tool in kubectl docker python3 curl gzip; do command -v "$tool" >/dev/null || die "$tool is needed"; done

log "preparing the test-CA material"
"$BENCH/prepare.sh" >/dev/null

builds=()
for letter in $SEQUENCE; do
  case "$letter" in J) builds+=(java-mongo) ;; G) builds+=(go-postgres) ;; *) die "SEQUENCE has $letter: use J and G" ;; esac
done

log "loading images (nothing is pushed anywhere)"
needed=()
for build in $(printf '%s\n' "${builds[@]}" | sort -u); do
  needed+=("$(image_of "$build")")
  [ "$build" = go-postgres ] && needed+=("$LIQUIBASE_IMAGE")
done
load_images "${needed[@]}"
preload_public_images

warmup_of() { case "$1" in java-mongo) echo "$JAVA_WARMUP" ;; *) echo "$GO_WARMUP" ;; esac; }
warmup_stages() {
  # A profile name (warmup, smoke) or explicit stages ("30s:100,90s:100").
  case "$1" in *:*) echo "warmup $1" ;; *) echo "$1 " ;; esac
}

trap 'stop_sampler; teardown' EXIT

position=0
for build in "${builds[@]}"; do
  position=$((position + 1))
  # This build's run number: how many times it appears in the sequence so far.
  run=$(printf '%s\n' "${builds[@]:0:$position}" | grep -cx "$build")
  run_dir="$RESULTS/$build/$CAMPAIGN-r$run"
  mkdir -p "$run_dir"
  log "══ $build, run $run (position $position of ${#builds[@]}) → ${run_dir#$ROOT/}"

  record_environment "$run_dir/environment.json"
  liquibase_json=null
  [ "$build" = go-postgres ] && liquibase_json="$(image_json "$LIQUIBASE_IMAGE")"
  python3 -I - "$run_dir/images.json" <<PY
import json, sys
json.dump({"build": "$build", "campaign": "$CAMPAIGN", "run": $run, "sequence": "$SEQUENCE",
           "position": $position, "app": json.loads('''$(image_json "$(image_of "$build")")'''),
           "liquibase": json.loads('''$liquibase_json'''),
           "harnessRevision": "$(git -C "$ROOT" rev-parse HEAD)",
           "profile": "$PROFILE", "stages": "$STAGES", "overlay": "${OVERLAY:-none}"},
          open(sys.argv[1], "w"), indent=1)
PY

  for scenario in $SCENARIOS; do
    out="$run_dir/$scenario"
    mkdir -p "$out"
    case "$scenario" in
      s7)
        teardown
        if [ "$OVERLAY" = small ]; then cpus=1 memory=1g; else cpus=2 memory=2g; fi
        CPUS="$cpus" MEMORY="$memory" "$BENCH/footprint.sh" "$build" "$out" >/dev/null
        ;;
      s1 | s2)
        if [ "$build" = go-postgres ] && [ "$GO_SERVES_API" != 1 ]; then
          echo "Not run: the Go build does not serve the API yet (Epics 9-10). Set GO_SERVES_API=1 once it does. The deployment itself was checked: durability.json and resources.json are from it." >"$out/NOT-RUN.txt"
          log "$scenario not run for $build (GO_SERVES_API=0); checking its deployment only"
          fresh_namespace
          deploy "$build"
          record_durability "$build" "$out/durability.json"
          record_resources >"$out/resources.json"
          teardown
          continue
        fi
        script="s1-create.js"; [ "$scenario" = s2 ] && script="s2-cycle.js"
        fresh_namespace
        deploy "$build"
        record_durability "$build" "$out/durability.json"

        warm="$(warmup_of "$build")"
        if [ "$warm" != none ]; then
          read -r warm_profile warm_stages <<<"$(warmup_stages "$warm")"
          started=$(now_ms)
          run_k6 "$script" "$warm_profile" "${warm_stages:-}" "$out/warmup" || die "the warm-up failed"
          clear_data "$build"
          printf '{"excludedFromMeasurement": true, "reason": "JIT warm-up (plan 9.2)", "profile": "%s", "stages": "%s", "elapsedMs": %s, "dataClearedAfter": true}\n' \
            "$warm_profile" "${warm_stages:-}" "$(( $(now_ms) - started ))" >"$out/warmup.json"
        fi

        start_sampler "$out/pod-stats.csv"
        run_k6 "$script" "$PROFILE" "$STAGES" "$out" || log "k6 failed for $scenario; its output is kept"
        stop_sampler
        record_resources >"$out/resources.json"
        record_db_after "$build" "$out/db-after.json"
        kn logs deployment/x9-qrcode -c app --tail=200 >"$out/app-tail.log" 2>&1 || true
        teardown
        ;;
      *) die "unknown scenario $scenario" ;;
    esac
  done
done

# Logs may carry addresses the harness never asked for: redact them in text, then refuse anything left.
find "$RESULTS" -name '*.log' -newer "$BENCH/build/payers.json" -exec \
  sed -i.bak -E 's/(^|[^0-9.])([0-9]{1,3}\.){3}[0-9]{1,3}([^0-9.]|$)/\1<address>\3/g' {} \; -exec rm -f {}.bak \;
for build in $(printf '%s\n' "${builds[@]}" | sort -u); do
  for dir in "$RESULTS/$build/$CAMPAIGN"-r*; do scrub_check "$dir"; done
done
log "campaign $CAMPAIGN done: ${RESULTS#$ROOT/}/<build>/$CAMPAIGN-r*"
