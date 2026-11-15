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
#   x9-qrcode-under-test:<run>   $X9_IMAGE plus the black-box truststore
#   x9-blackbox-runner:<run>     the suite, the bundled test CA, openapi.yaml, ADR-0035
#
# then starts docker-compose.yml and exits with the suite's own exit code.
#
# Every run is its own: the compose project is $X9_BLACKBOX_PROJECT if set, otherwise
# x9-blackbox-<random suffix>, and the two images are tagged with that same name. Two runs at once
# (say, one against the Java image and one against the Go image) never share a container, a
# network or an image tag, and each one removes only its own project and its own two tags. A suite that could not
# run fails: the service never healthy, zero tests collected (pytest exit 5), or any capability
# missing (X9_REQUIRE_ALL), never a green skip.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
HERE="$ROOT/others/blackbox/deployment"
X9_IMAGE="${X9_IMAGE:-x9-qrcode:latest}"
# A compose project name: lowercase letters, digits, '-' and '_', starting with a letter or digit.
PROJECT="${X9_BLACKBOX_PROJECT:-x9-blackbox-$(LC_ALL=C tr -dc 'a-z0-9' </dev/urandom | head -c 8 || true)}"
if ! printf '%s' "$PROJECT" | grep -Eq '^[a-z0-9][a-z0-9_-]*$'; then
    echo "X9_BLACKBOX_PROJECT must be lowercase letters, digits, '-' or '_': '$PROJECT'" >&2
    exit 2
fi
export X9_UNDER_TEST_IMAGE="x9-qrcode-under-test:$PROJECT"
export X9_RUNNER_IMAGE="x9-blackbox-runner:$PROJECT"
COMPOSE=(docker compose -p "$PROJECT" -f "$HERE/docker-compose.yml")

STAGE="$(mktemp -d)"
cleanup() {
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
    docker image rm "$X9_UNDER_TEST_IMAGE" "$X9_RUNNER_IMAGE" >/dev/null 2>&1 || true
    rm -rf "$STAGE"
}
trap cleanup EXIT

echo "== project $PROJECT"
echo "== staging the suite"
mkdir -p "$STAGE/runner/others" "$STAGE/runner/x9-qrcode-infrastructure/src/test/resources" \
         "$STAGE/runner/x9-qrcode-infrastructure/src/main/resources/apis" "$STAGE/under-test"
rsync -a --exclude '__pycache__' --exclude '.pytest_cache' \
      "$ROOT/others/blackbox" "$STAGE/runner/others/"
cp -R "$ROOT/x9-qrcode-infrastructure/src/test/resources/certificate" \
      "$STAGE/runner/x9-qrcode-infrastructure/src/test/resources/"
cp "$ROOT/x9-qrcode-infrastructure/src/main/resources/apis/openapi.yaml" \
   "$STAGE/runner/x9-qrcode-infrastructure/src/main/resources/apis/"
# ADR-0035, so test_transition_map.py can check every row of its map has a test (Story 4.1).
mkdir -p "$STAGE/runner/docs/adr"
cp "$ROOT"/docs/adr/0035-*.md "$STAGE/runner/docs/adr/"
cp "$HERE/x9-blackbox-truststore.jks" "$STAGE/under-test/"

echo "== building $X9_UNDER_TEST_IMAGE from $X9_IMAGE"
docker build -q -t "$X9_UNDER_TEST_IMAGE" --build-arg "X9_IMAGE=$X9_IMAGE" \
    -f "$HERE/under-test.Dockerfile" "$STAGE/under-test" >/dev/null
echo "== building $X9_RUNNER_IMAGE"
docker build -q -t "$X9_RUNNER_IMAGE" -f "$HERE/runner.Dockerfile" "$STAGE/runner" >/dev/null

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
