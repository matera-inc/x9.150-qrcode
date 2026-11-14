#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Every check of the Go code, in the order CI's `go` job runs them (.github/workflows/go.yml). It
# runs the same on a laptop; every tool is pinned with a `tool` directive in go.mod and run as
# `go tool`, so nothing needs installing but Go itself (and Docker, for the database tests).
#
#   others/scripts/ci-go.sh                 # every step
#   others/scripts/ci-go.sh lint test       # only those steps
#
# Steps: pin modules vet lint planted nilaway vuln licenses leakcheck test fuzz reproducible sbom
# Outputs go to $GO_CI_OUT (default ./go-ci-out): the SBOM, and the binaries the reproducibility
# check built.

set -euo pipefail
cd "$(dirname "$0")/../.."

MODULE=$(go list -m)
OUT=${GO_CI_OUT:-go-ci-out}
FUZZTIME=${FUZZTIME:-10s}
export GOFLAGS=-mod=readonly

step() { echo; echo "### $*"; }

# Go's own toolchain must be the one go.mod pins, so CI cannot drift to another compiler.
pin() {
  step "pin: the toolchain is the one go.mod pins"
  want=$(sed -nE 's/^toolchain (go[0-9.]+)$/\1/p' go.mod)
  got=$(go env GOVERSION)
  [ "$want" = "$got" ] || { echo "FAIL  go.mod pins $want, this is $got"; return 1; }
  echo "ok  $got"
}

modules() {
  step "modules: go.sum matches what was downloaded, go.mod matches the code"
  go mod verify
  go mod tidy -diff
}

vet() {
  step "go vet"
  go vet ./...
}

lint() {
  step "golangci-lint (.golangci.yml: depguard, forbidigo, gosec, formatters, …)"
  go tool golangci-lint run ./...
}

planted() {
  step "planted violations: each lint rule still fires"
  others/scripts/check-go-lint-planted.sh
}

nilaway() {
  step "nilaway: nil flows across functions"
  go tool nilaway -include-pkgs="$MODULE" ./...
}

vuln() {
  step "govulncheck: vulnerabilities the code can reach"
  go tool govulncheck ./...
}

licenses() {
  step "go-licenses: every linked dependency is under an allowed licence"
  # This module is under the Matera Source License; the check is about what it links.
  go tool go-licenses check ./... \
    --allowed_licenses=Apache-2.0,BSD-2-Clause,BSD-3-Clause,MIT,ISC,Zlib \
    --ignore "$MODULE"
}

# Every package with tests must fail on a leaked goroutine: its TestMain calls leaktest.Main.
leakcheck() {
  step "leak check: every tested package's TestMain calls leaktest.Main"
  local missing=0
  for dir in $(go list -f '{{if or .TestGoFiles .XTestGoFiles}}{{.Dir}}{{end}}' ./...); do
    if ! grep -hE '^[^/]*leaktest\.Main\(' "$dir"/*_test.go >/dev/null 2>&1; then
      echo "FAIL  ${dir#"$PWD"/} has tests but no TestMain calling leaktest.Main"
      missing=1
    fi
  done
  [ "$missing" = 0 ] && echo "ok"
  return "$missing"
}

# -race needs cgo for the test binaries only; shipped binaries are CGO_ENABLED=0. A non-UTC zone
# makes a leaked time.Local fail a test instead of passing on a UTC machine.
test() {
  step "go test -race -shuffle=on -count=1 (TZ=America/Sao_Paulo)"
  TZ=America/Sao_Paulo CGO_ENABLED=1 go test -race -shuffle=on -count=1 ./...
}

fuzz() {
  step "fuzz smoke: every Fuzz target for $FUZZTIME"
  local found=0
  while IFS=: read -r file name; do
    found=1
    pkg="./$(dirname "$file")"
    echo "--- $pkg $name"
    go test -run='^$' -fuzz="^${name}\$" -fuzztime="$FUZZTIME" "$pkg"
  done < <(grep -roE --include='*_test.go' --exclude-dir=testdata '^func Fuzz[A-Za-z0-9_]+' . |
           sed -E 's/^\.\/(.*):func (Fuzz[A-Za-z0-9_]+)$/\1:\2/')
  [ "$found" = 1 ] || echo "no fuzz targets"
}

# The binary is a function of the commit: built twice, the second time with an empty build cache
# so it really recompiles, and compared.
reproducible() {
  step "reproducible build: every main package, twice"
  mkdir -p "$OUT"
  local cache
  cache=$(mktemp -d)
  for pkg in $(go list -f '{{if eq .Name "main"}}{{.ImportPath}}{{end}}' ./...); do
    name=$(basename "$pkg")
    CGO_ENABLED=0 go build -trimpath -buildvcs=true -o "$OUT/$name" "$pkg"
    CGO_ENABLED=0 GOCACHE="$cache" go build -trimpath -buildvcs=true -o "$OUT/$name.again" "$pkg"
    a=$(sha256sum "$OUT/$name" 2>/dev/null || shasum -a 256 "$OUT/$name")
    b=$(sha256sum "$OUT/$name.again" 2>/dev/null || shasum -a 256 "$OUT/$name.again")
    if [ "${a%% *}" != "${b%% *}" ]; then
      echo "FAIL  $pkg builds differently from the same source"; rm -rf "$cache"; return 1
    fi
    rm -f "$OUT/$name.again"
    echo "ok  $pkg ${a%% *}"
  done
  rm -rf "$cache"
}

sbom() {
  step "SBOM: CycloneDX, from each binary's embedded build information"
  [ -n "$(ls "$OUT" 2>/dev/null)" ] || reproducible
  for bin in "$OUT"/*; do
    case "$bin" in *.json) continue ;; esac
    go tool cyclonedx-gomod bin -json -output "$bin.sbom.json" "$bin"
    echo "ok  $bin.sbom.json"
  done
}

ALL=(pin modules vet lint planted nilaway vuln licenses leakcheck test fuzz reproducible sbom)
for s in "${@:-${ALL[@]}}"; do
  case " ${ALL[*]} " in *" $s "*) "$s" ;; *) echo "unknown step: $s (steps: ${ALL[*]})"; exit 2 ;; esac
done
echo
echo "ALL GO CHECKS PASSED: ${*:-${ALL[*]}}"
