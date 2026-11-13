# 31. Same Docker image, lighter; 3.0.0 is a configuration migration

Date: 2026-11-13

## Status

Accepted 2026-11-13. Part of [0022](0022-rewrite-in-go-as-a-standalone-product.md).

## Context

The product reaches most people as `materainc/x9-qrcode` on Docker Hub. The owner's intent is that
whoever pulls it gets lighter software that behaves as before.

Behaviour can be kept ([0028](0028-the-http-contract-is-frozen-and-equivalence-is-proved-by-a-differential-run.md)).
**Configuration cannot, as it stands.** Operators configure Java through:

- a Spring YAML ConfigMap selected by `SPRING_CONFIG_LOCATION` (Helm `values.yaml:74-75`);
- `secrets/application-secrets.yml` (`application.yml:81-83`);
- the Spring Cloud Kubernetes secrets API (`application.yml:145-149`);
- Jasypt `ENC(...)` values, in live use;
- `classpath:` URIs, and a **JKS** demo keystore;
- relaxed-binding environment variables (`X9_PUBLICENDPOINTS_HOST`, …).

Operations also see these today:

- actuator `include: "*"` on a separate management port;
- b3 tracing;
- Spring's log format;
- a chart with RollingUpdate and an HPA (maximum 5 replicas).

Separately, Liquibase 5.x is licensed FSL-1.1-ALv2, and this product ships its migration step to
third parties. 4.33.0 is the last Apache-2.0 release.

## Decision

**Same image name, same contract, a new major: 3.0.0. It is a documented configuration migration,
with a converter.**

**Kept:**

| Surface | Kept as |
|---|---|
| Image | `materainc/x9-qrcode`, multi-arch amd64 + arm64 |
| Ports | `8080` API; the management port as today |
| Contract | [0028](0028-the-http-contract-is-frozen-and-equivalence-is-proved-by-a-differential-run.md) |
| Health | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, same JSON shape |
| Metrics | `/actuator/prometheus`. The plan inventories every metric Java exports; each is kept under its name or listed as dropped |
| Environment variables | every documented variable keeps its name and meaning |
| Out-of-the-box run | the demo identity is bundled (as PKCS#12), so `docker run` works with no secrets |
| Provenance | the OCI `revision` label; tags `git-<sha>`, `latest`, `latest-arm64`, `latest-amd64` |

**Changed, explicitly, with tools:**

- **Configuration file.** One YAML file of Go keys, plus the same environment variables.
- **`x9-config-convert`** ships in the migrate image, `materainc/x9-qrcode:<version>-migrate` ([0030](0030-a-black-box-suite-defines-done-and-migration-writes-only-runtime-valid-rows.md)).
  It reads an operator's Spring YAML, `application-secrets.yml`, the Jasypt password and the
  keystores. It writes the Go config file, decrypts `ENC(...)` into plain secret files, and converts
  JKS to PKCS#12.
- **Refusing to start on old configuration.** Given Spring-style configuration or MongoDB
  variables, the binary refuses to start and names the converter.
- **Secrets** come from mounted files or the environment. The Kubernetes secrets API and Jasypt are
  gone.
- **Database:** PostgreSQL (`DATABASE_*`), with Liquibase run first as a one-shot. The image is the
  official `liquibase/liquibase:4.33.0` (Apache-2.0), pinned until Matera's legal review of FSL.
- **Actuator:** only health, prometheus and info are exposed. The other endpoints Java exposed with
  `include: "*"` are dropped, and listed in the upgrade notes.
- **Tracing:** incoming `b3` / `traceparent` ids are logged with every request. There is no exporter
  in 3.0.0.
- **Log format:** `slog` JSON; the field mapping is in the upgrade notes.
- **Instances:** **one.** The chart switches to `strategy: Recreate` and removes the HPA. Long-poll
  wake-ups are in-process ([0025](0025-a-transition-is-one-transaction-and-its-event-is-in-it.md)),
  and the licence allows one active instance anyway.

**Build, decided:** Go's own toolchain plus one small multi-stage `Dockerfile`.

- **Go side:** `go build`, `go test` and `go vet`. Dependencies are in `go.mod` / `go.sum`, and dev
  tools (linters, `govulncheck`) are pinned with the `tool` directive and run as `go tool`. It is
  one module with no Maven, no reactor, no build-init module and no vendored jar.
- **Image:** a multi-stage `Dockerfile` at the repository root.
  1. Stage 1 compiles with the pinned Go image: `CGO_ENABLED=0`, `-trimpath`, version and commit
     stamped through `-ldflags`.
  2. Stage 2 copies the binary and the bundled demo identity onto
     `gcr.io/distroless/static-debian13:nonroot`, pinned by digest.
- **Multi-arch:** `docker buildx` builds `linux/amd64` and `linux/arm64` and publishes one manifest.
  `org.opencontainers.image.revision` and the other OCI labels are set from build arguments.
- **The migrate image** (`<version>-migrate`) is a second target in the same `Dockerfile`, carrying
  `x9-migrate` and `x9-config-convert`.
- **The `Makefile` stays a thin wrapper.** `make build`, `make test`, `make image`,
  `make image-amd64` and `make up` keep their names, so muscle memory and the existing docs still
  work.
- **Rejected for the build:**
  - **Cloud Native Buildpacks:** heavier, opaque, and built around a JVM;
  - **`ko`:** elegant, but one more tool for contributors, and its defaults hide the base image
    choice;
  - **a single-stage image with the toolchain inside:** large, and it ships a compiler to
    production.

**Operations, decided:**

- **HTTP server timeouts:**
  - read-header 10 s;
  - read 30 s;
  - write 40 s (above the 30 s long-poll ceiling);
  - idle 120 s;
  - headers ≤ 30 KB, as today.
- **Graceful shutdown on SIGTERM:**
  1. readiness turns DOWN;
  2. stop accepting new requests;
  3. wake parked long-polls, which return their current page;
  4. let in-flight requests finish for up to 20 s;
  5. close the pools.

  The chart's `terminationGracePeriodSeconds` is above that.
- **Readiness** means both: the database is reachable, and the schema check passes
  ([0032](0032-x9150-can-live-in-the-banks-own-database.md)). **Liveness** means the process is
  serving.
- **Point-in-time restore of the database.** A consumer's cursor may then be ahead of the restored
  log. `after` greater than `last_seq` answers **409, "cursor ahead of log"**, with the current
  `last_seq`, so the consumer re-synchronises from the QR state instead of waiting forever.

**Tags:**

- The last Java build is tagged **`java-final`**, immutable.
- The Go line is **`3.x`** (`3.0.0`, …), alongside the existing tag scheme.
- `:latest` moves to Go only after a real cutover has succeeded.
- DOCKERHUB.md says in its first paragraph that 3.0.0 changes the database and the configuration,
  and links the upgrade guide.

## Consequences

- An operator upgrades by running two tools: the converter and the migrator. Their API consumers
  notice nothing.
- `java-final` keeps working for anyone not ready to move.
- Liquibase stays Apache-2.0 for now. A future major release needs an explicit licence decision.

## Alternatives rejected

- **A compatibility loader that reads Spring YAML in Go.** It keeps Spring's format alive forever,
  inside a codebase that has left Spring.
- **Silently moving `:latest`.** Every unpinned operator breaks on their next pull.
- **A new image name.** It contradicts the owner's intent.
- **Several active instances.** They would need a cross-instance wake-up, and the licence allows one.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
