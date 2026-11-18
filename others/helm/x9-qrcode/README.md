# X9 QRCode — Helm Chart

Deploy the X9 QRCode service to Kubernetes, on **your own PostgreSQL** (16 or newer). The chart ships
no database ([ADR-0032](../../../docs/adr/0032-x9150-can-live-in-the-banks-own-database.md)).

> The Java build was retired at 3.0.0 ([ADR-0039](../../../docs/adr/0039-java-retired-at-3-0-0.md)):
> the 2.x chart and its `runtime: java` values are in git history under the tag `java-final`. To move
> 2.x values, see [UPGRADING-TO-3.md](../../../UPGRADING-TO-3.md) and
> [CONFIGURATION-FROM-JAVA.md](../../../docs/CONFIGURATION-FROM-JAVA.md). A release that still sets
> `runtime: java` is refused; `runtime: go` is accepted and changes nothing.

## Values

- `chart/values.yaml` — full configuration, every setting documented.
- `chart/values-minimal.yaml` — the three required values: the public host, the database host and
  its name.

Copy one and adjust it for your environment: image, database, `application.x9.public-endpoints.host`
(see [`ENDPOINTS.md`](../../../ENDPOINTS.md)), keystores, resources.

## Before installing

**The database is yours.** Before the first install your DBA creates, once, the owner role and the
schema, and either lets the changelog create the application, read-only and event-reader roles
(then sets the application role's password) or creates them beforehand:
[db/README.md, "Installing it in your database"](../../../db/README.md#installing-it-in-your-database).
The service connects as the application role (`go.database.user`), never as the owner, with at
most 12 connections.

**The passwords are in a Secret you create,** named in `go.existingSecret` (`x9-qrcode-go` by
default); the chart never takes a password as a value:

```shell
kubectl -n <namespace> create secret generic x9-qrcode-go \
  --from-file=database-password=<file> --from-file=liquibase-password=<file>
# and private-keystore-password, private-key-password, truststore-password with your own keystores
```

## Install / upgrade

Dry-run first:

```shell
helm upgrade --install x9-qrcode others/helm/x9-qrcode/chart \
  -n <namespace> --create-namespace \
  -f <your-values>.yaml --dry-run
```

Then apply (drop `--dry-run`). Replace `<namespace>` with your target namespace and point `-f` at
your customized values file.

> `replicaCount` is always 1 — see [HIGH-AVAILABILITY.md](../../../HIGH-AVAILABILITY.md) for the
> single-active-instance posture and how it relates to the license limits.

## What it renders, and why

([ADR-0031](../../../docs/adr/0031-same-image-lighter-and-3-0-is-a-configuration-migration.md))

- **One instance, `strategy: Recreate`, no HPA.** The old pod is gone before the new one starts,
  so two instances never overlap. The chart refuses `replicaCount` other than 1 and
  `autoscaling.enabled: true`.
- **Liquibase 4.33.0 as an init container,** applying `db/changelog` as the schema owner before the
  service starts, on every start (it finds nothing to do when the schema is current). An init
  container rather than a `pre-install`/`pre-upgrade` hook Job, because with `Recreate` the schema
  moves only after the old pod has stopped: a hook Job would migrate while the old binary is still
  serving, on a schema newer than the one it checked at start. It also needs no hook ordering for
  the Secret it reads, and fails the pod, visibly, if the changelog does not apply. The chart
  carries the changelog through `chart/files/changelog`, a link to `db/changelog`, so it applies
  the very files CI checks. Set `go.liquibase.enabled: false` if your DBA applies the changelog,
  or the SQL `liquibase update-sql` writes, by hand.
- **Only the service's variables** ([CONFIGURATION-FROM-JAVA.md](../../../docs/CONFIGURATION-FROM-JAVA.md)).
  A `SPRING_*`, `MONGO*` or `JASYPT_*` name in `go.env` or `customerEnvs` is refused when the chart
  renders, as the service would refuse it at start.
- **`GOMEMLIMIT`** is `go.memLimitPercent` (80) percent of `resources.limits.memory`, in bytes.
- **Probes** on `/actuator/health/liveness` and `/actuator/health/readiness`, on the API port:
  there is no management port. No `preStop` hook: the binary keeps readiness down for
  `go.shutdownDelaySeconds` itself, and the image has no shell.
- **Secrets from a Secret you create,** named in `go.existingSecret`; the chart refuses a
  `*PASSWORD*` variable given as a value. The database password is mounted as a file
  (`DATABASE_PASSWORD_FILE`, re-read on every new connection); the owner's goes to the init
  container only; the keystore passwords are `secretKeyRef`s, optional while the bundled demo
  identity is used.

The `go.*` section keeps its 2.x name, so values written for the 2.x chart's Go rendering
(`values-go.yaml`) carry over unchanged, less `runtime: go`.

| Value | Variable | Default |
|---|---|---|
| `image.repository`, `image.tag` | — | `docker.io/materainc/x9-qrcode`, the appVersion (`3.0.1`) |
| `go.existingSecret`, `go.secretKeys.*` | `DATABASE_PASSWORD_FILE`, `X9_CERTIFICATE_*_PASSWORD` | `x9-qrcode-go`; `database-password`, `private-keystore-password`, `private-key-password`, `truststore-password` |
| `go.database.host`, `port`, `name` | `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME` | required, `5432`, required |
| `go.database.user`, `schema` | `DATABASE_USER`, `DATABASE_SCHEMA` | `x9_qrcode_app`, `x9_qrcode` |
| `go.database.sslmode`, `caSecret`, `caSecretKey` | `DATABASE_SSLMODE`, `DATABASE_SSLROOTCERT` | `verify-full`, none, `ca.crt` |
| `go.database.poolWrites`, `poolReads`, `queryExecMode` | `DATABASE_POOL_WRITES`, `DATABASE_POOL_READS`, `DATABASE_QUERY_EXEC_MODE` | `8`, `4`, none |
| `go.liquibase.enabled`, `image`, `owner`, `existingSecret`, `passwordKey`, `roles.*` | — (the init container) | `true`, `liquibase/liquibase:4.33.0`, `x9_qrcode_owner`, `go.existingSecret`, `liquibase-password` |
| `go.memLimitPercent`, `go.gomemlimit` | `GOMEMLIMIT` | `80`, none |
| `go.shutdownDelaySeconds` | `X9_SERVER_SHUTDOWNDELAYSECONDS` | `5`; `terminationGracePeriodSeconds` (60) must exceed it plus 20 |
| `application.x9.public-endpoints.host` | `X9_PUBLICENDPOINTS_HOST` | required |
| `application.x9.certificate.*.location` | `X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION`, `X9_CERTIFICATE_TRUSTSTORE_LOCATION` | empty: the bundled demo identity (NON-PRODUCTION) |
| `application.x9.reservation.ttl-seconds` | `X9_RESERVATION_TTL_SECONDS` | `90` |
| `application.x9.notification.settlement-margin-seconds` | `X9_NOTIFICATION_SETTLEMENT_MARGIN_SECONDS` | `1`; `0` is off |
| `deploy.ports.containerPort` | `X9_SERVER_PORT` | `8080` |
| `go.env`, `customerEnvs` | any other variable (`X9_LOG_LEVEL`, `X9_EVENTS_RETENTION`, …) | `X9_LOG_LEVEL=info`, `[]` |

## Lint

```shell
helm lint others/helm/x9-qrcode/chart -f others/helm/x9-qrcode/chart/values-minimal.yaml
make helm-check    # lint and template both value files; the rendering holds no Java variable
```

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
