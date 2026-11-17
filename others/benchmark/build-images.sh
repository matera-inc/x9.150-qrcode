#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Builds the images a campaign runs, tagged git-<sha> of the checked-out commit, into this
# machine's Docker. Never pushes, never tags :latest.
#
#   others/benchmark/build-images.sh [go] [liquibase]     # default: both
#
#   x9-qrcode-go:git-<sha>         the Go build (Dockerfile at the root), OCI revision set
#   x9-qrcode-liquibase:git-<sha>  Liquibase 4.33.0 with db/changelog (db/local/liquibase.Dockerfile)
#
# The Java build was retired at 3.0.0 (ADR-0039) and cannot be built from this tree. A campaign that
# runs it (J in SEQUENCE) uses its last published image, materainc/x9-qrcode:java-final, which
# lib.sh takes as JAVA_IMAGE by default; `build-images.sh java` pulls it.
#
# A tree with uncommitted changes is refused (its git-<sha> would name code it is not); set
# ALLOW_DIRTY=1 for a harness check, never for a measurement.
set -euo pipefail
cd "$(dirname "$0")/../.."

SHA="$(git rev-parse --short HEAD)"
REVISION="$(git rev-parse HEAD)"
WHAT=("$@")
[ ${#WHAT[@]} -gt 0 ] || WHAT=(go liquibase)

if [ -n "$(git status --porcelain --untracked-files=no)" ] && [ "${ALLOW_DIRTY:-0}" != 1 ]; then
  echo "refusing: the working tree has uncommitted changes, so git-$SHA would not name this code" >&2
  exit 1
fi

for what in "${WHAT[@]}"; do
  case "$what" in
    java)
      # Not built: pulled. The source is the git tag java-final.
      image="${JAVA_IMAGE:-materainc/x9-qrcode:java-final}"
      docker pull -q "$image" >/dev/null ;;
    go)
      image="x9-qrcode-go:git-$SHA"
      if docker buildx version >/dev/null 2>&1; then
        docker buildx build --load -t "$image" --build-arg VERSION="git-$SHA" --build-arg REVISION="$REVISION" .
      else
        docker build -t "$image" --build-arg VERSION="git-$SHA" --build-arg REVISION="$REVISION" \
          --build-arg BUILDPLATFORM="linux/$(docker version -f '{{.Server.Arch}}')" .
      fi ;;
    liquibase)
      image="x9-qrcode-liquibase:git-$SHA"
      docker build -t "$image" --label org.opencontainers.image.revision="$REVISION" \
        --label org.opencontainers.image.version="git-$SHA" -f db/local/liquibase.Dockerfile db ;;
    *) echo "unknown image $what (java, go, liquibase)" >&2; exit 2 ;;
  esac
  # The size from `docker save`, not {{.Size}}, whose meaning depends on the image store.
  size="$(python3 -I others/scripts/image-size.py "$image" --field sizeBytes)"
  docker image inspect "$image" --format "built $image: {{.Os}}/{{.Architecture}}, $size bytes unpacked, revision {{index .Config.Labels \"org.opencontainers.image.revision\"}}"
done
