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
# A Go image (Story 9.1) runs on PostgreSQL instead: docker-compose.go.yml, two more local images
# (x9-blackbox-bootstrap:<run> and x9-blackbox-liquibase:<run>, db/local), the changelog applied by
# the Liquibase one-shot before the service starts, the truststore as PKCS#12, and the verdict of
# go-required.txt: every test runs, and only the listed ones decide the exit code. The build is the
# image's entrypoint (/x9-qrcode is the Go binary); X9_BUILD=java|go overrides it.
#
# Every run is its own: the compose project is $X9_BLACKBOX_PROJECT if set, otherwise
# x9-blackbox-<random suffix>, and the two images are tagged with that same name. Two runs at once
# (say, one against the Java image and one against the Go image) never share a container, a
# network or an image tag, and each one removes only its own project and its own tags. A suite that could not
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
docker image inspect "$X9_IMAGE" >/dev/null 2>&1 || docker pull -q "$X9_IMAGE" >/dev/null
BUILD="${X9_BUILD:-}"
if [ -z "$BUILD" ]; then
    case "$(docker image inspect "$X9_IMAGE" --format '{{json .Config.Entrypoint}}')" in
        '["/x9-qrcode"]') BUILD=go ;;
        *) BUILD=java ;;
    esac
fi
case "$BUILD" in
    java) COMPOSE_FILE="$HERE/docker-compose.yml"; TRUSTSTORE=x9-blackbox-truststore.jks
          DB_EXTRA=mongo; SERVICES=(blackbox x9-qrcode mongo) ;;
    go)   COMPOSE_FILE="$HERE/docker-compose.go.yml"; TRUSTSTORE=x9-blackbox-truststore.p12
          DB_EXTRA=postgres; SERVICES=(blackbox x9-qrcode) ;;
    *)    echo "X9_BUILD must be java or go: '$BUILD'" >&2; exit 2 ;;
esac
export X9_UNDER_TEST_IMAGE="x9-qrcode-under-test:$PROJECT"
export X9_RUNNER_IMAGE="x9-blackbox-runner:$PROJECT"
export X9_BOOTSTRAP_IMAGE="x9-blackbox-bootstrap:$PROJECT"
export X9_LIQUIBASE_IMAGE="x9-blackbox-liquibase:$PROJECT"
COMPOSE=(docker compose -p "$PROJECT" -f "$COMPOSE_FILE")

UP_FLAGS=()
STAGE="$(mktemp -d)"
cleanup() {
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
    docker image rm "$X9_UNDER_TEST_IMAGE" "$X9_RUNNER_IMAGE" "$X9_BOOTSTRAP_IMAGE" \
        "$X9_LIQUIBASE_IMAGE" >/dev/null 2>&1 || true
    rm -rf "$STAGE"
}
trap cleanup EXIT

echo "== project $PROJECT ($BUILD build)"
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
cp "$HERE/$TRUSTSTORE" "$STAGE/under-test/"
cp "$HERE/x9-test-ca.pem" "$STAGE/under-test/"

echo "== building $X9_UNDER_TEST_IMAGE from $X9_IMAGE"
docker build -q -t "$X9_UNDER_TEST_IMAGE" --build-arg "X9_IMAGE=$X9_IMAGE" \
    -f "$HERE/under-test.Dockerfile" "$STAGE/under-test" >/dev/null
echo "== building $X9_RUNNER_IMAGE"
docker build -q -t "$X9_RUNNER_IMAGE" --build-arg "X9_DB_EXTRA=$DB_EXTRA" \
    -f "$HERE/runner.Dockerfile" "$STAGE/runner" >/dev/null

if [ "$BUILD" = go ]; then
    echo "== building $X9_BOOTSTRAP_IMAGE and $X9_LIQUIBASE_IMAGE from db/"
    docker build -q -t "$X9_BOOTSTRAP_IMAGE" -f "$ROOT/db/local/bootstrap.Dockerfile" "$ROOT/db" >/dev/null
    docker build -q -t "$X9_LIQUIBASE_IMAGE" -f "$ROOT/db/local/liquibase.Dockerfile" "$ROOT/db" >/dev/null
    # The schema first, on its own: a one-shot exiting would end an --abort-on-container-exit run.
    echo "== applying db/changelog (Liquibase, as the migration role)"
    if ! "${COMPOSE[@]}" run --rm --quiet-pull liquibase >"$STAGE/liquibase.log" 2>&1; then
        echo "== the changelog did not apply:"
        cat "$STAGE/liquibase.log"
        exit 1
    fi
    grep -E 'Run:|Liquibase command .* was executed successfully' "$STAGE/liquibase.log" || true
    # PostgreSQL keeps running; the service and the suite start without re-running the one-shots.
    UP_FLAGS=(--no-deps)
fi

echo "== running the suite"
set +e
"${COMPOSE[@]}" up --no-color ${UP_FLAGS[@]+"${UP_FLAGS[@]}"} --exit-code-from blackbox --abort-on-container-exit \
    "${SERVICES[@]}" 2>&1 | grep --line-buffered -E '^blackbox' | sed 's/^blackbox[^|]*| //'
status="${PIPESTATUS[0]}"
set -e

if [ "$status" -ne 0 ]; then
    echo "== the suite failed (exit $status); the service's last log lines:"
    "${COMPOSE[@]}" logs --no-color --tail 150 x9-qrcode || true
fi
exit "$status"
