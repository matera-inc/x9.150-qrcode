# Running & Testing — First-Run Guide

A step-by-step guide to build, run, and smoke-test the X9 QRCode backend for the first time —
locally on the JVM, or as a container. For endpoint/URL configuration see
[`ENDPOINTS.md`](ENDPOINTS.md).

> **Two builds.** The service is the Go build on PostgreSQL: see [The Go build](#the-go-build) and
> the README's [Running the Application](README.md#running-the-application). The sections from
> Prerequisites to Multi-arch images describe the Java build, on the JVM with MongoDB, which stays
> in this repository until it is frozen under `legacy/java/` ([HISTORY.md](HISTORY.md)). Its MongoDB
> notes are in [Java build (legacy): MongoDB](#java-build-legacy-mongodb). The JWS clock skew and
> the test clock apply to both builds.

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

## JWS clock skew

Every JWS this service verifies (a payment notification it receives, a payload it decodes, a payee's
answer to a notification it sends) must not be dated in the future. Its `iat` may be ahead of this
service's clock by at most the allowed skew, which forgives honest drift between two clocks.

| Variable | Property | Default | Meaning |
|---|---|---|---|
| `X9_JWS_CLOCK_SKEW_SECONDS` | `x9.public-endpoints.jws-clock-skew-seconds` | `60` | How many seconds ahead of this service's clock a JWS's `iat` may be. `0` allows no drift. |

Beyond the skew, a payment notification is refused with a signed `401`, and a payee's answer is
reported `INVALID` with `signatureProblem` `FUTURE_IAT`. An expired one (`iat + ttl` passed) is
refused the same way, with `EXPIRED`.

## Moving the clock (test deployments only)

Discount windows, `validUntil`, the reservation lapse and JWS `iat + ttl` all depend on "now". A
test deployment can shift its business clock over HTTP, so a scenario like "fetch at 23:59:50,
notify after midnight" runs in seconds
([ADR-0033](docs/adr/0033-the-clock-can-be-moved-in-test-deployments-only.md)).

| Variable | Default | Meaning |
|---|---|---|
| `X9_TESTING_CLOCKCONTROL` | `false` | Turns the clock control on. |
| `X9_TESTING_CLOCKLISTEN` | `127.0.0.1:8099` | `host:port` of the clock listener. Never the API port. |

```bash
X9_TESTING_CLOCKCONTROL=true make run-local    # or any other way of starting the app
curl -s localhost:8099/test/clock                                    # {"now":"…Z","offsetMillis":0}
curl -s -X PUT localhost:8099/test/clock -d '{"at":"2031-12-31T23:59:59.999Z"}'
curl -s -X PUT localhost:8099/test/clock -d '{"offsetMillis":86400000}'
curl -s -X PUT localhost:8099/test/clock -d '{"offsetSeconds":86400}'  # the same offset, in seconds
curl -s -X DELETE localhost:8099/test/clock                          # back to real time
```

- **The offset is in milliseconds.** `PUT` takes exactly one of `at` (an RFC 3339 instant, kept to
  the millisecond; finer than that is a 400), `offsetMillis` or `offsetSeconds`. Every answer is
  `{"now", "offsetMillis"}`.
- **The clock keeps ticking.** It is the real clock plus the offset; it is never frozen. Straight
  after `PUT {"at": T}` it reads T plus less than a millisecond, never earlier than T.
- **Edges to the millisecond.** For a rule that ends at an instant E, `PUT {"at": E + 1 ms}` and
  every request after it is judged after E, so "refused at E + 1 ms" is exact. "Accepted at E" can be
  driven only as "accepted shortly before E": the clock goes on ticking while the request travels, so
  no request is judged at exactly E. Unit tests pin E itself.
- **`/test/clock` is never on the API port.** It is a separate listener and is not in `openapi.yaml`.
  With the flag off, nothing listens on the clock address and `/test/clock` on the API port is 404.
- **Real keys refuse it.** With the flag on, the app refuses to start unless its signing certificate
  is one this repository ships with its private key: the demo certificate, or any certificate under
  `x9-qrcode-infrastructure/src/test/resources/certificate` — the revoked and untrusted test fixtures
  included, since their keys are just as public. It is checked by SHA-256 fingerprint compiled into
  the code, so no configuration value can satisfy it. Any other certificate, real or self-made, is
  refused.
- **It is visible.** A `WARN` is logged at startup, and while the offset is non-zero every API
  response carries `X-Test-Clock-Offset: <milliseconds>` (signed; `86400000` is one day ahead,
  `-3600000` one hour behind).
- **MongoDB's TTL monitor runs on the real clock.** A moved clock never deletes an expired document.
- **Certificate validity stays on the real clock, on purpose.** Whether a certificate is within its
  `notBefore`/`notAfter` is a security check, not a business rule, so the JDK's PKIX chain validation
  never sees the offset: a clock moved past a certificate's `notAfter` does not make it expire, and a
  clock moved back does not revive an expired one. Only the JWS `iat` and `iat + ttl` follow the
  business clock.
- Request logging, the event-drain lease and long-poll waits measure real durations and keep the
  real clock.
- **Both builds serve it the same way.** The Go build (`internal/testclock`) takes the same
  variables, routes, bodies, units, header and guard. Its guard accepts every certificate bundled
  with its private key in this repository, by fingerprint compiled into the binary; that includes
  four test-CA leaves (`x9-test-payer-no-revocation`, `-crl-unreachable`, `-ocsp-unreachable` and
  `-both-unreachable`) that the Java build's list predates.

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

## The Go build

The Go + PostgreSQL build ([ADR-0022](docs/adr/0022-rewrite-in-go-as-a-standalone-product.md))
serves the whole HTTP contract, and the whole black-box suite runs against it. The image on Docker
Hub stays the Java build until the cutover. It needs Go (the version `go.mod` pins; with the default `GOTOOLCHAIN=auto`, `go`
fetches it).

```bash
go test ./...                     # unit tests
go tool golangci-lint run ./...   # lint (.golangci.yml; the linter is pinned in go.mod)
others/scripts/check-go-lint-planted.sh   # proves each lint rule still fires (testdata/lint)
make go-build                     # static binary in bin/x9-qrcode, version and commit stamped
make go-test                      # the tests, as CI runs them
make go-image                     # image x9-qrcode:go for this machine (never pushed)
make go-image-smoke               # non-root, health UP against PostgreSQL, exit 0 on SIGTERM, size
make go-image-multiarch           # amd64 + arm64 with docker buildx (no push)
make go-up                        # docker-compose.go.yml: PostgreSQL 16, Liquibase, then the service on
                                  # :8080 with the demo identity; waits for health UP (make go-down)
make go-compose-smoke             # the same on its own project: health, one create, then down
make helm-check                   # the chart, both builds: lint, template, no Java variable for Go
others/scripts/ci-go.sh           # everything CI's `go` job runs: vet, lint, nilaway, govulncheck,
                                  # go-licenses, race + shuffle + leak checks, fuzz smoke,
                                  # reproducible build, SBOM. `ci-go.sh lint test` runs just those.
# against the throwaway database of db/docker-compose.yml (it trusts every local connection):
DATABASE_HOST=127.0.0.1 DATABASE_PORT=55432 DATABASE_NAME=postgres DATABASE_USER=x9_qrcode_app \
DATABASE_PASSWORD=unused DATABASE_SSLMODE=disable go run ./cmd/x9-qrcode
```

**Configuration** is one YAML file plus environment variables, which take precedence
([ADR-0031](docs/adr/0031-same-image-lighter-and-3-0-is-a-configuration-migration.md)).
[`cmd/x9-qrcode/config.example.yaml`](cmd/x9-qrcode/config.example.yaml) lists every key and
the variable beside it. Pass the file with `-config <path>` or `X9_CONFIG_FILE`.

- **Everything is checked at start.** A missing or invalid key stops the service with an error
  naming the key and its variable, all problems at once. Unknown keys are refused too.
- **The public host defaults to Java's placeholder.** `X9_PUBLICENDPOINTS_HOST` (or
  `public-endpoints.host`) unset is `x9-150.example.com`, as in the Java build's default profile;
  set it to your own host for real scans. While it is the placeholder, start-up logs a `WARN`
  saying that QR Codes printed with it cannot be scanned and naming `X9_PUBLICENDPOINTS_HOST`. Set
  to empty, it stops the service at start, as in Java. It must be at most 37 characters, printable
  ASCII, no space. The host is written into a QR Code's EMV string once, with its location, and
  never recomputed: changing it later does not change a QR Code already printed.
- **Nothing security-relevant has a default.** The database host, user, credential
  (`DATABASE_PASSWORD` or `DATABASE_PASSWORD_FILE`) and TLS mode (`DATABASE_SSLMODE`) must be
  given; `verify-full` also needs `DATABASE_SSLROOTCERT`.
- **The Java build's configuration is refused, not half-read.** A Spring-style file (`spring:`,
  `management:` or `x9:` at the top, Jasypt `ENC(…)`, `classpath:`, `${…}` placeholders, a
  `.properties` file) or any `SPRING_*`, `MONGO*` or `JASYPT_*` variable stops the service and
  names [`docs/CONFIGURATION-FROM-JAVA.md`](docs/CONFIGURATION-FROM-JAVA.md), which maps every
  Java setting to its Go key and variable (there is no converter,
  [ADR-0038](docs/adr/0038-no-data-migration-from-the-java-build-a-written-mapping-instead.md)).
- **Logs are JSON** (`log/slog`), one line per request with `method`, `path`, `status` and
  `durationMs`. An incoming `b3`, `X-B3-TraceId`/`X-B3-SpanId` or `traceparent` header adds
  `traceId` and `spanId`. The ids are logged only; nothing is exported.
- **The database.** Two pools on the primary, writes (8) and reads (4), named `x9-qrcode` in
  `pg_stat_activity`. At start the service checks it is connected to the configured schema and
  that the changelog applied there is the one it expects, and refuses to start otherwise, saying
  which ([`db/README.md`](db/README.md)). While the database is unreachable it keeps retrying.
  `go test ./...` starts PostgreSQL 16, Liquibase 4.33.0 and PgBouncer 1.21 with Docker
  (testcontainers); `go test -short ./...` skips those tests.
- **Health, as before.** `/actuator/health`, `/actuator/health/liveness` and
  `/actuator/health/readiness` answer on the API port in the Java build's JSON shape
  (`{"status":"UP"}`; the aggregate adds `"groups":["liveness","readiness"]`). Liveness is UP while
  the process serves. Readiness is DOWN (503) until the schema check passes, while the database is
  unreachable, and from the moment shutdown begins.
- **Graceful shutdown.** On SIGTERM readiness turns DOWN, the listener keeps serving for
  `X9_SERVER_SHUTDOWNDELAYSECONDS` (default 5) so load balancers see it leave, then stops accepting;
  requests in flight get up to 20 s to finish; the pools close; the process exits 0. A pod's
  `terminationGracePeriodSeconds` must be above the sum (30 s is enough).
- **Retention, the one background job** ([ADR-0029](docs/adr/0029-time-is-applied-on-read-and-retention-is-the-only-job.md)).
  Once the schema check passes, and then `X9_RETENTION_INTERVAL` (`retention.interval`, default
  `PT1H`) after the end of each run, the service takes the business clock once and deletes, in
  batches of 500 rows, one transaction each with a 100 ms pause between them:
  1. the events older than `X9_EVENTS_RETENTION` (`events.retention`, default `P30D`);
  2. then the QR Codes whose `validUntil` **and** last revision are both older than
     `X9_QRCODE_RETENTION` (`qrcode.retention`, default `P400D`), in any status, with their audit
     log and stored notifications.

  The first run is at start, not one interval later, so a service restarted more often than the
  interval still purges. Durations are ISO-8601 days, hours, minutes and seconds (`P30D`, `PT1H`). It never changes a
  status, skips a QR Code a request is changing (the next run takes it), and stops on SIGTERM
  before the pools close. The service refuses to start if `qrcode.retention` is shorter than
  `events.retention`, naming both. **What shortening them gives up:** an expired QR Code is
  readable, with its expiry reason, and can still be marked `PAID`, only until `qrcode.retention`;
  after that it is a 404 like one that never existed. An event is in `GET /pub/api/v1/events` and
  in `payment_event` only until `events.retention`, so a consumer must read within it.
  **Sizing:** [BENCHMARK.md](BENCHMARK.md#database) measured about 15 KB of PostgreSQL per payment
  (the QR Code ~1.4 KB, its signed notifications ~12.6 KB, events and indexes the rest), nearly all
  of it kept for `qrcode.retention`: 10,000 payments a day at `P400D` is about 60 GB.
  Each run logs one `x9-qrcode retention run` line (`INFO`) with its two cutoffs, the rows deleted
  per table and `durationMs`; a failed run logs `ERROR` and is retried at the next tick.
- **Metrics.** `GET /actuator/prometheus`, on the API port, serves the Prometheus text format.
  The Java build's request timer (`http_server_requests_seconds`, labelled by route template),
  process gauges and log-event counter keep their names, types and labels; the JVM, Tomcat and
  MongoDB driver families are replaced by Go runtime and connection-pool families, or dropped. The
  Go build adds the retention job's counters (`x9_retention_deleted_rows_total{table="…"}`,
  `x9_retention_runs_total`, `x9_retention_runs_failed_total`). Every family, kept, replaced or
  dropped, and why: [docs/METRICS.md](docs/METRICS.md).
- **Info.** `GET /actuator/info` answers the version and commit the binary was stamped with:
  `{"build":{"name":"x9-qrcode","version":"…"},"git":{"commit":{"id":"…"}}}`. Every other
  actuator endpoint of the Java build is gone ([docs/METRICS.md](docs/METRICS.md#the-other-actuator-endpoints)).
- **The image.** A multi-stage [`Dockerfile`](Dockerfile): the pinned Go image cross-compiles a
  static binary (`CGO_ENABLED=0`, `-trimpath`, version and commit from `-ldflags`) onto
  `gcr.io/distroless/static-debian13:nonroot`, both pinned by digest. It runs as user 65532, has
  no shell, and is about 16 MB unpacked, 5.5 MB compressed. CI builds it for amd64 and arm64 and
  records its sizes (read from `docker save` by `others/scripts/image-size.py`) and cold start in
  the `go image` job's summary. Without `docker buildx`, `make go-image` falls back to
  the legacy builder for this machine's architecture. The Java image (`make image`) is unchanged,
  and no Go image is published before the Go build ships.
- **One clock.** Business time comes from `internal/clock` only
  ([ADR-0033](docs/adr/0033-the-clock-can-be-moved-in-test-deployments-only.md)). Lint refuses
  `time.Now`, `Since`, `Until`, `After`, `Tick` and `NewTimer` anywhere else, except at a
  `//nolint:forbidigo` that says which real duration it measures. The clock supports a
  millisecond offset, moved through `/test/clock` (`internal/testclock`) in test deployments only,
  as described in [Moving the clock](#moving-the-clock-test-deployments-only).
- **Signatures on the wire.** The Go build publishes its signing key at
  `/pub/.well-known/jwks` (a JWK set with the `x5c` chain, in the Java build's shape) and
  `/pub/.well-known/certificate/x9-key.pem` (the chain in PEM; the name every `x5u` gives it,
  and the only one served), and answers `POST /api/v1/signature/generate` and
  `/api/v1/signature/validate` as the Java build does. Both follow a keystore reload. A received
  JWS is verified with `X9_JWS_CLOCK_SKEW_SECONDS` (60; `0` allows no drift, a negative value is
  read as `0`) and `X9_CERTIFICATE_ISSUERNAME` (`X9`; empty skips the issuer check), the Java
  build's settings. A `jku` or `x5u` on the public host is read from the process itself over
  `localhost`, as Java reads it; any other is fetched over HTTPS only.
- **The payload a payer fetches.** `POST /pub/api/v1/loc/{id}` takes the payer's signed request
  (`application/jose`) and answers the signed payload, as the Java build does: priced at the later
  of the `dateForPayment` header's UTC start and now, with only the payment methods still valid at
  that instant, and `sentAt` the business clock's now (never before `revisedAt`). A request that
  does not verify, or whose content names another location, is a 400 with one generic reason
  (never a 401); content that is well formed but not the EMV string issued is a 400 naming
  `qrCodeContent`; a PAID or CANCELLED QR Code is refused naming which. An expired one is refused
  with its expiry reason, because the Go build keeps the row (ADR-0029), where the Java build
  answers the generic reason once MongoDB has deleted it. Nothing is written.

- **Payment notifications.** `POST /pub/api/v1/payment-notification` takes a payer's signed
  notification on every rail (INTERPRETATION I-15) and always answers with a JWS signed by this
  payee (`application/jose`, `typ: JOSE`, `statusCode` in the header and the body): a yes is
  `{"statusCode":200}` echoing the notification's `correlationId`; a refusal keeps its status and
  its error fields. One that does not verify is a signed 401 carrying only the fixed
  `signatureProblem` code, under a fresh `correlationId`; a wrong media type, an empty body and a
  failure are signed too. What the QR Code cannot take is refused by name before any transition
  (no `paymentNotification`, `kind: EXTERNAL`, a rail or currency it does not offer, `blockchain`
  on a bank rail, ACH without `payer.info` or `expectedDate`); the rest is decided by the
  transition map, in one transaction with the stored notification and its events.
- **Signing** (`internal/jws`) uses the Java build's settings and
  header profile: `X9_CERTIFICATE_JWKALGORITHM` (default `PS512`; RS\*, PS\*, or ES256/ES384/ES512
  each on its own curve), `X9_CERTIFICATE_ENDPOINTTYPE` (`JWK_SET` → `jku`, the default; `PEM` →
  `x5u`; `NONE` → `x5c`) and `X9_PUBLICENDPOINTS_JWSTTLSECONDS` (300, written as `ttl: 300000`
  ms). An algorithm the signing key cannot produce stops the service at start. Its tests check
  Go's tokens with the black-box suite's own verifier (`others/blackbox/x9client`) when
  `.blackbox-venv` exists (`make blackbox-venv`) or `X9_BLACKBOX_PYTHON` names an interpreter with
  the suite's dependencies; CI always runs it.

## Java build (legacy): MongoDB

This section concerns only the Java build. The Go build runs on PostgreSQL (see
[`db/README.md`](db/README.md)).

MongoDB Community is free to run in Docker. The compose files use the official **`mongo`** image
started as a **single-node replica set** (`mongod --replSet`) — the minimum required for the
transactions this app uses. `docker-compose*.yml` set that up for you (`rs.initiate` on a 1-node
set), so nothing further is needed for local testing.

> **The MongoDB tag is pinned to `mongo:7.0.45`, and not simply out of caution.**
> MongoDB **8.x refuses to start on Linux kernels 6.19 and newer** — a deliberate startup check
> rather than a crash: its bundled tcmalloc violates the kernel's rseq ABI, so it exits instead of
> risking corruption ([SERVER-121912](https://jira.mongodb.org/browse/SERVER-121912)). 7.0 links a
> different allocator, has no rseq references and no upper kernel bound, so one tag serves old and
> new hosts. That kernel is not exotic — on Ubuntu 24.04 it arrives through the HWE track, which
> rolls forward on its own.
>
> The cost is stated plainly: MongoDB 7.0 is supported until **2027-08-31**, against 2029-10-31 for
> 8.0. For a deployment whose job is to start on a stranger's machine that is the right trade today,
> since an 8.x that will not boot has no support value either. Revisit once upstream fixes the
> allocator.
>
> **If you already have MongoDB 8.x data on disk, do not simply change the tag.** MongoDB does not
> support an in-place major *downgrade* once a server has raised the data directory's feature
> compatibility version, so a 7.0 server started against it can refuse to come up — and the service
> is then down. The supported route is `mongodump` → wipe → restore, and it has to be done **while
> 8.x still starts**. Check `featureCompatibilityVersion` before you change anything.
>
> Running your own MongoDB (the Helm chart expects an external one) puts the same kernel
> requirement on you.

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
