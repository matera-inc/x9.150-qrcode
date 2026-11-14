#!/usr/bin/env bash
#
# Runs the whole black-box suite against one image of the service, in containers only (Story 3.6).
#
#   X9_IMAGE=x9-qrcode:latest ./others/blackbox/deployment/run-against-image.sh
#   make blackbox-image                       # the same, after `make image`
#
# CI runs exactly this. It builds two local images from staged copies (nothing is bind-mounted,
# so it works on Colima, which cannot mount paths under /Volumes):
#
#   x9-qrcode-under-test:local   $X9_IMAGE plus the black-box truststore
#   x9-blackbox-runner:local     the suite, the bundled test CA, openapi.yaml
#
# then starts docker-compose.yml and exits with the suite's own exit code. A suite that could not
# run fails: the service never healthy, zero tests collected (pytest exit 5), or any capability
# missing (X9_REQUIRE_ALL), never a green skip.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
HERE="$ROOT/others/blackbox/deployment"
X9_IMAGE="${X9_IMAGE:-x9-qrcode:latest}"
COMPOSE=(docker compose -p x9-blackbox -f "$HERE/docker-compose.yml")

STAGE="$(mktemp -d)"
cleanup() {
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$STAGE"
}
trap cleanup EXIT

echo "== staging the suite"
mkdir -p "$STAGE/runner/others" "$STAGE/runner/x9-qrcode-infrastructure/src/test/resources" \
         "$STAGE/runner/x9-qrcode-infrastructure/src/main/resources/apis" "$STAGE/under-test"
rsync -a --exclude '__pycache__' --exclude '.pytest_cache' \
      "$ROOT/others/blackbox" "$STAGE/runner/others/"
cp -R "$ROOT/x9-qrcode-infrastructure/src/test/resources/certificate" \
      "$STAGE/runner/x9-qrcode-infrastructure/src/test/resources/"
cp "$ROOT/x9-qrcode-infrastructure/src/main/resources/apis/openapi.yaml" \
   "$STAGE/runner/x9-qrcode-infrastructure/src/main/resources/apis/"
cp "$HERE/x9-blackbox-truststore.jks" "$STAGE/under-test/"

echo "== building x9-qrcode-under-test:local from $X9_IMAGE"
docker build -q -t x9-qrcode-under-test:local --build-arg "X9_IMAGE=$X9_IMAGE" \
    -f "$HERE/under-test.Dockerfile" "$STAGE/under-test" >/dev/null
echo "== building x9-blackbox-runner:local"
docker build -q -t x9-blackbox-runner:local -f "$HERE/runner.Dockerfile" "$STAGE/runner" >/dev/null

echo "== running the suite"
set +e
"${COMPOSE[@]}" up --no-color --exit-code-from blackbox --abort-on-container-exit blackbox \
    x9-qrcode mongo 2>&1 | grep --line-buffered -E '^blackbox' | sed 's/^blackbox[^|]*| //'
status="${PIPESTATUS[0]}"
set -e

if [ "$status" -ne 0 ]; then
    echo "== the suite failed (exit $status); the service's last log lines:"
    "${COMPOSE[@]}" logs --no-color --tail 150 x9-qrcode || true
fi
exit "$status"
