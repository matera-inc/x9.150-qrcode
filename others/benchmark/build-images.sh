#!/usr/bin/env bash
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Builds the images a campaign runs, tagged git-<sha> of the checked-out commit, into this
# machine's Docker. Never pushes, never tags :latest.
#
#   others/benchmark/build-images.sh [java] [go] [liquibase]     # default: all three
#
#   x9-qrcode:git-<sha>            the Java build (buildpacks, ./mvnw -Pdocker), OCI revision set
#   x9-qrcode-go:git-<sha>         the Go build (Dockerfile at the root), OCI revision set
#   x9-qrcode-liquibase:git-<sha>  Liquibase 4.33.0 with db/changelog (db/local/liquibase.Dockerfile)
#
# A tree with uncommitted changes is refused (its git-<sha> would name code it is not); set
# ALLOW_DIRTY=1 for a harness check, never for a measurement.
set -euo pipefail
cd "$(dirname "$0")/../.."

SHA="$(git rev-parse --short HEAD)"
REVISION="$(git rev-parse HEAD)"
WHAT=("$@")
[ ${#WHAT[@]} -gt 0 ] || WHAT=(java go liquibase)

if [ -n "$(git status --porcelain --untracked-files=no)" ] && [ "${ALLOW_DIRTY:-0}" != 1 ]; then
  echo "refusing: the working tree has uncommitted changes, so git-$SHA would not name this code" >&2
  exit 1
fi

for what in "${WHAT[@]}"; do
  case "$what" in
    java)
      ./mvnw -B -ntp -Pdocker -DskipTests package -Dbuild.image.name="x9-qrcode:git-$SHA" \
        -Doci.revision="$REVISION" -Doci.version="git-$SHA"
      image="x9-qrcode:git-$SHA" ;;
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
  docker image inspect "$image" --format "built $image: {{.Os}}/{{.Architecture}}, {{.Size}} bytes, revision {{index .Config.Labels \"org.opencontainers.image.revision\"}}"
done
