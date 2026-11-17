# syntax=docker/dockerfile:1
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# The image (ADR-0031): a static, cgo-free binary on distroless, running as non-root. Built by
# `make image` (this machine's architecture) and published for amd64 and arm64 by
# others/scripts/publish-image.sh.
#
#   docker build -t x9-qrcode:latest --build-arg VERSION=… --build-arg REVISION=$(git rev-parse HEAD) .
#   docker buildx build --platform linux/amd64,linux/arm64 .        # both architectures
#
# Stage 1 runs on the builder's own platform and cross-compiles for the target, so building the
# other architecture needs no emulation. Both base images are pinned by digest (a multi-arch
# index); move them deliberately, in a pull request of their own.

ARG GO_IMAGE=golang:1.27.4-trixie@sha256:b54b2639de8c1bfe538ec5ffc45f29b1932235ac5f6d63c059853646677112d9
ARG RUNTIME_IMAGE=gcr.io/distroless/static-debian13:nonroot@sha256:aafe993665bf6200db38a02c11a9351e782a84b6a45e7f33b6695638c88e3931
# Set by BuildKit. The legacy builder needs it passed: --build-arg BUILDPLATFORM=linux/<arch>.
ARG BUILDPLATFORM

# ── 1. Build ─────────────────────────────────────────────────────────────────────────────────────
FROM --platform=$BUILDPLATFORM ${GO_IMAGE} AS build
ARG TARGETOS=linux
ARG TARGETARCH
ARG VERSION=dev
ARG REVISION=unknown
# The image's Go is the toolchain go.mod pins; never fetch another, never edit go.mod.
ENV GOTOOLCHAIN=local GOFLAGS=-mod=readonly CGO_ENABLED=0
WORKDIR /src
COPY go.mod go.sum ./
COPY cmd ./cmd
COPY internal ./internal
# -trimpath: no build paths in the binary. Version and commit come from the build arguments, since
# the build context carries no .git (so -buildvcs is off).
RUN GOOS="$TARGETOS" GOARCH="${TARGETARCH:-$(go env GOARCH)}" \
    go build -trimpath -buildvcs=false \
      -ldflags "-s -w -X main.version=${VERSION} -X main.commit=${REVISION}" \
      -o /out/x9-qrcode ./cmd/x9-qrcode

# ── 2. Run ───────────────────────────────────────────────────────────────────────────────────────
FROM ${RUNTIME_IMAGE}
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="x9-qrcode" \
      org.opencontainers.image.description="ANSI X9.150 Payment QR Code service" \
      org.opencontainers.image.source="https://github.com/matera-inc/x9.150-qrcode" \
      org.opencontainers.image.vendor="Matera Systems, Inc." \
      org.opencontainers.image.licenses="LicenseRef-Matera-Source-License-1.0" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
COPY --from=build /out/x9-qrcode /x9-qrcode
# distroless's nonroot user (65532); stated here so the image says it, not only its base.
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/x9-qrcode"]
