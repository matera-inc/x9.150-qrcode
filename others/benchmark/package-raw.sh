#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Packages one campaign's raw k6 output for its GitHub release (README, "Publishing a campaign").
# The summaries and a SHA256SUMS per run are committed; the compressed raw files are not (they are
# git-ignored) and go on the release instead, one release per campaign.
#
#   CAMPAIGN=<campaign id> others/benchmark/package-raw.sh
#
# For every run of the campaign, results/<build>/<campaign>-r<n>/:
#   1. refuses the run if an IPv4 address or this machine's host name is anywhere in it;
#   2. compresses each raw k6 output (raw.ndjson → raw.ndjson.gz; k6 usually writes it gzipped);
#   3. copies each to results/release/<campaign>/ under a name unique across the campaign,
#      <build>_<campaign>-r<n>_<path with / as _>, e.g. java-mongo_2026-11-15T1200Z-r1_s1_raw.ndjson.gz;
#   4. writes SHA256SUMS in the run's directory: one line per raw file, by that release asset name.
# Run again, it rewrites the same files: nothing is appended twice.
#
# Environment:
#   CAMPAIGN      the campaign id (required): the <campaign> in results/<build>/<campaign>-r<n>/
#   RESULTS_DIR   default others/benchmark/results, as collect.sh
#   SKIP_HOSTNAME_CHECK=1   skip step 1's host name check only (a host name that is a common word)
set -euo pipefail
source "$(dirname "$0")/lib.sh"

[ -n "${CAMPAIGN:-}" ] || die "CAMPAIGN is not set: name the campaign to package"
RESULTS="${RESULTS_DIR:-$BENCH/results}"
RELEASE="$RESULTS/release/$CAMPAIGN"

if command -v sha256sum >/dev/null; then sha256() { sha256sum "$@"; }
elif command -v shasum >/dev/null; then sha256() { shasum -a 256 "$@"; }
else die "sha256sum or shasum is needed"; fi

# The machine's own names, matched as whole words, ignoring case, in text and in gzipped files.
hostname_check() {
  local dir="$1" names=() name hits=""
  [ "${SKIP_HOSTNAME_CHECK:-0}" = 1 ] && return 0
  for name in "$(hostname 2>/dev/null || true)" "$(hostname -s 2>/dev/null || true)"; do
    [ -n "$name" ] && [ "$name" != localhost ] && names+=("$name")
  done
  [ "${#names[@]}" -gt 0 ] || return 0
  for name in "${names[@]}"; do
    hits+="$(grep -rIliwF -- "$name" "$dir" 2>/dev/null || true)"$'\n'
    while IFS= read -r -d '' f; do
      gzip -dc "$f" | grep -qiwF -- "$name" && hits+="$f"$'\n'
    done < <(find "$dir" -name '*.gz' -print0)
  done
  hits="$(printf '%s' "$hits" | sed '/^$/d' | sort -u)"
  if [ -n "$hits" ]; then
    log "FAIL  this machine's host name is in these files; remove it before publishing:"
    printf '%s\n' "$hits" | sed 's/^/        /' >&2
    return 1
  fi
}

shopt -s nullglob
runs=("$RESULTS"/*/"$CAMPAIGN"-r*/)
[ "${#runs[@]}" -gt 0 ] || die "no runs of campaign $CAMPAIGN under $RESULTS"

# Every run is checked before anything is written, so a refusal leaves no half-staged release.
for run_dir in "${runs[@]}"; do
  scrub_check "${run_dir%/}" || die "${run_dir%/} holds an IP address"
  hostname_check "${run_dir%/}" || die "${run_dir%/} holds this machine's host name"
done

rm -rf "$RELEASE"
mkdir -p "$RELEASE"

for run_dir in "${runs[@]}"; do
  run_dir="${run_dir%/}"
  run="$(basename "$run_dir")"
  build="$(basename "$(dirname "$run_dir")")"

  while IFS= read -r -d '' raw; do
    gzip -n -9 -f "$raw"
  done < <(find "$run_dir" -type f -name 'raw.ndjson' -print0)

  sums="$run_dir/SHA256SUMS"
  : >"$sums.tmp"
  while IFS= read -r -d '' raw; do
    rel="${raw#"$run_dir"/}"
    asset="${build}_${run}_${rel//\//_}"
    cp "$raw" "$RELEASE/$asset"
    (cd "$RELEASE" && sha256 "$asset") >>"$sums.tmp"
  done < <(find "$run_dir" -type f -name 'raw.ndjson.gz' -print0)
  if [ -s "$sums.tmp" ]; then
    sort -k2 "$sums.tmp" >"$sums"
    log "$build/$run: $(wc -l <"$sums" | tr -d ' ') raw file(s) → SHA256SUMS"
  else
    rm -f "$sums"
    log "$build/$run: no raw k6 output (S7 only, or not run)"
  fi
  rm -f "$sums.tmp"
done

log "release assets for $CAMPAIGN: ${RELEASE#"$ROOT"/}/ (git-ignored; README, \"Publishing a campaign\")"
