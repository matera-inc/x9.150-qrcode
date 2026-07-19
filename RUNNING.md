# Running & Testing — First-Run Guide

A step-by-step guide to build, run, and smoke-test the X9 QRCode backend for the first time —
locally on the JVM, or as a container. For endpoint/URL configuration see
[`ENDPOINTS.md`](ENDPOINTS.md).

## Prerequisites

| Need | Why | Notes |
|------|-----|-------|
| **JDK 25** | The code targets Java 25 | `java -version` should report 25 (a newer JDK that supports `--release 25` also works) |
| **Maven** | Build tool | **Bundled** — use `./mvnw`; you do *not* need Maven pre-installed |
| **Docker** | Container image + Compose, and the easiest way to get MongoDB | Docker Desktop or any engine with `docker compose` |
| **MongoDB (replica set)** | The app uses multi-document transactions, which require a replica set | The Compose files start a single-node replica set for you |

> **Why a replica set?** MongoDB transactions only work on a replica set — even a single node.
> A plain `mongod` will not do. The provided Compose files initialize a 1-node set automatically.

## TL;DR (Makefile)

```bash
make help        # list all targets
make build       # compile + test + install all modules
make up          # start app + MongoDB (docker-compose.yml)
make smoke       # liveness check against http://localhost:8080
make down        # stop everything
```

`make` just wraps the `./mvnw` and `docker compose` commands shown below — use whichever you prefer.

## Option A — Run on the host JVM (fastest inner loop)

Good for development: hot rebuilds, debugger, breakpoints. The app runs on your machine; only
MongoDB runs in Docker.

```bash
# 1. Start just MongoDB as a single-node replica set
docker compose up -d mongo mongo-setup

# 2. Build once, then run the Spring Boot app on your JVM
./mvnw clean install
./mvnw -pl x9-qrcode-infrastructure spring-boot:run
#   → app on http://localhost:8080   (make run-local does steps 2's run)
```

The app starts on the `default` profile and uses the bundled self-signed sample keystore, so it
runs out of the box. To point the public URLs at your own host, set `X9_PUBLICENDPOINTS_HOST`
(see [`ENDPOINTS.md`](ENDPOINTS.md)).

## Option B — Everything in Docker (Compose)

Good for a self-contained run that mirrors a deployment.

```bash
# 1. Build the container image (buildpacks — no Dockerfile). Produces x9-qrcode:latest
./mvnw -Pdocker -DskipTests clean package      # or: make image

# 2a. Local run — app + MongoDB + mongo-express UI
docker compose up -d                           # or: make up

# 2b. Or the env-driven, self-contained run (all config as environment variables)
cp .env.sample .env                            # optional: customize
docker compose -f docker-compose.prod.yml up -d # or: make up-prod
```

- App: <http://localhost:8080> (health: `/actuator/health`)
- MongoDB: `localhost:27017` (replica set `x9-qrcode`)
- `docker-compose.yml` also starts **mongo-express** at <http://localhost:9091> (admin/admin)

All configuration can be defined at launch via environment variables — see
**[Configuring at launch](ENDPOINTS.md#configuring-at-launch-docker-compose)**.

## Building the container image

The image is produced by **Cloud Native Buildpacks** via `spring-boot:build-image` (bound to the
`docker` Maven profile) — there is **no Dockerfile**. The result is a small layered image:
Linux (Ubuntu noble) + a JRE + your app as Spring Boot layered jars. No JDK or build tools ship
inside it.

```bash
./mvnw -Pdocker -DskipTests clean package      # → x9-qrcode:latest   (make image)
```

## Smoke test

With the app running on `:8080`:

```bash
make smoke                                     # health + public JWKS
# equivalently:
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/pub/.well-known/jwks
```

To exercise the full create → fetch flow, POST a payment request and then fetch its payload. The
easiest starting point is the Postman collection at
[`others/postman/postman_collection.json`](others/postman/postman_collection.json), or the sample
request bodies under
`x9-qrcode-infrastructure/src/test/resources/payment-requests/`. The API is open and
unauthenticated, so no bearer token is needed. Access control is intentionally out of scope —
protect the service at your edge (API gateway, mTLS, or network policy).

## Multi-arch images (arm64 + amd64) & publishing

The image is registry-agnostic — publish it wherever you like (a private registry, GHCR, Docker
Hub, etc.); nothing is imposed. Buildpacks build **one architecture per run**, matching the Docker
daemon (or an explicit `imagePlatform`):

```bash
make image           # native arch → x9-qrcode:latest       (arm64 on Apple Silicon)
make image-amd64     # amd64/intel → x9-qrcode:latest-amd64  (uses Rosetta on Apple Silicon)
```

> Building amd64 on Apple Silicon needs **Rosetta** in your Docker/Colima VM. Verify with
> `docker run --rm --platform=linux/amd64 alpine uname -m` → it should print `x86_64`.

**Publishing is left to you** — the repo imposes no registry or flow. `make publish` is an
intentional stub (an extension point): drop your own publish script there. A typical single-arch
push is just:

```bash
docker login your.registry.example
docker tag x9-qrcode:latest your.registry.example/you/x9-qrcode:latest
docker push your.registry.example/you/x9-qrcode:latest
```

For a **multi-arch** tag, build each arch to its own tag, push both, and combine them into one
manifest (so a plain `docker pull …:latest` auto-selects the architecture):

```bash
make image        && docker tag x9-qrcode:latest       REPO:latest-arm64 && docker push REPO:latest-arm64
make image-amd64  && docker tag x9-qrcode:latest-amd64 REPO:latest-amd64 && docker push REPO:latest-amd64
docker manifest create REPO:latest REPO:latest-arm64 REPO:latest-amd64
docker manifest push   REPO:latest
```

## Troubleshooting

| Symptom | Likely cause / fix |
|---------|--------------------|
| Startup fails with a transactions/replica-set error | MongoDB isn't a replica set. Use the Compose `mongo` + `mongo-setup` services (they run `rs.initiate`). |
| `release version 25 not supported` | JDK older than 25. Check `java -version`; install a JDK 25. |
| App starts but a phone can't reach the QR URL | The QR embeds `x9.public-endpoints.host`, which defaults to a placeholder. Point it at a reachable host (or a tunnel) — see [`ENDPOINTS.md`](ENDPOINTS.md#testing-a-real-scan-computer--phone-on-5g-cloudflare-tunnel). |
| Startup fails: payload host too long | The host must be ≤ 37 chars so the loc URL fits the EMV field (see [`ENDPOINTS.md`](ENDPOINTS.md#host-length-constraint)). |
| `docker compose` image not found | Build it first: `make image` (or `./mvnw -Pdocker -DskipTests clean package`). |
| Port 8080/27017 already in use | Stop the conflicting process, or change the published ports in the Compose file. |

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
