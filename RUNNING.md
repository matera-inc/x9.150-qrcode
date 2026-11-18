# Running & Testing — First-Run Guide

A step-by-step guide to build, run, and smoke-test the X9 QRCode backend for the first time — with
Docker Compose, as a binary on your machine, or on Kubernetes. For endpoint/URL configuration see
[`ENDPOINTS.md`](ENDPOINTS.md).

> **One build.** The service is the Go build on PostgreSQL
> ([ADR-0022](docs/adr/0022-rewrite-in-go-as-a-standalone-product.md)). The Java build on MongoDB
> was retired at 3.0.0 ([ADR-0039](docs/adr/0039-java-retired-at-3-0-0.md)): it is in the git
> history under the tag `java-final`, and on Docker Hub as `materainc/x9-qrcode:java-final`.
> Moving a 2.x (Java) deployment to 3.0.0: [UPGRADING-TO-3.md](UPGRADING-TO-3.md).

## Prerequisites

| Need | Why | Notes |
|------|-----|-------|
| **Docker** | The image, Compose, and the database tests | Any engine with `docker compose` v2; `docker buildx` for both architectures |
| **Go** | To build and test outside a container | The version `go.mod` pins (`toolchain go1.27.4`); with the default `GOTOOLCHAIN=auto`, `go` fetches it |
| **PostgreSQL 16 or newer** | The only database | Compose starts one for you; see [`db/README.md`](db/README.md) for your own |
| **Python 3** | The black-box suite and a few scripts | `make blackbox-venv` installs the suite's pinned dependencies |

The schema is applied by **Liquibase, before the service starts**, never by the service itself. Every
way of running below does that step for you.

## TL;DR (Makefile)

```bash
make help        # list all targets
make up          # build the image; PostgreSQL 16, Liquibase, then the service on :8080; waits for health UP
make smoke       # health + public JWKS against http://localhost:8080
make down        # stop everything and throw the database away
```

`make` wraps the `go`, `docker` and `docker compose` commands shown below — use whichever you prefer.

## Option A — Everything in Docker (Compose)

Good for a self-contained run that mirrors a deployment. [`docker-compose.yml`](docker-compose.yml)
starts, in order: PostgreSQL 16, the DBA's one-time bootstrap (the owner role and schema), Liquibase
applying [`db/changelog/`](db/changelog/), then the service, connected as the application role.

```bash
make up                                        # builds x9-qrcode:latest with `make image`, then starts
cp .env.sample .env                            # optional: host, port, log level, an existing image
X9_IMAGE=materainc/x9-qrcode:3 make up         # run a published image instead of building one
docker compose up -d --build --wait            # the same by hand (needs BuildKit/buildx)
make down                                      # stop; the database volume is removed
```

- App: <http://localhost:8080> (health: `/actuator/health`), published on `127.0.0.1` only.
- The database is not published to the host; look at it with
  `docker compose exec postgres psql -U x9_qrcode_readonly postgres`.
- It signs with the bundled demo identity (EC P-256, `ES256`; NON-PRODUCTION) and trusts every
  connection on the Compose network: local use only.

`make compose-smoke` brings the same file up on its own project and a free port, checks health and
one create, and takes it down — what CI's `compose` job runs.

## Option B — The binary on your machine (fastest inner loop)

Good for development: a debugger, quick rebuilds. Only PostgreSQL runs in Docker.

```bash
# 1. A throwaway PostgreSQL 16 on 127.0.0.1:55432, with the schema applied (trusts every local connection)
docker compose -f db/docker-compose.yml up -d --build

# 2. The service, as the application role
export DATABASE_HOST=127.0.0.1 DATABASE_PORT=55432 DATABASE_NAME=postgres \
       DATABASE_USER=x9_qrcode_app DATABASE_PASSWORD=unused DATABASE_SSLMODE=disable
make run-local                                 # builds bin/x9-qrcode, then runs it
go run ./cmd/x9-qrcode                         # or straight from source
#   → API on http://localhost:8080

# 3. Throw the database away when you are done
docker compose -f db/docker-compose.yml down -v
```

The service uses the bundled demo signing key and truststore, so it runs out of the box. To point
the public URLs at your own host, set `X9_PUBLICENDPOINTS_HOST` (see [`ENDPOINTS.md`](ENDPOINTS.md)).

## Option C — Kubernetes (Helm)

The chart under [`others/helm/x9-qrcode/`](others/helm/x9-qrcode/README.md) deploys the service
with Liquibase as an init container, against a PostgreSQL you provide. It needs the public host,
`go.database.host` and `go.database.name`, and a Secret holding the database password; keep one
active instance (see [High Availability](HIGH-AVAILABILITY.md)). `make helm-check` lints and
renders it.

## Building, testing and the image

```bash
make build                        # static binary in bin/x9-qrcode, version and commit stamped
make test                         # the Go tests as CI runs them: race, shuffled, leak-checked
                                  # (the database tests start PostgreSQL with Docker)
go test -short ./...              # skips the tests that need Docker
go tool golangci-lint run ./...   # lint (.golangci.yml; the linter is pinned in go.mod)
others/scripts/check-go-lint-planted.sh   # proves each lint rule still fires (testdata/lint)
others/scripts/ci-go.sh           # everything CI's `go` job runs: vet, lint, nilaway, govulncheck,
                                  # go-licenses, race + shuffle + leak checks, fuzz smoke,
                                  # reproducible build, SBOM. `ci-go.sh lint test` runs just those.
make image                        # image x9-qrcode:latest for this machine's architecture (never pushed)
make image-smoke                  # the image: non-root, health UP against PostgreSQL, exit 0 on SIGTERM, size
make image-multiarch              # amd64 + arm64 with docker buildx (no push, no load)
```

The image is a multi-stage [`Dockerfile`](Dockerfile): the pinned Go image cross-compiles a static
binary (`CGO_ENABLED=0`, `-trimpath`, version and commit from `-ldflags`) onto
`gcr.io/distroless/static-debian13:nonroot`, both pinned by digest. It runs as user 65532, has no
shell, and is about 16 MB unpacked, 5.5 MB compressed. CI builds it for amd64 and arm64 and records
its sizes (read from `docker save` by `others/scripts/image-size.py`) and cold start in the
`go image` job's summary. Without `docker buildx`, `make image` falls back to the legacy builder
for this machine's architecture.

## Smoke test

With the service running on `:8080`:

```bash
make smoke                                     # health + public JWKS
# equivalently:
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/pub/.well-known/jwks
```

To exercise the full create → fetch flow, POST a payment request and then fetch its payload. The
easiest starting points are the sample request bodies under [`playground/requests/`](playground/requests/)
and the Postman collection at [`others/postman/postman_collection.json`](others/postman/postman_collection.json):

```bash
curl -sS -H 'Content-Type: application/json' --data-binary @playground/requests/qr-burger-createqr.json \
  http://localhost:8080/api/v1/payment-request
```

The API is open and unauthenticated, so no bearer token is needed. Access control is intentionally
out of scope — protect the service at your edge (API gateway, mTLS, or network policy).

## The black-box suite

The suite under [`others/blackbox/`](others/blackbox/README.md) drives the service over HTTP only,
signing as independent payers.

```bash
make blackbox-image                            # build nothing; run it against x9-qrcode:latest, all in containers
make blackbox-image IMAGE=materainc/x9-qrcode:3   # against another image
make blackbox                                  # against a service already running on X9_BASE_URL
```

`make blackbox-image` is what CI's `blackbox-go` job runs: PostgreSQL 16, the schema bootstrap,
Liquibase, the image with the test clock on and the black-box truststore, and the suite in the
service's network namespace. A suite that could not run fails, never skips green.

## Publishing the image

`make publish` (`others/scripts/publish-image.sh`) builds amd64 and arm64 with `docker buildx`,
runs the image smoke test and the whole black-box suite against each architecture, and only then
pushes `git-<sha>`, the version tags when `VERSION` is given (`VERSION=3.0.0` → `3.0.0`, `3.0`, `3`)
and `latest` (unless `make publish-keep-latest`), one multi-arch manifest per tag.
`make publish-dry-run` does everything but the push, from any branch. The registry defaults to
`materainc/x9-qrcode`; set `REGISTRY_REPO` to publish anywhere else.

## Configuration

**Configuration** is one YAML file plus environment variables, which take precedence
([ADR-0031](docs/adr/0031-same-image-lighter-and-3-0-is-a-configuration-migration.md)).
[`cmd/x9-qrcode/config.example.yaml`](cmd/x9-qrcode/config.example.yaml) lists every key and
the variable beside it. Pass the file with `-config <path>` or `X9_CONFIG_FILE`.

- **Everything is checked at start.** A missing or invalid key stops the service with an error
  naming the key and its variable, all problems at once. Unknown keys are refused too.
- **The public host defaults to a placeholder.** `X9_PUBLICENDPOINTS_HOST` (or
  `public-endpoints.host`) unset is `x9-150.example.com`; set it to your own host for real scans.
  While it is the placeholder, start-up logs a `WARN` saying that QR Codes printed with it cannot be
  scanned and naming `X9_PUBLICENDPOINTS_HOST`. Set to empty, it stops the service at start. It must
  be at most 37 characters, printable ASCII, no space. The host is written into a QR Code's EMV
  string once, with its location, and never recomputed: changing it later does not change a QR Code
  already printed.
- **Nothing security-relevant has a default.** The database host, user, credential
  (`DATABASE_PASSWORD` or `DATABASE_PASSWORD_FILE`) and TLS mode (`DATABASE_SSLMODE`) must be
  given; `verify-full` also needs `DATABASE_SSLROOTCERT`.
- **Keys and certificates** are PEM or PKCS#12 files (`X9_CERTIFICATE_PRIVATEKEYSTORE_*`,
  `X9_CERTIFICATE_TRUSTSTORE_*`); a JKS file is refused, naming the `keytool -importkeystore` command
  that converts it. Empty, the bundled demo identity is used: EC P-256, signing `ES256` unless
  told otherwise (NON-PRODUCTION: its private key is public).
- **The Java build's configuration is refused, not half-read.** A Spring-style file (`spring:`,
  `management:` or `x9:` at the top, Jasypt `ENC(…)`, `classpath:`, `${…}` placeholders, a
  `.properties` file) or any `SPRING_*`, `MONGO*` or `JASYPT_*` variable stops the service and
  names [`docs/CONFIGURATION-FROM-JAVA.md`](docs/CONFIGURATION-FROM-JAVA.md), which maps every
  2.x setting to its key and variable (there is no converter,
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
- **Signing and verifying take turns on the CPU.** At most `GOMAXPROCS` signatures and
  verifications run at a time, each one whole (a verification from the compact form's split and
  the certificates' parsing to the signature check); the others wait their turn, and a request that
  ends while waiting gives up. A verification gives its turn back while a certificate, CRL or OCSP
  answer is fetched, and waits for it again after. Without the bound, hundreds of goroutines doing
  JWS work crowd out those that hold a write connection between round trips, and the 8-connection
  write pool, not the CPU, becomes the bottleneck. At 500 virtual users on 2 vCPU, the full payment
  cycle went from 779 req/s and a median of about 90 ms to about 810 req/s and 61–70 ms; a bound
  around the signature arithmetic alone did not help. Go reads the container's CPU limit into
  `GOMAXPROCS`; set the `GOMAXPROCS` variable only to override it. Why, in detail:
  [`internal/cpu`](internal/cpu/cpu.go).
- **Health.** `/actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness`
  answer on the API port (`{"status":"UP"}`; the aggregate adds `"groups":["liveness","readiness"]`,
  the shape the Java build answered). Liveness is UP while the process serves. Readiness is DOWN
  (503) until the schema check passes, while the database is unreachable, and from the moment
  shutdown begins.
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
- **Metrics.** `GET /actuator/prometheus`, on the API port, serves the Prometheus text format: the
  request timer (`http_server_requests_seconds`, labelled by route template), process gauges, the
  log-event counter, Go runtime and connection-pool families, and the retention job's counters
  (`x9_retention_deleted_rows_total{table="…"}`, `x9_retention_runs_total`,
  `x9_retention_runs_failed_total`). Every family, and how it relates to what the Java build
  exported: [docs/METRICS.md](docs/METRICS.md).
- **Info.** `GET /actuator/info` answers the version and commit the binary was stamped with:
  `{"build":{"name":"x9-qrcode","version":"…"},"git":{"commit":{"id":"…"}}}`. There are no other
  actuator endpoints ([docs/METRICS.md](docs/METRICS.md#the-other-actuator-endpoints)).
- **One clock.** Business time comes from `internal/clock` only
  ([ADR-0033](docs/adr/0033-the-clock-can-be-moved-in-test-deployments-only.md)). Lint refuses
  `time.Now`, `Since`, `Until`, `After`, `Tick` and `NewTimer` anywhere else, except at a
  `//nolint:forbidigo` that says which real duration it measures. The clock supports a
  millisecond offset, moved through `/test/clock` (`internal/testclock`) in test deployments only,
  as described in [Moving the clock](#moving-the-clock-test-deployments-only).
- **Signatures on the wire.** The service publishes its signing key at
  `/pub/.well-known/jwks` (a JWK set with the `x5c` chain) and
  `/pub/.well-known/certificate/x9-key.pem` (the chain in PEM; the name every `x5u` gives it,
  and the only one served), and answers `POST /api/v1/signature/generate` and
  `/api/v1/signature/validate`. Both follow a keystore reload. A received JWS is verified with
  `X9_JWS_CLOCK_SKEW_SECONDS` (60; `0` allows no drift, a negative value is read as `0`) and
  `X9_CERTIFICATE_ISSUERNAME` (`X9`; empty skips the issuer check). A `jku` or `x5u` on the public
  host is read from the process itself over `localhost`; any other is fetched over HTTPS only.
- **The payload a payer fetches.** `POST /pub/api/v1/loc/{id}` takes the payer's signed request
  (`application/jose`) and answers the signed payload: priced at the later of the `dateForPayment`
  header's UTC start and now, with only the payment methods still valid at that instant, and
  `sentAt` the business clock's now (never before `revisedAt`). A request that does not verify, or
  whose content names another location, is a 400 with one generic reason (never a 401); content
  that is well formed but not the EMV string issued is a 400 naming `qrCodeContent`; a PAID or
  CANCELLED QR Code is refused naming which. An expired one is refused with its expiry reason,
  because the row is kept until retention (ADR-0029). Nothing is written.
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
- **Signing** (`internal/jws`): `X9_CERTIFICATE_JWKALGORITHM` (default `ES256` with the
  bundled demo identity, `PS512` with your own keystore, ADR-0040; RS\*, PS\*, or
  ES256/ES384/ES512 each on its own curve), `X9_CERTIFICATE_ENDPOINTTYPE` (`JWK_SET` → `jku`, the
  default; `PEM` → `x5u`; `NONE` → `x5c`) and `X9_PUBLICENDPOINTS_JWSTTLSECONDS` (300, written as
  `ttl: 300000` ms). An algorithm the signing key cannot produce stops the service at start. Its
  tests check Go's tokens with the black-box suite's own verifier (`others/blackbox/x9client`) when
  `.blackbox-venv` exists (`make blackbox-venv`) or `X9_BLACKBOX_PYTHON` names an interpreter with
  the suite's dependencies; CI always runs it.

## JWS clock skew

Every JWS this service verifies (a payment notification it receives, a payload it decodes, a payee's
answer to a notification it sends) must not be dated in the future. Its `iat` may be ahead of this
service's clock by at most the allowed skew, which forgives honest drift between two clocks.

| Variable | Key | Default | Meaning |
|---|---|---|---|
| `X9_JWS_CLOCK_SKEW_SECONDS` | `public-endpoints.jws-clock-skew-seconds` | `60` | How many seconds ahead of this service's clock a JWS's `iat` may be. `0` allows no drift. |

Beyond the skew, a payment notification is refused with a signed `401`, and a payee's answer is
reported `INVALID` with `signatureProblem` `FUTURE_IAT`. An expired one (`iat + ttl` passed) is
refused the same way, with `EXPIRED`.

## Settlement margin

A pre-commit ("I'll pay") arrives before the money moves, so the payee approves it only while there is
still time for the transfer to settle before the QR Code expires
([ADR-0041](docs/adr/0041-a-pre-commit-needs-time-to-settle-before-validuntil.md)).

| Variable | Key | Default | Meaning |
|---|---|---|---|
| `X9_NOTIFICATION_SETTLEMENT_MARGIN_SECONDS` | `notification.settlement-margin-seconds` | `1` | A pre-commit is refused when less than this many seconds are left before the QR Code's or the matched payment method's `validUntil`. `0` turns it off (`validUntil` inclusive). Negative stops the service at start. |

The refusal is the usual signed `400` on `validUntil` (or `paymentMethods.validUntil`), saying "Too
little time is left to settle"; past `validUntil` it still says the QR Code expired. Judged on this
service's clock at receipt. A post-commit is never judged on time, so it is recorded however late.

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
X9_TESTING_CLOCKCONTROL=true make run-local    # or any other way of starting the service
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
- **Real keys refuse it.** With the flag on, the service refuses to start unless its signing
  certificate is one this repository ships with its private key: the demo certificate, or any
  certificate under [`testdata/pki`](testdata/pki/README.md) — the revoked and untrusted test
  fixtures included, since their keys are just as public. It is checked by SHA-256 fingerprint
  compiled into the binary, so no configuration value can satisfy it. Any other certificate, real
  or self-made, is refused.
- **It is visible.** A `WARN` is logged at startup, and while the offset is non-zero every API
  response carries `X-Test-Clock-Offset: <milliseconds>` (signed; `86400000` is one day ahead,
  `-3600000` one hour behind).
- **Retention follows it.** The retention job takes the business clock, so a clock moved past both
  retentions purges on the next run (the black-box suite's X9-TIME-015 does exactly that).
- **Certificate validity stays on the real clock, on purpose.** Whether a certificate is within its
  `notBefore`/`notAfter`, and whether a CRL or OCSP answer is current, is a security check, not a
  business rule, so chain validation (`internal/pki`) never sees the offset: a clock moved past a
  certificate's `notAfter` does not make it expire, and a clock moved back does not revive an
  expired one. Only the JWS `iat` and `iat + ttl` follow the business clock.
- Request logging and long-poll waits measure real durations and keep the real clock.

## Troubleshooting

| Symptom | Likely cause / fix |
|---------|--------------------|
| Startup refused, naming `docs/CONFIGURATION-FROM-JAVA.md` | A 2.x setting is present: a Spring-style file, a JKS keystore, or a `SPRING_*`, `MONGO*` or `JASYPT_*` variable. Map it with that document, or unset it. |
| Startup refused: the schema or changelog is not the one expected | Liquibase has not run, or ran another changelog. Apply [`db/changelog/`](db/changelog/) first ([`db/README.md`](db/README.md)). |
| Readiness stays DOWN (503) | The database is unreachable, or the schema check has not passed; the log says which. |
| App starts but a phone can't reach the QR URL | The QR embeds `X9_PUBLICENDPOINTS_HOST`, which defaults to a placeholder. Point it at a reachable host (or a tunnel) — see [`ENDPOINTS.md`](ENDPOINTS.md#testing-a-real-scan-computer--phone-on-5g-cloudflare-tunnel). |
| Startup fails: payload host too long | The host must be ≤ 37 chars so the loc URL fits the EMV field (see [`ENDPOINTS.md`](ENDPOINTS.md#host-length-constraint)). |
| `docker compose` image not found | Build it first: `make image`, or name one with `X9_IMAGE`. |
| Port 8080 or 55432 already in use | Stop the conflicting process, or set `X9_PORT` (Compose) / `X9_DB_PORT` (`db/docker-compose.yml`). |

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
