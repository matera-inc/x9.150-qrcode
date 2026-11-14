#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Lints testdata/lint/planted and fails unless exactly the planted violations are reported:
#   - a line ending in "// want <linter> [<linter>…]" must be reported by exactly those linters;
#   - "want-file <linter>" anywhere in a file expects that linter on the file's line 1;
#   - no other line may be reported.
# A rule that stops firing (a typo in .golangci.yml, a linter renamed by an upgrade) would otherwise
# pass silently, along with every real violation of it. Needs jq.
#
#   others/scripts/check-go-lint-planted.sh        # from anywhere in the repository

set -euo pipefail
cd "$(dirname "$0")/../.."

DIR=testdata/lint/planted

want=$(
  {
    grep -rnE '// want [a-z]' "$DIR" --include='*.go' |
      sed -E 's/^([^:]+):([0-9]+):.*\/\/ want (.*)$/\1 \2 \3/' |
      while read -r file line linters; do
        for l in $linters; do echo "$file:$line $l"; done
      done
    grep -rlE 'want-file [a-z]' "$DIR" --include='*.go' | while read -r file; do
      for l in $(sed -nE 's/.*want-file ([a-z ]+).*/\1/p' "$file"); do echo "$file:1 $l"; done
    done
  } | sort -u
)

# golangci-lint exits non-zero when it reports something, which is the point here.
got=$(go tool golangci-lint run --max-issues-per-linter=0 --max-same-issues=0 --uniq-by-line=false \
        --show-stats=false --output.json.path=stdout "./$DIR/..." 2>/dev/null |
      jq -r '.Issues[] | "\(.Pos.Filename):\(.Pos.Line) \(.FromLinter)"' | sort -u || true)

if [ -n "$want" ] && [ "$want" = "$got" ]; then
  echo "ok  every planted violation is reported, and nothing else: $(echo "$want" | wc -l | tr -d ' ') findings"
  exit 0
fi
echo "FAIL  the lint configuration no longer reports exactly the planted violations"
diff <(echo "$want") <(echo "$got") | sed -n 's/^</  missing:   /p; s/^>/  unexpected:/p' || true
exit 1
