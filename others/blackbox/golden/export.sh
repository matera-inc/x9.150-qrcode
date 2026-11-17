#!/usr/bin/env bash
#
# Exports the golden JWS and EMV sets from one image of the Java build (Story 4.7, ADR-0027),
# replacing others/blackbox/golden/{manifest.json,jws,pki}.
#
#   ./others/blackbox/golden/export.sh          # X9_IMAGE defaults to materainc/x9-qrcode:java-final
#
# The Java build was retired at 3.0.0 (ADR-0039): its last image is published as java-final, and its
# source is the git tag java-final. The set is a record of what Java produced; re-export it only if
# the bundled test PKI (testdata/pki) has to be regenerated, and only from that image.
#
# It starts the image four times, each time signing with another of the repository's bundled TEST
# keystores and answering under another public host, and has export.py capture what Java signs and
# encodes (see export.py for what is captured, and what is derived from it):
#
#   identity   keystore                      host
#   rsa        x9-test-payer.p12             x9-150.example.com
#   ec         x9-test-payer-ec.p12          x9.thirty-seven-characters.example.io   (37, the limit)
#   revoked    x9-test-payer-revoked.p12     x9.example
#   untrusted  x9-test-untrusted-payer.p12   qr.example.org:8443
#
# Containers only, nothing bind-mounted (Colima cannot mount paths under /Volumes). Every export
# makes NEW tokens (new correlationIds, new signatures), so the set is replaced as a whole and
# committed as a whole. The export is not finished until the suite has passed against the Go build
# with the new set (make blackbox-image; tests/test_golden.py), which CI also runs.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
HERE="$ROOT/others/blackbox/golden"
CERTS="$ROOT/testdata/pki"
X9_IMAGE="${X9_IMAGE:-materainc/x9-qrcode:java-final}"
COMPOSE=(docker compose -p x9-golden -f "$HERE/docker-compose.yml")

export GOLDEN_IMAGE="$X9_IMAGE"
GOLDEN_REVISION="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
export GOLDEN_REVISION
export GOLDEN_IDENTITY="" GOLDEN_KEYSTORE="" GOLDEN_HOST="" GOLDEN_ALG=""

STAGE="$(mktemp -d)"
cleanup() {
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$STAGE"
}
trap cleanup EXIT

echo "== staging"
mkdir -p "$STAGE/runner/others" "$STAGE/runner/testdata" "$STAGE/signer" "$STAGE/out"
rsync -a --exclude '__pycache__' --exclude '.pytest_cache' \
      "$ROOT/others/blackbox" "$STAGE/runner/others/"
cp -R "$CERTS" "$STAGE/runner/testdata/"
cp "$ROOT/others/blackbox/deployment/x9-blackbox-truststore.jks" "$CERTS"/x9-test-payer.p12 \
   "$CERTS"/x9-test-payer-ec.p12 "$CERTS"/x9-test-payer-revoked.p12 \
   "$CERTS"/x9-test-untrusted-payer.p12 "$STAGE/signer/"

echo "== building x9-qrcode-golden-signer:local from $X9_IMAGE"
docker build -q -t x9-qrcode-golden-signer:local --build-arg "X9_IMAGE=$X9_IMAGE" \
    -f "$HERE/signer.Dockerfile" "$STAGE/signer" >/dev/null
echo "== building x9-golden-exporter:local"
docker build -q -t x9-golden-exporter:local \
    -f "$ROOT/others/blackbox/deployment/runner.Dockerfile" "$STAGE/runner" >/dev/null

"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true

capture() {
    export GOLDEN_IDENTITY="$1" GOLDEN_KEYSTORE="$2" GOLDEN_ALG="$3" GOLDEN_HOST="$4"
    echo "== $GOLDEN_IDENTITY: Java signs with $GOLDEN_KEYSTORE.p12 as $GOLDEN_HOST"
    set +e
    "${COMPOSE[@]}" up --no-color --exit-code-from exporter --abort-on-container-exit exporter \
        x9-qrcode mongo 2>&1 | grep --line-buffered -E '^exporter' | sed 's/^exporter[^|]*| //'
    status="${PIPESTATUS[0]}"
    set -e
    if [ "$status" -ne 0 ]; then
        echo "== the export failed (exit $status); the service's last log lines:"
        "${COMPOSE[@]}" logs --no-color --tail 40 x9-qrcode || true
        exit "$status"
    fi
    # The exporter's container, stopped, still holds the volume with what it wrote.
    rm -rf "$STAGE/out" && mkdir -p "$STAGE/out"
    docker cp "$("${COMPOSE[@]}" ps -a -q exporter)":/out/golden/. "$STAGE/out/"
    "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1      # keeps the volume, drops MongoDB
}

capture rsa       x9-test-payer           PS512 x9-150.example.com
capture ec        x9-test-payer-ec        ES256 x9.thirty-seven-characters.example.io
capture revoked   x9-test-payer-revoked   PS512 x9.example
capture untrusted x9-test-untrusted-payer PS512 qr.example.org:8443

echo "== replacing the committed set"
rm -rf "$HERE/jws" "$HERE/pki" "$HERE/manifest.json"
cp -R "$STAGE/out/jws" "$STAGE/out/pki" "$STAGE/out/manifest.json" "$HERE/"
echo "== done: $(find "$HERE/jws" -type f | wc -l | tr -d ' ') JWS files. Now prove the manifest against Java:"
echo "   X9_IMAGE=$X9_IMAGE ./others/blackbox/deployment/run-against-image.sh"
