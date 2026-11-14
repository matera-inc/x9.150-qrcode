#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Lints testdata/lint/planted, where every line ending in "// want <linter>" is a deliberate
# violation, and fails unless exactly those lines are reported by exactly those linters. A rule that
# stops firing (a typo in .golangci.yml, a linter renamed by an upgrade) would otherwise pass
# silently, along with every real violation of it.
#
#   others/scripts/check-go-lint-planted.sh        # from the repository root

set -euo pipefail
cd "$(dirname "$0")/../.."

DIR=testdata/lint/planted

want=$(grep -rnE '// want [a-z]+' "$DIR" --include='*.go' \
  | sed -E 's/^([^:]+):([0-9]+):.*\/\/ want ([a-z]+).*/\1:\2 \3/' | sort)

# golangci-lint exits non-zero when it reports something, which is the point here.
got=$(go tool golangci-lint run --max-issues-per-linter=0 --max-same-issues=0 "./$DIR/..." 2>&1 \
  | sed -nE 's/^([^:]+):([0-9]+):[0-9]+: .*\(([a-z]+)\)$/\1:\2 \3/p' | sort -u || true)

if [ "$want" = "$got" ]; then
  echo "ok  every planted violation is reported, and nothing else: $(echo "$want" | wc -l | tr -d ' ') lines"
  exit 0
fi
echo "FAIL  the lint configuration no longer reports exactly the planted violations"
diff <(echo "$want") <(echo "$got") | sed 's/^</  missing:   /; s/^>/  unexpected:/' || true
exit 1
