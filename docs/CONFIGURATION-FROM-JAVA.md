# Configuring the Go build, coming from the Java build

> **For an operator who ran the Java build** (Spring Boot, MongoDB) and now starts the Go build
> (PostgreSQL). There is no converter and no data migration
> ([ADR-0038](adr/0038-no-data-migration-from-the-java-build-a-written-mapping-instead.md)): the
> Go build starts on an empty database, and you write its configuration once, from this page.

Every property and environment variable the Java build reads is listed below, with the Go key and
the Go environment variable that replace it, or **dropped** and why. The Java side comes from
`x9-qrcode-infrastructure/src/main/resources/application*.yml`, the classes that bind them, the
Helm chart (`others/helm/x9-qrcode/chart/`), the Compose files and `secrets/`. The Go side comes
from [`internal/config/config.go`](../internal/config/config.go), and every key is shown with its
comment in [`cmd/x9-qrcode/config.example.yaml`](../cmd/x9-qrcode/config.example.yaml).

## How the Go build reads its configuration

- **One YAML file of Go keys,** passed with `-config <path>` or `X9_CONFIG_FILE`, **plus
  environment variables,** which take precedence over the file
  ([ADR-0031](adr/0031-same-image-lighter-and-3-0-is-a-configuration-migration.md)). Either alone is
  enough.
- **Where a setting exists in both builds, its environment variable has the same name** (for
  example `X9_PUBLICENDPOINTS_HOST`, `X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION`). The exceptions are
  marked in the tables below: `SERVER_PORT` became `X9_SERVER_PORT`, and the database variables
  are new.
- **Everything is checked at start.** An unknown key in the file, a missing required key or an
  invalid value stops the service with an error naming the key and its variable.
- **The Java build's configuration is refused, not half-read.** The service stops at start, naming
  this page, when it finds:
  - a `.properties` file, or a YAML file with a top-level `spring`, `management`, `jasypt`, `info`,
    `logging` or `x9` key, a Jasypt `ENC(…)` value, a `classpath:` location or a `${…}`
    placeholder;
  - any environment variable whose name starts with `SPRING_`, `MONGO` or `JASYPT_`;
  - an `ENC(…)` value in any variable the Go build reads;
  - a JKS or JCEKS keystore file;
  - an HSM or security-provider setting with a value other than the Java default (see
    [Keystores](#signing-key-and-truststore)).
- **Other Java variables are ignored.** A variable the Go build does not read (say
  `X9_PUBLICENDPOINTS_BASEPATH`) has no effect. Remove it, so that nobody believes it does
  something.

In the tables, **Java variable** is the name Spring reads the property from (relaxed binding: upper
case, dots to underscores, dashes removed), and "—" means there is none worth naming.

## Server and shutdown

| Java property | Java variable | Go key | Go variable | Notes |
|---|---|---|---|---|
| `server.port` (8080) | `SERVER_PORT` | `server.port` | `X9_SERVER_PORT` | Same default, 8080. **The variable is renamed.** |
| `management.server.port` (12345 in the chart) | `MANAGEMENT_SERVER_PORT` | dropped | — | Health and metrics are served on `server.port`, as `/actuator/health…` and `/actuator/prometheus`. Point the probes at the API port. |
| `server.shutdown` (`graceful`) | — | dropped | — | Shutdown is always graceful: readiness turns `OUT_OF_SERVICE`, parked long-polls are woken, requests in flight finish. |
| `spring.lifecycle.timeout-per-shutdown-phase` (20 s) | — | dropped | — | Fixed at 20 s for requests in flight (ADR-0031). |
| — (Go only) | — | `server.shutdown-delay-seconds` | `X9_SERVER_SHUTDOWNDELAYSECONDS` | New, default 5: how long readiness is down before new connections are refused. |
| `server.max-http-request-header-size` (30 KB) | — | dropped | — | Fixed: headers above 30 KB are refused with a 400, as in the Java build. |

## Public endpoints and JWS

| Java property | Java variable | Go key | Go variable | Notes |
|---|---|---|---|---|
| `x9.public-endpoints.host` | `X9_PUBLICENDPOINTS_HOST` | `public-endpoints.host` | `X9_PUBLICENDPOINTS_HOST` | Same meaning and default placeholder (`x9-150.example.com`). A host with no scheme, at most 37 characters, printable ASCII. |
| `x9.public-endpoints.jws-ttl-seconds` (300) | `X9_PUBLICENDPOINTS_JWSTTLSECONDS` | `public-endpoints.jws-ttl-seconds` | `X9_PUBLICENDPOINTS_JWSTTLSECONDS` | Same. |
| `x9.public-endpoints.jws-clock-skew-seconds` (60) | `X9_JWS_CLOCK_SKEW_SECONDS` | `public-endpoints.jws-clock-skew-seconds` | `X9_JWS_CLOCK_SKEW_SECONDS` | Same, including 0 (no drift) and a negative value read as 0. |
| `x9.public-endpoints.base-path` (`/pub`) | `X9_PUBLICENDPOINTS_BASEPATH` | dropped | — | The paths are part of the frozen HTTP contract ([ADR-0028](adr/0028-the-http-contract-is-frozen-and-equivalence-is-proved-by-a-differential-run.md)): always `/pub`. `docker-compose.prod.yml` sets this variable; the Go build ignores it. |
| `x9.public-endpoints.payload-path` | — | dropped | — | Fixed: `/pub/api/v1/loc`. |
| `x9.public-endpoints.payment-notification-path` | — | dropped | — | Fixed: `/pub/api/v1/payment-notification`. |
| `x9.public-endpoints.jwk-set-path` | — | dropped | — | Fixed: `/pub/.well-known/jwks`. |
| `x9.public-endpoints.certificate-path` | — | dropped | — | Fixed: `/pub/.well-known/certificate`. |

## Reservation, currencies and the test clock

| Java property | Java variable | Go key | Go variable | Notes |
|---|---|---|---|---|
| `x9.reservation.ttl-seconds` (90) | `X9_RESERVATION_TTL_SECONDS` | `reservation.ttl-seconds` | `X9_RESERVATION_TTL_SECONDS` | Same, including 0 for no window; negative is refused. |
| `x9.currency.supported-currencies-location` | `X9_CURRENCY_SUPPORTEDCURRENCIESLOCATION` | `currency.supported-currencies-location` | `X9_CURRENCY_SUPPORTEDCURRENCIESLOCATION` | A path; `file:` is accepted and dropped, `classpath:` is refused. Empty is the bundled file, the same as Java's. |
| `x9.currency.pegged-currencies-location` | `X9_CURRENCY_PEGGEDCURRENCIESLOCATION` | `currency.pegged-currencies-location` | `X9_CURRENCY_PEGGEDCURRENCIESLOCATION` | As above. |
| `x9.testing.clock-control` (false) | `X9_TESTING_CLOCKCONTROL` | `testing.clock-control` | `X9_TESTING_CLOCKCONTROL` | Same, test deployments only ([ADR-0033](adr/0033-the-clock-can-be-moved-in-test-deployments-only.md)). |
| `x9.testing.clock-listen` (`127.0.0.1:8099`) | `X9_TESTING_CLOCKLISTEN` | `testing.clock-listen` | `X9_TESTING_CLOCKLISTEN` | Same. |

## Signing key and truststore

The Go build reads **PKCS#12 or PEM**. The file's content decides its format, not its name or
`type`: a file called `.jks` that is really PKCS#12 (keytool's default since JDK 9, which is what
the Java build's bundled demo files are) is read as it is. A true JKS or JCEKS file is refused:
convert it as shown in [JKS to PKCS#12](#jks-to-pkcs12). A location left empty is the bundled
**demo identity (NON-PRODUCTION)**, as in the Java build.

| Java property | Java variable | Go key | Go variable | Notes |
|---|---|---|---|---|
| `x9.certificate.endpoint-type` (`JWK_SET`) | `X9_CERTIFICATE_ENDPOINTTYPE` | `certificate.endpoint-type` | `X9_CERTIFICATE_ENDPOINTTYPE` | Same: `JWK_SET`, `PEM` or `NONE`. |
| `x9.certificate.jwk-algorithm` (`PS512`) | `X9_CERTIFICATE_JWKALGORITHM` | `certificate.jwk-algorithm` | `X9_CERTIFICATE_JWKALGORITHM` | Same. An algorithm the key cannot sign with is refused at start. |
| `x9.certificate.issuer-name` (`X9`) | `X9_CERTIFICATE_ISSUERNAME` | `certificate.issuer-name` | `X9_CERTIFICATE_ISSUERNAME` | Same; empty skips the check. |
| `x9.certificate.certificate-supported-type` (`X.509`) | — | dropped | — | Always X.509. |
| `x9.certificate.custom-provider` | `X9_CERTIFICATE_CUSTOMPROVIDER` | dropped | — | The Java build never read it. |
| `x9.certificate.private-keystore.location` | `X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION` | `certificate.private-keystore.location` | `X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION` | A path; `file:` is accepted and dropped. **`classpath:` is refused**: leave it empty for the bundled demo, or mount the file. |
| `x9.certificate.private-keystore.type` (`JKS`) | `X9_CERTIFICATE_PRIVATEKEYSTORE_TYPE` | `certificate.private-keystore.type` | `X9_CERTIFICATE_PRIVATEKEYSTORE_TYPE` | Informative: `PKCS12`, `PEM` or `JKS` (as a name). `PKCS11` is refused. |
| `x9.certificate.private-keystore.password` | `X9_CERTIFICATE_PRIVATEKEYSTORE_PASSWORD` | `certificate.private-keystore.password` | `X9_CERTIFICATE_PRIVATEKEYSTORE_PASSWORD` | Plain text: see [Jasypt](#jasypt-enc-values). |
| `x9.certificate.private-keystore.private-key-password` | `X9_CERTIFICATE_PRIVATEKEYSTORE_PRIVATEKEYPASSWORD` | `certificate.private-keystore.private-key-password` | `X9_CERTIFICATE_PRIVATEKEYSTORE_PRIVATEKEYPASSWORD` | Empty, or equal to the password: a PKCS#12 file has one password. |
| `x9.certificate.private-keystore.alias` | `X9_CERTIFICATE_PRIVATEKEYSTORE_ALIAS` | `certificate.private-keystore.alias` | `X9_CERTIFICATE_PRIVATEKEYSTORE_ALIAS` | Names the keystore in logs, as in Java. |
| `x9.certificate.private-keystore.enforced-alias` | `X9_CERTIFICATE_PRIVATEKEYSTORE_ENFORCEDALIAS` | `certificate.private-keystore.enforced-alias` | `X9_CERTIFICATE_PRIVATEKEYSTORE_ENFORCEDALIAS` | Accepted and not used (a `WARN` says so): the file must hold exactly one private key. |
| `x9.certificate.private-keystore.hsm-enabled` (false) | `X9_CERTIFICATE_PRIVATEKEYSTORE_HSMENABLED` | refused | — | Any value but the Java default stops the service: no HSM signer yet (plan question B2). |
| `x9.certificate.private-keystore.provider` | `X9_CERTIFICATE_PRIVATEKEYSTORE_PROVIDER` | refused | — | As above. |
| `x9.certificate.private-keystore.external-provider-class` | `X9_CERTIFICATE_PRIVATEKEYSTORE_EXTERNALPROVIDERCLASS` | refused | — | As above. |
| `x9.certificate.private-keystore.load-strategy` (`LOAD_INPUT_STREAM`) | `X9_CERTIFICATE_PRIVATEKEYSTORE_LOADSTRATEGY` | refused | — | As above. |
| `x9.certificate.private-keystore.pkcs11.config-file-path` | `X9_CERTIFICATE_PRIVATEKEYSTORE_PKCS11_CONFIGFILEPATH` | refused | — | As above. |
| `x9.certificate.private-keystore.pkcs11.configurable-base-provider` (`SunPKCS11`) | `X9_CERTIFICATE_PRIVATEKEYSTORE_PKCS11_CONFIGURABLEBASEPROVIDER` | refused | — | As above. |
| `x9.certificate.private-keystore.trust-manager-factory-algorithm` | — | dropped | — | A JVM setting; the Go build verifies chains with its own code ([ADR-0027](adr/0027-crypto-in-go-x9150-jws-profile-pkix-with-crl-and-emv-by-hand.md)). |
| `x9.certificate.private-keystore.keystore-alias` | — | dropped | — | Internal to the Java reload event. |
| `x9.certificate.private-keystore.ignore-expired-validation` | — | dropped | — | The Java build never read it. |
| `x9.certificate.private-keystore.cache-certificates-enabled`, `cache-entries-enabled`, `private-key-cache-enabled` (true) | — | dropped | — | The Go build always holds the key and certificates in memory, and re-reads the files on `keystore.reload.frequency`. |
| `x9.certificate.truststore.location` | `X9_CERTIFICATE_TRUSTSTORE_LOCATION` | `certificate.truststore.location` | `X9_CERTIFICATE_TRUSTSTORE_LOCATION` | As for the private keystore. Empty is the bundled demo truststore. |
| `x9.certificate.truststore.type` | `X9_CERTIFICATE_TRUSTSTORE_TYPE` | `certificate.truststore.type` | `X9_CERTIFICATE_TRUSTSTORE_TYPE` | Informative, as above. |
| `x9.certificate.truststore.password` | `X9_CERTIFICATE_TRUSTSTORE_PASSWORD` | `certificate.truststore.password` | `X9_CERTIFICATE_TRUSTSTORE_PASSWORD` | Plain text. |
| `x9.certificate.truststore.alias` | `X9_CERTIFICATE_TRUSTSTORE_ALIAS` | `certificate.truststore.alias` | `X9_CERTIFICATE_TRUSTSTORE_ALIAS` | Names the truststore in logs. |
| `x9.certificate.truststore.hsm-enabled`, `provider`, `external-provider-class`, `load-strategy`, `pkcs11.*` | `X9_CERTIFICATE_TRUSTSTORE_HSMENABLED`, `…_PROVIDER`, `…_EXTERNALPROVIDERCLASS`, `…_LOADSTRATEGY`, `…_PKCS11_CONFIGFILEPATH`, `…_PKCS11_CONFIGURABLEBASEPROVIDER` | refused | — | As for the private keystore. |
| `x9.certificate.truststore.trust-manager-factory-algorithm`, `keystore-alias`, `ignore-expired-validation`, `cache-*`, `enforced-alias` | — | dropped | — | As for the private keystore. |
| `keystore.reload.frequency` (30000 ms) | `KEYSTORE_RELOAD_FREQUENCY` | `keystore.reload.frequency` | `KEYSTORE_RELOAD_FREQUENCY` | Same, in milliseconds. |

## Database

The Go build uses PostgreSQL; nothing of MongoDB carries over. Create the schema with Liquibase
first, as [db/README.md](../db/README.md) explains, and start the service on it **empty**.

| Java property | Java variable | Go key | Go variable | Notes |
|---|---|---|---|---|
| `spring.data.mongodb.uri` (the chart's secret) | `SPRING_DATA_MONGODB_URI` | dropped | — | Refused at start (`SPRING_*`). Set the `database.*` keys below instead. |
| `spring.data.mongodb.host` | `SPRING_DATA_MONGODB_HOST` | dropped | — | Refused at start. See `database.host`. |
| `spring.data.mongodb.port` | `SPRING_DATA_MONGODB_PORT` | dropped | — | Refused at start. See `database.port`. |
| `spring.data.mongodb.database` (`x9-qrcode`) | `SPRING_DATA_MONGODB_DATABASE` | dropped | — | Refused at start. See `database.name` and `database.schema`. |
| `spring.data.mongodb.replica-set-name` | `SPRING_DATA_MONGODB_REPLICA_SET_NAME` | dropped | — | Refused at start. PostgreSQL needs no replica set for transactions. |
| `spring.data.mongodb.auto-index-creation`, `max-wait-time`, `connection-timeout`, `socket-timeout` | — | dropped | — | Indexes are created by Liquibase; the pool's timeouts are fixed. |
| — (Go only) | — | `database.host` | `DATABASE_HOST` | **Required.** |
| — (Go only) | — | `database.port` | `DATABASE_PORT` | Default 5432. |
| — (Go only) | — | `database.name` | `DATABASE_NAME` | **Required.** |
| — (Go only) | — | `database.user` | `DATABASE_USER` | **Required**: the application role. |
| — (Go only) | — | `database.password` | `DATABASE_PASSWORD` | One of this or the file is **required**. |
| — (Go only) | — | `database.password-file` | `DATABASE_PASSWORD_FILE` | A mounted file, re-read on every new connection. |
| — (Go only) | — | `database.schema` | `DATABASE_SCHEMA` | Default `x9_qrcode`. |
| — (Go only) | — | `database.sslmode` | `DATABASE_SSLMODE` | **Required**; `verify-full` is recommended. |
| — (Go only) | — | `database.sslrootcert` | `DATABASE_SSLROOTCERT` | Required for `verify-ca` and `verify-full`. |
| — (Go only) | — | `database.pool-writes` | `DATABASE_POOL_WRITES` | Default 8. |
| — (Go only) | — | `database.pool-reads` | `DATABASE_POOL_READS` | Default 4. |
| — (Go only) | — | `database.query-exec-mode` | `DATABASE_QUERY_EXEC_MODE` | `exec` behind an older PgBouncer. |

## Retention

MongoDB's TTL index deleted documents at expiry; the Go build keeps an expired QR Code readable and
purges old rows with one timer ([ADR-0029](adr/0029-time-is-applied-on-read-and-retention-is-the-only-job.md)).
These keys are new; the Java build had none.

| Java property | Java variable | Go key | Go variable | Notes |
|---|---|---|---|---|
| — (Go only) | — | `events.retention` | `X9_EVENTS_RETENTION` | Default `P30D`. |
| — (Go only) | — | `qrcode.retention` | `X9_QRCODE_RETENTION` | Default `P400D`; never shorter than `events.retention`. |
| — (Go only) | — | `retention.interval` | `X9_RETENTION_INTERVAL` | Default `PT1H`. |

## Logs, health and metrics

| Java property | Java variable | Go key | Go variable | Notes |
|---|---|---|---|---|
| `logging.level.*` | `LOGGING_LEVEL_…` | `log.level` | `X9_LOG_LEVEL` | One level for the whole service: `debug`, `info` (default), `warn` or `error`. |
| `application.loggingFormat` (`json`, `text` in the default profile; a chart value) | `APPLICATION_LOGGINGFORMAT` | dropped | — | The log is always JSON, one object per line, on standard output. |
| `ASYNC_LOGGING_FLUSH_TIME` (logback) | `ASYNC_LOGGING_FLUSH_TIME` | dropped | — | No asynchronous file appender. |
| `management.endpoint.logfile.external-file` | — | dropped | — | No log file: standard output only. |
| `x9.observability.rest.logger-filter.enabled`, `regex-rules.*` | — | dropped | — | One line per request, always; `/actuator/health` and `/actuator/prometheus` are not logged, as Java's default rules did. |
| `management.endpoints.web.exposure.include` (`*`), `exclude` | — | dropped | — | Only `/actuator/health` (with `liveness` and `readiness`), `/actuator/prometheus` and `/actuator/info` are served ([ADR-0031](adr/0031-same-image-lighter-and-3-0-is-a-configuration-migration.md)); the other endpoints, and why each is gone, are in [METRICS.md](METRICS.md#the-other-actuator-endpoints). |
| `management.endpoint.health.probes.enabled`, `show-details` | — | dropped | — | The probes are always on, in the Java build's JSON shape; no details are shown. |
| `management.health.mail.enabled`, `management.health.rabbit.enabled`, `management.health.kubernetes.enabled` | — | dropped | — | Health is the database and the schema check only. |
| `management.prometheus.*`, `management.endpoint.prometheus.enabled`, `management.endpoint.metrics.enabled` | — | dropped | — | `/actuator/prometheus` is always on. Which of the Java build's metrics keep their names: [METRICS.md](METRICS.md). |
| `management.endpoint.env.*`, `management.endpoint.configprops.*`, `management.endpoint.sbom.enabled` | — | dropped | — | Those endpoints are not served. |
| `management.tracing.propagation.type` (`b3`) | — | dropped | — | Incoming `b3` and `traceparent` ids are logged with each request; nothing is exported. |
| `info.app.*`, `info.tags.pod` | `HOSTNAME` | dropped | — | Not read. `/actuator/info` answers the version and commit stamped into the binary at build time, the same two `x9-qrcode -version` prints and the start-up log line carries ([METRICS.md](METRICS.md#actuatorinfo)). The Java build never showed these properties: its `/actuator/info` answered `{}`. |

## Spring, the JVM and Kubernetes

| Java property | Java variable | Go key | Go variable | Notes |
|---|---|---|---|---|
| `spring.config.location` (the chart's ConfigMap) | `SPRING_CONFIG_LOCATION` | `-config` flag | `X9_CONFIG_FILE` | Refused at start (`SPRING_*`). Point `X9_CONFIG_FILE` at the Go YAML file instead. |
| `spring.profiles.active` (`kubernetes`, `dev`) | `SPRING_PROFILES_ACTIVE` | dropped | — | Refused at start. There are no profiles: one file plus the environment. |
| `spring.config.import` (`secrets/application-secrets.yml`, `kubernetes:`) | — | dropped | — | Secrets come from the environment or mounted files; see [Jasypt](#jasypt-enc-values). |
| `spring.cloud.kubernetes.*`, `spring.cloud.discovery.client.composite-indicator.enabled` | — | dropped | — | The service never calls the Kubernetes API. |
| `spring.application.name` | — | dropped | — | The database connections are named `x9-qrcode`. |
| `spring.threads.virtual.enabled`, `spring.main.allow-circular-references`, `spring.jmx.enabled` | — | dropped | — | JVM and Spring internals. |
| `jasypt.encryptor.algorithm`, `jasypt.encryptor.password` (the chart's `secrets.configEncryption`) | `JASYPT_ENCRYPTOR_PASSWORD` | dropped | — | Refused at start. The Go build never decrypts; see [Jasypt](#jasypt-enc-values). |
| `x9.emv.*` (`mai-gui`, `mai-tag-id`, `url-id`, `transaction-currency`, …) | — | dropped | — | The EMV fields are the standard's and fixed in the Go build's encoder; the Java build's defaults are its only values. |
| JVM options of the image | `JAVA_TOOL_OPTIONS`, `BPL_…` | dropped | — | No JVM. To cap memory, use the Go runtime's own `GOMEMLIMIT`. |

## The Helm chart's values

The chart under `others/helm/x9-qrcode/` deploys the Java build by default until the cutover;
with `-f values-go.yaml` (`runtime: go`) it deploys the Go build, as its
[README](../others/helm/x9-qrcode/README.md#the-go-build-values-goyaml) explains. This is what each
of the Java values becomes in that rendering; the `application.x9.*` values feed both builds, and
the Go-only values are under `go.*`.

| Chart value | Java setting it feeds | Go key | Go variable | Notes |
|---|---|---|---|---|
| `application.x9.public-endpoints.host` | `x9.public-endpoints.host` | `public-endpoints.host` | `X9_PUBLICENDPOINTS_HOST` | Required by the chart; the Go build defaults to the placeholder and warns. |
| `application.x9.certificate.private-keystore.location` | `x9.certificate.private-keystore.location` | `certificate.private-keystore.location` | `X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION` | Mount a PKCS#12 or PEM file; see [JKS to PKCS#12](#jks-to-pkcs12). |
| `application.x9.certificate.truststore.location` | `x9.certificate.truststore.location` | `certificate.truststore.location` | `X9_CERTIFICATE_TRUSTSTORE_LOCATION` | As above. |
| `application.x9.reservation.ttl-seconds` | `x9.reservation.ttl-seconds` | `reservation.ttl-seconds` | `X9_RESERVATION_TTL_SECONDS` | Same. |
| `application.loggingFormat` | `application.loggingFormat` | dropped | — | Always JSON. |
| `applicationExtra` | any Spring property | dropped | — | Write Go keys in the Go YAML file instead. |
| `secrets.spring.data.mongodb.uri` | `spring.data.mongodb.uri` | `database.host` | `DATABASE_HOST` | And the other `DATABASE_*` variables above: chart values `go.database.*`; the password is `DATABASE_PASSWORD_FILE`, mounted from the Secret `go.existingSecret`. |
| `secrets.x9.certificate.privateKeystore.password` | `x9.certificate.private-keystore.password` | `certificate.private-keystore.password` | `X9_CERTIFICATE_PRIVATEKEYSTORE_PASSWORD` | From the Secret `go.existingSecret`, through `secretKeyRef`; the chart takes no password as a value. |
| `secrets.x9.certificate.privateKeystore.privateKeyPassword` | `x9.certificate.private-keystore.private-key-password` | `certificate.private-keystore.private-key-password` | `X9_CERTIFICATE_PRIVATEKEYSTORE_PRIVATEKEYPASSWORD` | Equal to the password, or empty. |
| `secrets.x9.certificate.truststore.password` | `x9.certificate.truststore.password` | `certificate.truststore.password` | `X9_CERTIFICATE_TRUSTSTORE_PASSWORD` | From the Secret `go.existingSecret`. |
| `secrets.configEncryption.enabled`, `password` | `jasypt.encryptor.password` | dropped | — | No Jasypt. |
| `environment.env` (`SPRING_CONFIG_LOCATION`) | `spring.config.location` | `-config` flag | `X9_CONFIG_FILE` | Mount the Go YAML file and name it. |
| `deploy.ports.containerPort` (8080) | `server.port` | `server.port` | `X9_SERVER_PORT` | Same port. |
| `deploy.ports.managementPort` (12345), the probes' `port` | `management.server.port` | dropped | — | Probes move to the API port; the paths are the same. |
| `autoscaling.*`, `replicaCount` | — | dropped | — | **One instance** (ADR-0031). |
| `deploymentStrategy.type` (`RollingUpdate`) | — | dropped | — | `Recreate`, so two instances never overlap (ADR-0031). |
| `deploymentStrategy.terminationGracePeriodSeconds`, `preStop.sleep` | — | `server.shutdown-delay-seconds` | `X9_SERVER_SHUTDOWNDELAYSECONDS` | Chart value `go.shutdownDelaySeconds`; the grace period must exceed this delay plus the 20 s drain. No `preStop` hook. |
| `resources.limits.memory` | the JVM's heap | — | — | The Go runtime's own `GOMEMLIMIT` is set to `go.memLimitPercent` (80) percent of the limit, in bytes. |

## The Compose files and `secrets/`

| Where | Java setting | Go key | Go variable | Notes |
|---|---|---|---|---|
| `docker-compose.yml`, `docker-compose.prod.yml` | `SPRING_DATA_MONGODB_HOST`, `_PORT`, `_REPLICA_SET_NAME` | `database.host` | `DATABASE_HOST` | Refused at start; use the `DATABASE_*` variables. The Go stack is `docker-compose.go.yml` (`make go-up`): PostgreSQL 16, Liquibase, then the service. |
| `docker-compose.yml` | `SPRING_PROFILES_ACTIVE`, `SPRING_CONFIG_LOCATION` | `-config` flag | `X9_CONFIG_FILE` | Refused at start. |
| `docker-compose.yml`, `docker-compose.prod.yml` | `X9_PUBLICENDPOINTS_HOST` | `public-endpoints.host` | `X9_PUBLICENDPOINTS_HOST` | Same. |
| `docker-compose.prod.yml` | `X9_PUBLICENDPOINTS_BASEPATH` | dropped | — | Always `/pub`. |
| `secrets/application-secrets.yml` | `jasypt.encryptor.password` | dropped | — | No Jasypt. |
| `secrets/application-secrets.yml` | `x9.certificate.private-keystore.password`, `private-key-password` | `certificate.private-keystore.password` | `X9_CERTIFICATE_PRIVATEKEYSTORE_PASSWORD` | And `X9_CERTIFICATE_PRIVATEKEYSTORE_PRIVATEKEYPASSWORD`. |
| `secrets/application-secrets.yml` | `x9.certificate.truststore.password` | `certificate.truststore.password` | `X9_CERTIFICATE_TRUSTSTORE_PASSWORD` | Plain text. |
| `secrets/certificate/*.jks` | keystore files | `certificate.private-keystore.location` | `X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION` | Converted to PKCS#12 if they are true JKS. |

## JKS to PKCS#12

Check the file first: since JDK 9 keytool writes PKCS#12 by default, whatever the file is called.

```bash
keytool -list -keystore x9-signing.jks -storepass "$OLD_STORE_PASSWORD" | head -1
# Keystore type: PKCS12   -> nothing to convert: point the Go build at the file as it is
# Keystore type: JKS      -> convert it as below
```

**The signing keystore** (one private key and its certificate chain). A PKCS#12 file has one
password, so give the key the store's password:

```bash
keytool -importkeystore \
  -srckeystore x9-signing.jks -srcstoretype JKS \
  -srcstorepass "$OLD_STORE_PASSWORD" \
  -srcalias "$KEY_ALIAS" -srckeypass "$OLD_KEY_PASSWORD" \
  -destkeystore x9-signing.p12 -deststoretype PKCS12 \
  -deststorepass "$NEW_PASSWORD" -destkeypass "$NEW_PASSWORD"
```

Then set `X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION=/path/to/x9-signing.p12`,
`X9_CERTIFICATE_PRIVATEKEYSTORE_PASSWORD` to `$NEW_PASSWORD`, and leave
`X9_CERTIFICATE_PRIVATEKEYSTORE_PRIVATEKEYPASSWORD` empty or equal to it. `$KEY_ALIAS` is the
Java build's `x9.certificate.private-keystore.enforced-alias`, or the one `PrivateKeyEntry` that
`keytool -list` shows. The file must hold exactly one private key.

**The truststore** (trusted certificates only):

```bash
keytool -importkeystore \
  -srckeystore x9-truststore.jks -srcstoretype JKS \
  -srcstorepass "$OLD_TRUSTSTORE_PASSWORD" \
  -destkeystore x9-truststore.p12 -deststoretype PKCS12 \
  -deststorepass "$OLD_TRUSTSTORE_PASSWORD"
```

Then set `X9_CERTIFICATE_TRUSTSTORE_LOCATION` and `X9_CERTIFICATE_TRUSTSTORE_PASSWORD`. A JCEKS
file converts the same way with `-srcstoretype JCEKS`. Never commit the converted files or their
passwords.

## Jasypt `ENC(…)` values

The Java build decrypted `ENC(…)` values with the Jasypt password (`jasypt.encryptor.password`,
`JASYPT_ENCRYPTOR_PASSWORD`, the chart's `secrets.configEncryption.password`). The Go build never
decrypts: it refuses an `ENC(…)` value, and any `JASYPT_*` variable, at start.

1. **Recover the plain value once,** on a trusted machine, with the same Jasypt version and
   algorithm the Java build used (`PBEWithHmacSHA512AndAES_256` in `application.yml`). Do it by
   hand, and do not write the plain value to a file inside the repository or to a shell history
   that is kept.
2. **Supply it to the Go build in one of these ways,** never in a committed file:
   - **an environment variable** from your secret store, for example a Kubernetes `Secret` read
     with `secretKeyRef`, or a Docker `--env-file` kept outside the repository;
   - **a mounted file**: `DATABASE_PASSWORD_FILE` reads the database password from one, and is
     re-read on every new connection; the keystore passwords can live in the Go YAML file itself,
     mounted from a `Secret` and named with `X9_CONFIG_FILE`.
3. **Remove** `JASYPT_ENCRYPTOR_PASSWORD` and the Jasypt password from the deployment.

## Data

Nothing is carried over ([ADR-0038](adr/0038-no-data-migration-from-the-java-build-a-written-mapping-instead.md)).
QR Codes created on the Java build do not exist on the Go build; create again the ones you still
need. An event consumer starts over: a stored Java cursor (a ULID) resumes at the start of the Go
log. The Java build and its MongoDB are left as they are, so the `java-final` image remains a way
back.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root. Creating a Derivative Work from this document — by AI/ML generation or by manual re-implementation based on it — is governed by that license (see the "Derivative Work" definition and Annex A).</sub>
