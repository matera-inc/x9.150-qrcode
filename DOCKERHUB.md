<!--
  Source of truth for the Docker Hub repository "Overview" of materainc/x9-qrcode.
  Docker Hub's overview can't be set via CLI — when this changes, paste the body below
  (everything under the first heading) into the repo's Overview in the Docker Hub web UI.
-->

# X9 QRCode — ANSI X9.150 Payment QR Code backend

A backend implementation of the **ANSI X9.150-2026 Payment QR Code Standard**. It plays the
**Payee-PSP** role: it creates and manages merchant-presented payment QR codes, serves the
JWS-signed payment payload a payer's app fetches, and receives payment notifications.

- **Rails:** US bank rails — **FedNow, RTP, ACH** — plus public blockchains (Bitcoin, Ethereum,
  Solana, Polygon, Base, XRP, Arc).
- **Currency-agnostic:** any ISO 4217 code or digital-asset ticker (USD, JPY, USDC, BTC, …) is
  carried through as-is.
- **Stack:** Java 25, Spring Boot 3.5.x, MongoDB. Clean / Hexagonal architecture.
- **Multi-arch:** `linux/amd64` + `linux/arm64`.

## Tags

| Tag | Meaning |
|-----|---------|
| `latest` | Newest build; auto-selects your architecture |
| `git-<short-sha>` | Immutable — pinned to a source commit (e.g. `git-3ddaee1`) |
| `latest-arm64` / `latest-amd64` | Explicit per-architecture images |

## Pull

```bash
docker pull materainc/x9-qrcode:latest
```

## Run

The image is the application only — it needs a **MongoDB replica set** (for multi-document
transactions). Point the container at your MongoDB:

```bash
docker run -p 8080:8080 \
  -e SPRING_DATA_MONGODB_HOST=your-mongo-host \
  -e SPRING_DATA_MONGODB_PORT=27017 \
  -e SPRING_DATA_MONGODB_REPLICA_SET_NAME=your-rs \
  materainc/x9-qrcode:latest
```

For an all-in-one local run (app + a single-node Mongo replica set), use the Docker Compose setup
in the source repo. Health check: `GET /actuator/health`. The API is open/unauthenticated by
design — secure it at your edge.

## Provenance

Every image records the exact source commit it was built from:

```bash
docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
  materainc/x9-qrcode:latest
```

## Source & license

- **Source:** https://github.com/matera-inc/x9.150-qrcode
- **License:** Matera Source License v1.0 (source-available; not open source). Provided AS IS.
- The **ANSI X9.150-2026** standard itself is copyrighted by ASC X9 and is **not** included —
  obtain it from the ANSI Web Store.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
