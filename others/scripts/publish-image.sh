#!/usr/bin/env bash
#
# Build, PROVE, and publish the container image (the Go build, from the root Dockerfile).
#
#   ./others/scripts/publish-image.sh                       # build, verify, push git-<sha>, move latest
#   VERSION=3.0.0 ./others/scripts/publish-image.sh         # ... and 3.0.0, 3.0 and 3
#   ./others/scripts/publish-image.sh --no-latest           # leave `latest` where it is
#   ./others/scripts/publish-image.sh --dry-run             # build and verify, push nothing
#   ./others/scripts/publish-image.sh --allow-unverified-arch   # push an architecture this machine cannot run
#   REGISTRY_REPO=you/your-image ./others/scripts/publish-image.sh
#
# The repo imposes no registry: REGISTRY_REPO defaults to the one this project publishes to and can
# be pointed anywhere. What the script does impose is that nothing is pushed until it has been run.
# It pushes from this machine with its stored `docker login`; CI never pushes.
#
# WHY THE VERIFY STEP EXISTS. On 2026-09-30 an image was published whose acceptance suite passed
# 37/37 and which an adopter then reported as missing three features. It was not — their node had a
# stale layer — but the suite WOULD have passed with all three missing, because it tested the payment
# flow and not the behaviours most recently changed. Green is not the same as present. So this runs
# the image smoke test and the WHOLE black-box suite against each built architecture, by its own
# tag, and refuses to push if any of it fails or cannot run.
#
# What it does, in order:
#   1. builds linux/amd64 and linux/arm64 with docker buildx from the Dockerfile (the Go build
#      cross-compiles, so neither needs emulation to build), each loaded under its own local tag,
#      with VERSION and REVISION stamped into the binary and the OCI labels;
#   2. checks each one's org.opencontainers.image.revision is this commit;
#   3. per architecture: others/scripts/image-smoke.sh (non-root, -version, health UP against
#      PostgreSQL, exit 0 on SIGTERM) and others/blackbox/deployment/run-against-image.sh (the whole
#      suite, on PostgreSQL 16 with db/changelog applied), with X9_PLATFORM set so the image runs as
#      that architecture. An architecture this machine cannot run is "unverified": refused unless
#      --allow-unverified-arch;
#   4. only then pushes: git-<sha>-amd64 and git-<sha>-arm64 (the verified images, as they are),
#      then ONE multi-arch manifest per tag — git-<sha>, the version tags when VERSION is given
#      (3.0.0 -> 3.0.0, 3.0, 3; a pre-release such as 3.1.0-rc.1 gets only itself), and `latest`
#      unless --no-latest — made with `docker buildx imagetools create` from the pushed images, so
#      what is published is byte for byte what was verified;
#   5. pulls git-<sha> back and checks its revision label.
#
# The Java build's images (2.x and earlier) were published by an earlier version of this script; the
# last of them is tagged java-final (ADR-0039). This script never touches that tag.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

REGISTRY_REPO="${REGISTRY_REPO:-materainc/x9-qrcode}"
VERSION="${VERSION:-}"
MOVE_LATEST=1
DRY_RUN=0
ALLOW_UNVERIFIED_ARCH=0
ARCHES=(amd64 arm64)

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

docker buildx version >/dev/null 2>&1 || die "docker buildx is needed (it builds both architectures)."

# ------------------------------------------------------------------ what we are about to publish

say "What is being published"

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
SHA="$(git rev-parse --short HEAD)"
FULL_SHA="$(git rev-parse HEAD)"

# The version tags: 3.0.0 -> 3.0.0 3.0 3. A pre-release or build suffix gets only its own tag, so
# that `3` never moves to a release candidate.
VERSION_TAGS=()
if [ -n "$VERSION" ]; then
    VERSION="${VERSION#v}"
    if [[ "$VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
        VERSION_TAGS=("$VERSION" "${BASH_REMATCH[1]}.${BASH_REMATCH[2]}" "${BASH_REMATCH[1]}")
    elif [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+[-+][0-9A-Za-z.-]+$ ]]; then
        VERSION_TAGS=("${VERSION//+/_}")
    else
        die "VERSION must look like 3.0.0 (or 3.1.0-rc.1), not '$VERSION'."
    fi
fi
# The version stamped into the binary and the image's labels: the release, or the commit.
IMAGE_VERSION="${VERSION:-git-$SHA}"

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

TAGS=("git-$SHA" ${VERSION_TAGS[@]+"${VERSION_TAGS[@]}"})
[ "$MOVE_LATEST" = "1" ] && TAGS+=(latest)

echo "   commit   $FULL_SHA"
echo "   version  $IMAGE_VERSION"
echo "   images   $REGISTRY_REPO:git-$SHA-{amd64,arm64}"
echo "   tags     ${TAGS[*]} (one multi-arch manifest each)"
[ "$MOVE_LATEST" = "1" ] || echo "   latest   left where it is"
[ "$DRY_RUN" = "1" ] && echo "   DRY RUN  nothing will be pushed"

# Local tags of this run, removed at the end. Never :latest (a later `docker save` of one of them
# would carry it and move the recipient's latest).
LOCAL="x9-qrcode-publish:$SHA"
local_tag() { printf '%s-%s' "$LOCAL" "$1"; }
cleanup() {
    for arch in "${ARCHES[@]}"; do docker image rm "$(local_tag "$arch")" >/dev/null 2>&1 || true; done
}
trap cleanup EXIT

# ------------------------------------------------------------------------------------- build

say "Building both architectures (docker buildx, from the Dockerfile)"
for arch in "${ARCHES[@]}"; do
    docker buildx build --platform "linux/$arch" --load -t "$(local_tag "$arch")" \
        --build-arg VERSION="$IMAGE_VERSION" --build-arg REVISION="$FULL_SHA" . >/dev/null 2>&1 \
        || die "the $arch build failed — run 'docker buildx build --platform linux/$arch .' to see why."
    built="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$(local_tag "$arch")")"
    [ "$built" = "$FULL_SHA" ] || die "$arch carries revision '$built', expected $FULL_SHA. An image nobody can trace is worse than no image."
    actual="$(docker image inspect --format '{{.Architecture}}' "$(local_tag "$arch")")"
    [ "$actual" = "$arch" ] || die "the $arch build is $actual."
    ok "$arch, labelled $SHA"
done

# --------------------------------------------------------------------------- prove it, then push

say "Proving the built images actually behave"

# BOTH architectures, not just the one this machine runs natively. An image was once verified on
# arm64 and reported as verified, while the people running it were on amd64 — and `docker pull
# --platform` silently returned the native image anyway. The only reliable way is to run each
# per-architecture image by its own tag, as that architecture, and check what it answers.

can_run() {   # whether this machine's Docker can run a linux/$1 container at all
    docker run --rm --platform "linux/$1" "$(local_tag "$1")" -version >/dev/null 2>&1
}

verify_image() {   # prints pass, fail or unstartable; the evidence goes to $LOGS/<arch>-*.log
    local arch="$1" image
    image="$(local_tag "$arch")"
    if ! can_run "$arch"; then
        echo "unstartable"; return
    fi
    if ! X9_PLATFORM="linux/$arch" X9_SMOKE_PORT="$2" \
            others/scripts/image-smoke.sh "$image" "$LOGS/$arch" >"$LOGS/$arch-smoke.log" 2>&1; then
        echo "fail-smoke"; return
    fi
    # The whole suite, in containers, as CI runs it: a suite that could not run fails it.
    if X9_IMAGE="$image" X9_PLATFORM="linux/$arch" \
            X9_BLACKBOX_PROJECT="x9-publish-$arch-$SHA" \
            ./others/blackbox/deployment/run-against-image.sh >"$LOGS/$arch-blackbox.log" 2>&1; then
        echo "pass"
    else
        echo "fail-blackbox"
    fi
}

LOGS="$(mktemp -d "${TMPDIR:-/tmp}/x9-publish-$SHA.XXXXXX")"
echo "   logs     $LOGS"
UNVERIFIED=""
port=18078
for arch in "${ARCHES[@]}"; do
    printf '   ...   %s: image smoke test, then the black-box suite\n' "$arch"
    result="$(verify_image "$arch" "$port")"
    port=$((port + 1))
    case "$result" in
        pass)          ok "$arch — image smoke test and the whole black-box suite" ;;
        fail-smoke)    tail -30 "$LOGS/$arch-smoke.log"; die "$arch builds but fails the image smoke test. NOTHING was pushed." ;;
        fail-blackbox) tail -40 "$LOGS/$arch-blackbox.log"; die "$arch builds but fails the black-box suite. NOTHING was pushed." ;;
        unstartable)   bad "$arch cannot be run on this machine"; UNVERIFIED="$UNVERIFIED $arch" ;;
        *)             die "verification of $arch produced no verdict ('$result'). Refusing to publish on a check that did not run." ;;
    esac
done

if [ -n "$UNVERIFIED" ]; then
    if [ "$ALLOW_UNVERIFIED_ARCH" = "1" ]; then
        printf '\n   \033[33mPublishing with%s unverified — you asked for it with --allow-unverified-arch.\033[0m\n' "$UNVERIFIED"
    else
        die "Could not verify:$UNVERIFIED on this machine. Publish from a machine that can run it (or enable emulation), or pass --allow-unverified-arch to ship it unchecked. An architecture nobody ran is not an architecture anybody verified."
    fi
fi

# ------------------------------------------------------------------------------------- push

if [ "$DRY_RUN" = "1" ]; then
    say "Dry run — verified, nothing pushed"
    exit 0
fi

say "Pushing"

for arch in "${ARCHES[@]}"; do
    docker tag "$(local_tag "$arch")" "$REGISTRY_REPO:git-$SHA-$arch"
    docker push -q "$REGISTRY_REPO:git-$SHA-$arch" >/dev/null || die "push of git-$SHA-$arch failed (docker login?)"
    docker image rm "$REGISTRY_REPO:git-$SHA-$arch" >/dev/null 2>&1 || true
    ok "git-$SHA-$arch"
done

# One multi-arch manifest per tag, from the two images just pushed: nothing is rebuilt.
for tag in "${TAGS[@]}"; do
    docker buildx imagetools create -t "$REGISTRY_REPO:$tag" \
        "$REGISTRY_REPO:git-$SHA-amd64" "$REGISTRY_REPO:git-$SHA-arm64" >/dev/null \
        || die "could not create the manifest $tag"
    ok "$tag (linux/amd64, linux/arm64)"
done

# ---------------------------------------------------------------------- confirm from outside

say "Confirming from the registry"

docker image rm "$REGISTRY_REPO:git-$SHA" >/dev/null 2>&1 || true
docker pull -q "$REGISTRY_REPO:git-$SHA" >/dev/null || die "the tag was pushed but does not pull back"
PULLED="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$REGISTRY_REPO:git-$SHA")"
docker image rm "$REGISTRY_REPO:git-$SHA" >/dev/null 2>&1 || true
[ "$PULLED" = "$FULL_SHA" ] || die "pulled image reports revision '$PULLED', expected $FULL_SHA"
ok "pulls back, revision $SHA"

echo
echo "   digests — give these to anyone reporting behaviour that disagrees with this build:"
docker buildx imagetools inspect "$REGISTRY_REPO:git-$SHA" | sed 's/^/     /'

say "Published $REGISTRY_REPO:${TAGS[*]}"
