#!/usr/bin/env bash
#
# Build, PROVE, and publish the container image.
#
#   ./others/scripts/publish-image.sh                  # build, verify, push git-<sha> and move latest
#   ./others/scripts/publish-image.sh --no-latest      # leave `latest` where it is
#   ./others/scripts/publish-image.sh --dry-run        # build and verify, push nothing
#   REGISTRY_REPO=you/your-image ./others/scripts/publish-image.sh
#
# The repo imposes no registry: REGISTRY_REPO defaults to the one this project publishes to and can
# be pointed anywhere. What the script does impose is that nothing is pushed until it has been run.
#
# WHY THE VERIFY STEP EXISTS. On 2026-09-30 an image was published whose acceptance suite passed
# 37/37 and which an adopter then reported as missing three features. It was not — their node had a
# stale layer — but the suite WOULD have passed with all three missing, because it tests the payment
# flow and not the behaviours most recently changed. Green is not the same as present. So this runs
# a short set of probes for exactly those, against the built container, and refuses to push if any
# of them fails.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

REGISTRY_REPO="${REGISTRY_REPO:-materainc/x9-qrcode}"
MOVE_LATEST=1
DRY_RUN=0
ALLOW_UNVERIFIED_ARCH=0
# Host ports for the verification containers. Neither may be 8080, the port INSIDE the container:
# when the advertised host and the internal host are identical, normalizeHost stops rewriting the
# JWS `jku` to plain HTTP, the instance tries to fetch its own JWK set over HTTPS, and every
# signature check fails with a 401 that looks like a broken image. It is not; it is the port.
PORT_ARM64="${PORT_ARM64:-8079}"
PORT_AMD64="${PORT_AMD64:-8078}"
MONGO_URI="${MONGO_URI:-mongodb://mongo:27017/x9-qrcode?replicaSet=x9-qrcode}"
NETWORK="${NETWORK:-x9-qrcode-network}"

for arg in "$@"; do
    case "$arg" in
        --no-latest) MOVE_LATEST=0 ;;
        --dry-run)   DRY_RUN=1 ;;
        --allow-unverified-arch) ALLOW_UNVERIFIED_ARCH=1 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

say()  { printf '\n\033[1m== %s\033[0m\n' "$1"; }
ok()   { printf '   \033[32mok\033[0m    %s\n' "$1"; }
bad()  { printf '   \033[31mFAIL\033[0m  %s\n' "$1"; }
die()  { printf '\n\033[31m%s\033[0m\n' "$1" >&2; exit 1; }

# ------------------------------------------------------------------ what we are about to publish

say "What is being published"

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
SHA="$(git rev-parse --short HEAD)"
FULL_SHA="$(git rev-parse HEAD)"

# A dry run is for checking a build before it is merged, so it does not insist on main — it only
# refuses to PUSH. A real publish insists, because a revision label naming a commit nobody can fetch
# is a false assurance rather than provenance.
if [ "$DRY_RUN" = "0" ]; then
    [ "$BRANCH" = "main" ] || die "On '$BRANCH'. Publish from main: the revision label must name a commit others can fetch. (--dry-run works from anywhere.)"
    [ -z "$(git status --porcelain)" ] || die "Working tree is dirty. The label would name a commit that does not match what is built."

    git fetch -q origin 2>/dev/null || true
    if [ -n "$(git rev-list "origin/main..HEAD" 2>/dev/null)" ]; then
        die "HEAD is ahead of origin/main. Push first, or the published revision will not exist for anyone else."
    fi
elif [ "$BRANCH" != "main" ] || [ -n "$(git status --porcelain)" ]; then
    echo "   note     dry run from '$BRANCH'; a real publish would require a clean main"
fi

echo "   commit   $FULL_SHA"
echo "   tags     $REGISTRY_REPO:git-$SHA{,-amd64,-arm64}"
[ "$MOVE_LATEST" = "1" ] && echo "   latest   will move to this build" || echo "   latest   left where it is"
[ "$DRY_RUN" = "1" ] && echo "   DRY RUN  nothing will be pushed"

# ------------------------------------------------------------------------------------- build

say "Building both architectures"
make image      >/dev/null 2>&1 || die "arm64 build failed — run 'make image' to see why."
ok "arm64"
make image-amd64 >/dev/null 2>&1 || die "amd64 build failed — run 'make image-amd64' to see why."
ok "amd64"

for pair in "x9-qrcode:latest arm64" "x9-qrcode:latest-amd64 amd64"; do
    set -- $pair
    built="$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$1")"
    [ "$built" = "$FULL_SHA" ] || die "$1 carries revision '$built', expected $FULL_SHA. An image nobody can trace is worse than no image."
    ok "$2 labelled $SHA"
done

# --------------------------------------------------------------------------- prove it, then push

say "Proving the built images actually behave"

# BOTH architectures, not just the one this machine runs natively. An image was verified on arm64
# and reported as verified, while the people running it were on amd64 — and `docker pull --platform`
# silently returned the native image anyway, so even the obvious correction did not work. The only
# reliable way is to run each per-architecture image by its own tag and check what it answers.

verify_image() {
    local image="$1" arch="$2" port="$3" platform=""

    # An if, not `[ ... ] && assign`: under `set -e` the false branch of such a line returns 1 and
    # kills the subshell, so the function returned nothing and the caller's case matched nothing —
    # reporting success for an architecture that was never started. Found by noticing a missing
    # line of output, which is the only symptom it has.
    case "$arch" in
        amd64) platform="--platform linux/amd64" ;;
        arm64) platform="--platform linux/arm64" ;;
    esac

    local container="x9-publish-verify-$arch"
    docker rm -f "$container" >/dev/null 2>&1 || true

    if ! docker run -d --name "$container" $platform --network "$NETWORK" -p "$port:8080" \
            -e SPRING_DATA_MONGODB_URI="$MONGO_URI" \
            -e X9_PUBLICENDPOINTS_HOST="localhost:$port" "$image" >/dev/null 2>&1; then
        echo "unstartable"
        return
    fi

    local health=""
    # Emulated architectures start slowly; give them room before concluding anything.
    for _ in $(seq 1 80); do
        health="$(curl -s -o /dev/null -w '%{http_code}' --max-time 4 "http://localhost:$port/actuator/health" || true)"
        [ "$health" = "200" ] && break
        sleep 3
    done

    if [ "$health" != "200" ]; then
        docker rm -f "$container" >/dev/null 2>&1 || true
        echo "unstartable"
        return
    fi

    if ./others/acceptance/acceptance.py "http://localhost:$port" >/dev/null 2>&1; then
        docker rm -f "$container" >/dev/null 2>&1 || true
        echo "pass"
    else
        ./others/acceptance/acceptance.py "http://localhost:$port" | tail -40
        docker rm -f "$container" >/dev/null 2>&1 || true
        echo "fail"
    fi
}

cleanup() { docker rm -f x9-publish-verify-arm64 x9-publish-verify-amd64 >/dev/null 2>&1 || true; }
trap cleanup EXIT

UNVERIFIED=""
for pair in "x9-qrcode:latest arm64 $PORT_ARM64" "x9-qrcode:latest-amd64 amd64 $PORT_AMD64"; do
    set -- $pair
    printf '   ...   %s: building a container and running the suite\n' "$2"
    result="$(verify_image "$1" "$2" "$3")"

    case "$result" in
        pass)        ok "$2 — acceptance suite, including the conditional-request and revision checks" ;;
        fail)        die "$2 builds but does not behave. NOTHING was pushed." ;;
        unstartable) bad "$2 could not be started on this machine"; UNVERIFIED="$UNVERIFIED $2" ;;
        *)           die "verification of $2 produced no verdict ('$result'). Refusing to publish on a check that did not run." ;;
    esac
done

if [ -n "$UNVERIFIED" ]; then
    if [ "$ALLOW_UNVERIFIED_ARCH" = "1" ]; then
        printf '\n   \033[33mPublishing with%s unverified — you asked for it with --allow-unverified-arch.\033[0m\n' "$UNVERIFIED"
    else
        die "Could not verify:$UNVERIFIED on this machine. Publish from a machine that can run it, or pass --allow-unverified-arch to ship it unchecked. An architecture nobody ran is not an architecture anybody verified."
    fi
fi

cleanup
trap - EXIT

# ------------------------------------------------------------------------------------- push

if [ "$DRY_RUN" = "1" ]; then
    say "Dry run — verified, nothing pushed"
    exit 0
fi

say "Pushing"

docker tag x9-qrcode:latest       "$REGISTRY_REPO:git-$SHA-arm64"
docker tag x9-qrcode:latest-amd64 "$REGISTRY_REPO:git-$SHA-amd64"

for arch in arm64 amd64; do
    docker push -q "$REGISTRY_REPO:git-$SHA-$arch" >/dev/null || die "push of git-$SHA-$arch failed (docker login?)"
    ok "git-$SHA-$arch"
done

docker manifest rm "$REGISTRY_REPO:git-$SHA" >/dev/null 2>&1 || true
docker manifest create "$REGISTRY_REPO:git-$SHA" \
    "$REGISTRY_REPO:git-$SHA-amd64" "$REGISTRY_REPO:git-$SHA-arm64" >/dev/null
docker manifest push "$REGISTRY_REPO:git-$SHA" >/dev/null
ok "git-$SHA (multi-arch)"

if [ "$MOVE_LATEST" = "1" ]; then
    docker tag x9-qrcode:latest       "$REGISTRY_REPO:latest-arm64"
    docker tag x9-qrcode:latest-amd64 "$REGISTRY_REPO:latest-amd64"
    docker push -q "$REGISTRY_REPO:latest-arm64" >/dev/null
    docker push -q "$REGISTRY_REPO:latest-amd64" >/dev/null
    docker manifest rm "$REGISTRY_REPO:latest" >/dev/null 2>&1 || true
    docker manifest create "$REGISTRY_REPO:latest" \
        "$REGISTRY_REPO:latest-amd64" "$REGISTRY_REPO:latest-arm64" >/dev/null
    docker manifest push "$REGISTRY_REPO:latest" >/dev/null
    ok "latest now points here"
fi

# ---------------------------------------------------------------------- confirm from outside

say "Confirming from the registry"

docker rmi "$REGISTRY_REPO:git-$SHA" >/dev/null 2>&1 || true
docker pull -q "$REGISTRY_REPO:git-$SHA" >/dev/null || die "the tag was pushed but does not pull back"
PULLED="$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$REGISTRY_REPO:git-$SHA")"
[ "$PULLED" = "$FULL_SHA" ] || die "pulled image reports revision '$PULLED', expected $FULL_SHA"
ok "pulls back, revision $SHA"

if command -v crane >/dev/null 2>&1; then
    echo
    echo "   digests — give these to anyone reporting behaviour that disagrees with this build:"
    echo "     list  $(crane digest "$REGISTRY_REPO:git-$SHA" 2>/dev/null)"
    for arch in amd64 arm64; do
        echo "     $arch $(crane digest --platform "linux/$arch" "$REGISTRY_REPO:git-$SHA" 2>/dev/null)"
    done
fi

say "Published $REGISTRY_REPO:git-$SHA"
