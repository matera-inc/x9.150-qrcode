# Upgrading from 2.x (Java) to 3.0.0 (Go)

> **For an operator** who runs the Java build (Spring Boot, MongoDB) and will move to 3.0.0, the Go
> build on PostgreSQL. API clients notice almost nothing. Operations notice a lot: the database,
> the configuration, the secrets, the logs and the number of instances all change. This page says
> what, and links the details.
>
> **From 3.0.0 the repository and the image are the Go build only.** The Java build is retired
> ([ADR-0039](docs/adr/0039-java-retired-at-3-0-0.md)): its code is in git history under the tag
> `java-final`, and its last image is `materainc/x9-qrcode:java-final`, the way back. How to run
> the Go build: [RUNNING.md](RUNNING.md).

> **3.0.1: the demo identity is now EC P-256 and signs `ES256`.** The NON-PRODUCTION identity the
> image bundles changed from RSA-3072 (`PS512`) to EC P-256 (`ES256`): about 70% more payments
> per second on the same hardware, at the same strength
> ([ADR-0040](docs/adr/0040-the-bundled-demo-identity-signs-es256.md)). **Your own keys are
> unaffected:** with `X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION` set and
> `X9_CERTIFICATE_JWKALGORITHM` unset, the service still signs `PS512`, and an algorithm you set
> is used as before. Only something that trusted or pinned the 3.0.0 demo certificate (a test, a
> demo) must take the new one: SHA-256 `71f5abfa2557037258e616ef8b4d87b7f8ec4c97ae777e03b406ed9da8b75e41`.

Why 3.0.0, and what is kept on purpose:
[ADR-0031](docs/adr/0031-same-image-lighter-and-3-0-is-a-configuration-migration.md). Why there is
no data migration:
[ADR-0038](docs/adr/0038-no-data-migration-from-the-java-build-a-written-mapping-instead.md).

## What stays the same

| Surface | In 3.0.0 |
|---|---|
| Image name | `materainc/x9-qrcode`, amd64 and arm64 |
| HTTP contract | The same paths, bodies and status codes ([ADR-0028](docs/adr/0028-the-http-contract-is-frozen-and-equivalence-is-proved-by-a-differential-run.md)). The few deliberate differences are [below](#api-differences). |
| API port | `8080` (`EXPOSE 8080` in the [`Dockerfile`](Dockerfile)) |
| Health | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, in the Java build's JSON shape |
| Metrics | `/actuator/prometheus`; the request timer keeps its name and labels ([METRICS.md](docs/METRICS.md)) |
| Environment variables | Where a setting exists in both builds, its variable keeps its name: `X9_PUBLICENDPOINTS_HOST`, `X9_CERTIFICATE_*`, `X9_RESERVATION_TTL_SECONDS`, `X9_JWS_CLOCK_SKEW_SECONDS`, `KEYSTORE_RELOAD_FREQUENCY`, and the others in the mapping |
| Out of the box | The demo signing identity (NON-PRODUCTION) is bundled, so the image starts with no keystore configured. It was the Java build's RSA key until 3.0.0; from 3.0.1 it is EC P-256, signing `ES256` |

**What moves:** the management port. The Java chart served health and metrics on a separate port
(12345). The Go build has no management port: point the probes and the Prometheus scrape at
`8080`. The paths are the same.

## The database

The Go build runs on **PostgreSQL 16 or newer**, in one schema (`x9_qrcode` by default), which can
live in a PostgreSQL the bank already runs
([ADR-0032](docs/adr/0032-x9150-can-live-in-the-banks-own-database.md)). Nothing of MongoDB is used.

- **Four roles.** An owner for migrations only, the application role the service connects as, a
  read-only role, and an event reader for a payment system that reads events with SQL.
  Your DBA creates the owner and the schema once. The changelog creates the other three, or uses
  them if the DBA created them. See [db/README.md](db/README.md#roles).
- **Liquibase runs before the service,** as the owner. The service never runs DDL. At start it
  checks that it is in the configured schema and that the latest changeset is the one it was built
  for, and refuses to start otherwise. A DBA who keeps the owner role can generate the SQL with
  `liquibase update-sql`, review it and run it. See
  [db/README.md](db/README.md#installing-it-in-your-database).
- **At most 12 connections** per instance: 8 for writes, 4 for reads.
- **Backup** is your database's backup. Restore the schema's tables together, from one point in
  time.

**No data migration.** The Java build had no production users, so no tool moves MongoDB data
([ADR-0038](docs/adr/0038-no-data-migration-from-the-java-build-a-written-mapping-instead.md)). The
Go build starts on an empty schema. This means:

- **QR Codes created on the Java build do not exist on the Go build.** A printed one will not
  resolve. Create again the ones you still need.
- **Event consumers start over.** A stored Java cursor (a ULID) reads the Go log from the start.
- **Your Java deployment and its MongoDB are left as they are,** so you can go back.

**Storage grows more than on MongoDB.** The Go build keeps every signed notification in full:
about 15 KB per payment, about 12.5 times the Java build's figure
([BENCHMARK.md](BENCHMARK.md#database)). Retention is configurable: see `X9_QRCODE_RETENTION` and
`X9_EVENTS_RETENTION` in [db/README.md](db/README.md#what-the-service-asks-of-your-database).

## Configuration

There is no converter. [docs/CONFIGURATION-FROM-JAVA.md](docs/CONFIGURATION-FROM-JAVA.md) lists
every Java property and variable with its Go key and variable, or says why it is dropped.

- **One YAML file of Go keys** (`-config <path>` or `X9_CONFIG_FILE`), **plus environment
  variables**, which win. Every key is in
  [`cmd/x9-qrcode/config.example.yaml`](cmd/x9-qrcode/config.example.yaml).
- **Renamed:** `SERVER_PORT` is now `X9_SERVER_PORT`. `SPRING_CONFIG_LOCATION` is now
  `X9_CONFIG_FILE`.
- **New:** the `DATABASE_*` variables (`DATABASE_HOST`, `DATABASE_NAME`, `DATABASE_USER`,
  `DATABASE_PASSWORD` or `DATABASE_PASSWORD_FILE`, and `DATABASE_SSLMODE` are required), and the
  retention keys.
- **Dropped,** among others: profiles, `X9_PUBLICENDPOINTS_BASEPATH` (always `/pub`), per-package
  log levels (one `X9_LOG_LEVEL`), and the HSM settings (refused).
- **The Java configuration is refused, not half-read.** A Spring-style file, a `SPRING_*`,
  `MONGO*` or `JASYPT_*` variable, an `ENC(…)` value, a `classpath:` location or a JKS keystore
  stops the service at start, with a message that names the mapping page.

## Secrets

- **No Jasypt.** The Go build never decrypts. Recover each `ENC(…)` value once, by hand, and supply
  the plain secret from your secret store
  ([how](docs/CONFIGURATION-FROM-JAVA.md#jasypt-enc-values)).
- **From the environment or from a mounted file.** The database password can be a file
  (`DATABASE_PASSWORD_FILE`), re-read on every new connection, so rotating it is a file change.
- **No Kubernetes secrets API.** The service never calls the Kubernetes API; the chart mounts a
  Secret you create.

## Keystores

The Go build reads **PKCS#12 or PEM**. The file's content decides, not its name. A true JKS or
JCEKS file is refused: convert it once with `keytool`
([JKS to PKCS#12](docs/CONFIGURATION-FROM-JAVA.md#jks-to-pkcs12)). Check first: since JDK 9,
keytool writes PKCS#12 by default, so your `.jks` file may need no conversion. A PKCS#12 file has
one password: leave the private-key password empty or equal to it. A `classpath:` location is
refused; mount the file instead.

## Actuator and metrics

- **Kept:** `/actuator/health` (with `liveness` and `readiness`), `/actuator/prometheus` and
  `/actuator/info`. Every other actuator endpoint the Java build exposed answers **404**.
  `/actuator/info` now answers the build's version and commit; the Java build answered `{}`.
- **Metrics:** 9 Java families are kept with the same name, type and labels, 12 are replaced by Go
  families, and 40 are dropped. Each one, and why, is in [docs/METRICS.md](docs/METRICS.md).
- **Dashboards on request rate, latency and errors keep working:** they read
  `http_server_requests_seconds`, which is kept, labelled by route template as before. An alert on
  the rate of error logs (`logback_events_total`) keeps working too. JVM, Tomcat and MongoDB
  driver panels need the Go queries listed in METRICS.md.

## Logs

The log is **JSON, one object per line, on standard output**, always. There is no text format and
no log file. One line is the exception: if the service cannot start, it writes the reason
(`x9-qrcode stopped`) to standard error.

| Go field | Java field (logstash JSON encoder) | Notes |
|---|---|---|
| `time` | `@timestamp` | RFC 3339 |
| `level` | `level` | `DEBUG`, `INFO`, `WARN`, `ERROR` in both |
| `msg` | `message` | |
| `traceId`, `spanId` | `traceId`, `spanId` | From an incoming `b3`, `X-B3-*` or `traceparent` header, on the request line. Logged only; nothing is exported |
| `method`, `path`, `status`, `durationMs` | none: inside `message` | One `request` line per answered request. Java wrote `Started …` and `Finished … took …ms with status …` as text |
| `err` | `stack_trace` | The error's text; no stack trace |
| none | `logger_name`, `thread_name`, `level_value`, `@version` | No Go equivalent |
| none | `service` (`X9-QRCODE`) | Not written. A log pipeline that filters on it must select the container instead |

- **One level** for the whole service: `X9_LOG_LEVEL` (`debug`, `info`, `warn`, `error`).
- **Health and metrics scrapes are not logged,** as in the Java build.
- Sources: [`internal/httplog/httplog.go`](internal/httplog/httplog.go), and the Java build's
  `x9-qrcode-infrastructure/src/main/resources/logback-spring.xml` at the tag `java-final`.

## One instance

The Go build runs **one instance**. The chart uses `strategy: Recreate`, removes the HPA, and
refuses `replicaCount` other than 1. The old pod stops before the new one starts, so expect a short
gap on every upgrade. Why: long-poll wake-ups are in the process, and the licence allows one
active instance ([HIGH-AVAILABILITY.md](HIGH-AVAILABILITY.md)).

**Memory:** there is no JVM. Cap memory with the Go runtime's `GOMEMLIMIT`; the chart sets it to
80% of the container's memory limit. For what to expect, see the
[BENCHMARK.md summary](BENCHMARK.md#summary): the Go build uses a small fraction of the Java build's
memory, and its image is much smaller.

## Image tags

| Tag | Meaning |
|---|---|
| `3.0.0`, `3.0.1`, `3.0`, `3` (and later `3.x`) | The Go build: a release, and the newest of its minor and major line |
| `git-<sha>` | Pinned to a source commit, as before |
| `latest` | The newest release: the Go build from 3.0.0 |
| `java-final` | The last Java build, immutable: the way back. It receives no more fixes |

**Pin a tag.** If you pull `latest` and are not ready to move, pin `java-final`, or the
`git-<sha>` of the Java build you run.

The Go image is distroless: it has no shell and runs as user 65532. Use `x9-qrcode -version` or
`/actuator/info` for its version, and the OCI `revision` label for its commit.

## Compose and Helm

**Compose.** [`docker-compose.yml`](docker-compose.yml) is now the Go stack (it was
`docker-compose.go.yml` beside the Java files until 3.0.0; the Java `docker-compose.yml` and
`docker-compose.prod.yml` are gone): PostgreSQL 16, the DBA's one-time step, Liquibase, then the
service as the application role.

```bash
make up         # build, start, wait for health UP
make down       # stop and throw the database away
```

It is for local use only: its database trusts every connection.

**Helm.** The chart (3.0.0) deploys the Go build only; `runtime: java` and the Java templates are
gone, and a release that still sets `runtime: java` is refused. 2.x values written for
`values-go.yaml` carry over: the `go.*` keys keep their names.

```bash
helm upgrade --install x9-qrcode others/helm/x9-qrcode/chart \
  -n <namespace> -f <your-values>.yaml
```

It renders one instance with `Recreate` and no HPA, Liquibase 4.33.0 as an init container, probes on
the API port, and `GOMEMLIMIT`. It ships no PostgreSQL: point `go.database.*` at yours. It takes no
password as a value: create a Secret and name it in `go.existingSecret`. Details:
[the chart's README](others/helm/x9-qrcode/README.md).

## API differences

Until 3.0.0 the black-box suite ran against both builds and compared every answer. The
differences it allowed are listed, with reasons, in
[TOLERATED-DIFFERENCES.md](others/blackbox/TOLERATED-DIFFERENCES.md). The ones an integrator is
most likely to notice:

- **An expired QR Code is kept,** readable with its expiry reason, until retention removes it. The
  Java build deleted it about 30 s after `validUntil`, and answered 404.
- **The event stream is stricter.** A cursor ahead of the log is a 409; a malformed cursor is a 400.
- **Create and PATCH answer with an `ETag`,** so the next PATCH can send `If-Match` without a GET.
- **Every 500 is the `internal-error` problem,** and a JSON body over 1 MiB is refused with a 400.
- **JSON is compact,** not pretty-printed.

## Checklist

1. Read [CONFIGURATION-FROM-JAVA.md](docs/CONFIGURATION-FROM-JAVA.md) and write the Go YAML file or
   environment.
2. Remove every `SPRING_*`, `MONGO*` and `JASYPT_*` variable. Rename `SERVER_PORT` to
   `X9_SERVER_PORT`.
3. Replace each `ENC(…)` value with its plain secret, from your secret store or a mounted file.
4. Check your keystores with `keytool -list`; convert any true JKS file to PKCS#12.
5. Have your DBA create the owner role and the schema, then run Liquibase as the owner
   ([db/README.md](db/README.md)).
6. Set the `DATABASE_*` variables for the application role, with `DATABASE_SSLMODE=verify-full`.
7. Move probes and the Prometheus scrape to port `8080`; update JVM, Tomcat and MongoDB panels from
   [METRICS.md](docs/METRICS.md).
8. Update log parsing for the Go fields above.
9. Run one instance with `Recreate`; set a memory limit (the chart derives `GOMEMLIMIT`).
10. Tell event consumers to start over, and re-create any QR Code still in use.
11. Keep the Java deployment and its MongoDB until you are satisfied: `java-final` is the way back.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
