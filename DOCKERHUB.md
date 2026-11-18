<!--
  Source of truth for the Docker Hub repository "Overview" of materainc/x9-qrcode.

  It is pushed with `make dockerhub-overview`, which PATCHes everything below this comment to
  the Hub API using the credentials `docker login` already stored. The Overview drifted badly
  once — it advertised Bitcoin, Ethereum, Polygon, Base, XRP and Arc, and "any ISO 4217 code",
  for weeks after this service began refusing all of them by name. A page that promises what
  the service declines is worse than no page, so this is scripted rather than remembered.
-->

# X9 QRCode — ANSI X9.150 Payment QR Code backend

A backend implementation of the **ANSI X9.150-2026 Payment QR Code Standard**. It plays the
**Payee-PSP** role: it creates and manages merchant-presented payment QR codes, serves the
JWS-signed payment payload a payer's app fetches, and receives payment notifications. Your own
systems learn that a QR Code was paid by reading its **payment event stream**
(`GET /pub/api/v1/events`) — cursor-paged and long-polling.

It can also play the **payer** side: `/api/v1/payment-notification/pre-payment` and `/post-payment`
compose, sign and deliver a notification to another deployment, so a PSP integrating here never
builds a JWS or manages a keystore.

> **3.0.0 changed the database and the configuration.** From 3.0.0 this image is the Go build on
> **PostgreSQL**; up to 2.x it was the Java build on MongoDB. The HTTP contract is the same, but the
> database, the configuration, the secrets and the logs are not: a 2.x deployment does not start on
> 3.0.0 unchanged. Before you move, read
> [UPGRADING-TO-3.md](https://github.com/matera-inc/x9.150-qrcode/blob/main/UPGRADING-TO-3.md).
> The last Java build stays available as **`java-final`**: that is the way back, and it receives no
> more fixes.

- **Rails:** the US bank rails the standard defines — **FedNow, RTP, ACH** — and **Solana**, on the
  embedding its Foundation published. Any other network is **refused at creation, by name**, rather
  than advertised on a QR Code nobody can pay.
- **Currencies:** the payload format is currency-agnostic and repeats whatever code it is given, but
  a deployment accepts only what its rails settle. This image ships with **USD, USDC and FRNT**
  (`supported-currencies.json`); anything else is refused at creation. Add a currency there when you
  add a rail that settles it.
- **Stack:** Go, a static cgo-free binary on a distroless image, running as non-root; PostgreSQL
  16 or newer, its schema applied by Liquibase before the service starts.
- **Multi-arch:** `linux/amd64` + `linux/arm64`, one manifest per tag.

## Tags

| Tag | Meaning |
|-----|---------|
| `3.0.1`, `3.0`, `3` | A release (`3.0.0` stays), and the newest release of its minor and major line |
| `latest` | Newest release; auto-selects your architecture |
| `git-<short-sha>` | Immutable — pinned to a source commit (e.g. `git-a9cb57e`) |
| `git-<short-sha>-amd64` / `-arm64` | The per-architecture images that tag is made of |
| `java-final` | The last Java build (2.x, MongoDB). Frozen; no more fixes |

## Pull

```bash
docker pull materainc/x9-qrcode:3
```

## Run

The image is the service only. It needs a **PostgreSQL 16+** database whose schema has been
applied with Liquibase from the repository's `db/changelog`, by a migration role; the service
connects as the application role and never runs DDL. See
[db/README.md](https://github.com/matera-inc/x9.150-qrcode/blob/main/db/README.md).

```bash
docker run -p 8080:8080 \
  -e DATABASE_HOST=your-postgres-host \
  -e DATABASE_NAME=your-database \
  -e DATABASE_USER=x9_qrcode_app \
  -v "$PWD/db-password:/run/secrets/x9-qrcode-db-password:ro" \
  -e DATABASE_PASSWORD_FILE=/run/secrets/x9-qrcode-db-password \
  -e DATABASE_SSLMODE=verify-full \
  -e X9_PUBLICENDPOINTS_HOST=x9.example.com \
  materainc/x9-qrcode:3
```

Every setting is in
[`config.example.yaml`](https://github.com/matera-inc/x9.150-qrcode/blob/main/cmd/x9-qrcode/config.example.yaml).
For an all-in-one local run (PostgreSQL, Liquibase and the service), use `docker compose up` with
the `docker-compose.yml` of the source repository. Health check: `GET /actuator/health`. The image
starts with a bundled NON-PRODUCTION demo signing identity (EC P-256, signing `ES256` from 3.0.1;
your own RSA keystore still defaults to `PS512`); supply your own keystore for anything real. The
API is open/unauthenticated by design — secure it at your edge.

## Provenance

Every image records the exact source commit it was built from:

```bash
docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
  materainc/x9-qrcode:3
```

`docker run --rm materainc/x9-qrcode:3 -version` prints the version and the commit too.

## Source & license

- **Source:** https://github.com/matera-inc/x9.150-qrcode
- **License:** Matera Source License v1.0 (source-available; not open source). Provided AS IS.
- The **ANSI X9.150-2026** standard itself is copyrighted by ASC X9 and is **not** included —
  obtain it from the ANSI Web Store.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
