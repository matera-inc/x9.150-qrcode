# Open-Source Third-Party Notices

X9 QRCode is built on the open-source software listed below. We gratefully acknowledge their
authors and communities. Each dependency remains under its own license; this file is provided for
attribution and license compliance (e.g. the Apache-2.0 NOTICE and MIT/BSD attribution
requirements).

Since 3.0.0 the service is the Go build ([ADR-0039](docs/adr/0039-java-retired-at-3-0-0.md)). The
Java build's notices (Spring Boot, the MongoDB driver, Jackson, Jasypt and the rest of its Maven
dependency tree) are in this file at the git tag `java-final`.

## Linked into the binary

These Go modules are compiled into `x9-qrcode`, the only program in the image. The list is what
`go list -deps ./cmd/x9-qrcode` reports for the versions `go.mod` pins; each licence was read from
the module's own `LICENSE` file. CI's `go` job checks it on every pull request
(`others/scripts/ci-go.sh licenses`: `go-licenses check`, allowing only Apache-2.0, BSD-2-Clause,
BSD-3-Clause, MIT, ISC and Zlib).

| Module | Version | License | Used for |
|---|---|---|---|
| [`github.com/go-jose/go-jose/v4`](https://github.com/go-jose/go-jose) | v4.1.9 | Apache-2.0 | JWS parsing and signing |
| [`github.com/google/uuid`](https://github.com/google/uuid) | v1.6.0 | BSD-3-Clause | QR Code ids (UUIDv7) and `correlationId`s |
| [`github.com/jackc/pgx/v5`](https://github.com/jackc/pgx) | v5.13.0 | MIT | the PostgreSQL driver and pools |
| [`github.com/jackc/pgpassfile`](https://github.com/jackc/pgpassfile) | v1.0.0 | MIT | pgx: `.pgpass` files |
| [`github.com/jackc/pgservicefile`](https://github.com/jackc/pgservicefile) | v0.0.0-20240606120523-5a60cdf6a761 | MIT | pgx: service files |
| [`github.com/jackc/puddle/v2`](https://github.com/jackc/puddle) | v2.2.3 | MIT | pgx: the connection pool |
| [`go.yaml.in/yaml/v3`](https://github.com/yaml/go-yaml) | v3.0.6 | MIT and Apache-2.0 (with a NOTICE) | the configuration file |
| [`golang.org/x/crypto`](https://pkg.go.dev/golang.org/x/crypto) | v0.58.0 | BSD-3-Clause | OCSP |
| [`golang.org/x/sync`](https://pkg.go.dev/golang.org/x/sync) | v0.24.0 | BSD-3-Clause | pgx |
| [`golang.org/x/text`](https://pkg.go.dev/golang.org/x/text) | v0.43.0 | BSD-3-Clause | pgx |
| [`software.sslmate.com/src/go-pkcs12`](https://github.com/SSLMate/go-pkcs12) | v0.7.4 | BSD-3-Clause | PKCS#12 keystores and truststores |
| [The Go standard library](https://go.dev) | go1.27.4 | BSD-3-Clause | everything else, from HTTP to X.509 |

The NOTICE of `go.yaml.in/yaml/v3`: *Copyright 2011-2016 Canonical Ltd. Licensed under the Apache
License, Version 2.0.*

## Used only to build and test

Not in the image. The direct test dependencies are
[`github.com/google/go-cmp`](https://github.com/google/go-cmp) (v0.7.0, BSD-3-Clause) and
[`github.com/testcontainers/testcontainers-go`](https://github.com/testcontainers/testcontainers-go)
(v0.45.0, MIT), with their own transitive modules. The tools pinned by the `tool` block of
[`go.mod`](go.mod) — golangci-lint, nilaway, govulncheck, go-licenses and cyclonedx-gomod — run in
CI and on a developer's machine only, and pull in the rest of `go.mod`'s indirect requirements. The
black-box suite's Python dependencies are pinned in
[`others/blackbox/requirements.txt`](others/blackbox/requirements.txt).

## Run beside the service

| Software | License | Where |
|---|---|---|
| [distroless `static-debian13:nonroot`](https://github.com/GoogleContainerTools/distroless) | Apache-2.0 (the project); the Debian packages it holds (`base-files`, `ca-certificates`, `netbase`, `tzdata`) under their own licences | the image's base ([`Dockerfile`](Dockerfile)) |
| [PostgreSQL](https://www.postgresql.org) 16 or newer | The PostgreSQL License | the database; not shipped in the image |
| [Liquibase](https://www.liquibase.com) 4.33.0 | Apache-2.0 | applies [`db/changelog/`](db/changelog/) before the service starts ([ADR-0031](docs/adr/0031-same-image-lighter-and-3-0-is-a-configuration-migration.md)); its own image |

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
