#!/usr/bin/env bash
#
# Removes generated playground artifacts (.png .emv .jws .json) produced by the
# simulate_*.py scripts and QR-rendering commands.
#
# Non-recursive on purpose: only files directly in this folder are deleted. The
# committed source create-requests in requests/ (requests/qr-*-createqr.json) are
# left untouched.
#
set -euo pipefail

cd "$(dirname "$0")"
shopt -s nullglob

files=( *.png *.emv *.jws *.json )

if [ ${#files[@]} -eq 0 ]; then
    echo "Nothing to clean."
    exit 0
fi

echo "Deleting ${#files[@]} generated file(s) from $(pwd):"
for f in "${files[@]}"; do
    echo "  $f"
    rm -f -- "$f"
done
echo "Done."
