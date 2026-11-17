---
name: x9-qrcode
description: >-
  Onboard to the X9.150 Payment QR Code backend (Go on PostgreSQL). Use when someone wants to run
  the app locally, generate or decode QR codes, ask how the API / data model works (grounded in the
  OpenAPI + code), understand payment-request fields and why a create returns 400, look at or set up
  the PostgreSQL database, or deploy via Docker or Kubernetes. Lets a freshly-cloned developer start
  playing without reading the docs first.
---

# X9.150 QR Code — getting started

This backend is the **Payee-PSP** side of ANSI X9.150: it creates payment QR codes, serves the
signed payload a payer's app fetches, and validates the payment notifications payers send back. It
never moves or sees money, and never marks a QR Code `PAID` by itself: only the payment system does,
through the status-update API (`README.md`, "What this software does, and what it does not").
Help a newcomer go from a fresh clone to a generated QR code, and answer their questions about how
it works.

It is one **Go** binary on **PostgreSQL 16+**, with the schema applied by **Liquibase** before the
binary starts. The Java/MongoDB build was retired at 3.0.0 (ADR-0039); see "History" at the end.

## Ground rules for answering "spec" / data-model questions

The ANSI X9.150 standard text is **copyrighted and not in this repo**. Ground every answer only in
tracked sources — never quote or reproduce the ANSI PDF/MD (even if a copy is on the machine):

- `api/openapi.yaml` — the authoritative, annotated contract for every endpoint, field and rule.
- The Go code: `internal/billing/` (what a payment request may hold: rails, currencies, amounts,
  tips), `internal/domain/` (every lifecycle transition and event), `internal/notification/`,
  `internal/contract/` (wire types, the closed-field check), `internal/decoder/` (the EMV decoder).
- Tracked docs: `README.md`, `RUNNING.md`, `ENDPOINTS.md`, `STATE-MACHINE.md`, `AGENTS.md`,
  `official-spec/INTERPRETATION.md` (our reading of the spec), `official-spec/BEST-PRACTICES.md`,
  `playground/README.md`, `docs/adr/`.

Describing how *this software* behaves is fine — that's our own implementation.

## 1. Run it locally

Prereqs: Docker with `docker compose` v2, and Python 3 for the playground (standard library only).
Go (the version `go.mod` pins) is needed only to build or test outside a container.

```bash
make up          # builds x9-qrcode:latest, then PostgreSQL 16 -> bootstrap -> Liquibase -> service on :8080; waits for health UP
make smoke       # GET /actuator/health and /pub/.well-known/jwks on :8080
# ... work ...
make down        # stop everything and throw the database away
```

- Health: `curl -s localhost:8080/actuator/health` → `{"status":"UP","groups":["liveness","readiness"]}`.
- The service listens on `127.0.0.1` only. Port taken? `X9_PORT=18080 make up` (then use that port
  everywhere below, and `X9_BASE_URL` for the playground).
- Run a published image instead of building one: `X9_IMAGE=materainc/x9-qrcode:3 make up`.
- Optional settings (host, port, log level, image): `cp .env.sample .env`.
- By hand, without `make`: `docker compose up -d --build --wait` (needs BuildKit/buildx).
- It signs with the bundled **NON-PRODUCTION** demo identity and the database trusts every
  connection on the Compose network: local use only.
- The binary on the host instead of a container (fast inner loop, debugger): start only the
  database with `docker compose -f db/docker-compose.yml up -d --build`, export the `DATABASE_*`
  variables `RUNNING.md` "Option B" lists, then `make run-local` (or `go run ./cmd/x9-qrcode`).

`make help` lists every target. Full guide: `RUNNING.md`.

## 2. Generate a QR code (two ways)

**A — Playground (simplest; nothing to install).** Run from the repository root (or `cd playground`
and drop the prefix):

```bash
python3 playground/simulate_payee.py parking   # POSTs requests/qr-parking-createqr.json, writes playground/qr-parking.emv
python3 playground/simulate_payer.py parking   # signs a payload request, POST /pub/api/v1/loc/{id}, prints the signed payload
python3 playground/dumpjws.py playground/parking-call.jws   # decode a saved JWS (header + payload; does not verify)
bash playground/clean.sh                       # delete the generated .emv/.jws/.json files
```

- With no argument, each script shows a menu. Scenarios (`playground/requests/qr-*-createqr.json`):
  `burger` (tip), `waterbill` (discount + late fee, FedNow/RTP/ACH), `lab` (invoice with due
  date), `parking` (tiny amount), `donation` (editable amount), `cloudprovider` (deferred bill).
- `simulate_payer.py` asks whether to save the payload and the JWS exchange; answer `n` to only
  print. `X9_DATE_FOR_PAYMENT=YYYY-MM-DD` prices the payload on another day (e.g. to see a late fee).
- Another port or host: `X9_BASE_URL=http://localhost:18080 python3 playground/simulate_payee.py parking`.
- Details: `playground/README.md`.

**B — curl against the create endpoint:**

```bash
curl -sS -X POST http://localhost:8080/api/v1/payment-request \
  -H 'Content-Type: application/json' \
  --data-binary @playground/requests/qr-parking-createqr.json
# 201 {"id":"01A1…","qrCode":"000201…","qrCodeB64":"…","location":{"id":"…","endpoint":"https://x9-150.example.com/pub/api/v1/loc/…"}}

curl -sS http://localhost:8080/api/v1/payment-request/<id>         # the stored request, status ACTIVE

curl -sS -X POST http://localhost:8080/api/v1/qrcode-emv-decoder \
  -H 'Content-Type: application/json' -d '{"qrCode":"<the qrCode string>"}'   # the payload it points at
```

`qrCode` is the EMV string a QR image encodes; `qrCodeB64` is the same string in base64. The URL
inside it uses `X9_PUBLICENDPOINTS_HOST` (default placeholder `x9-150.example.com`, no scheme, at
most 37 characters; see `ENDPOINTS.md`) — set your own host before printing QR Codes for real scans.
The API is open and unauthenticated by design; protect it at the edge. Every endpoint:
`api/openapi.yaml` and `ENDPOINTS.md`; a Postman collection is in `others/postman/`.

Minimal create body (one USD method on FedNow):

```json
{
  "validUntil": "2030-12-31T23:59:59Z",
  "creditor": { "name": "MetroPark", "MCC": "7523",
    "address": { "line1": "210 Wacker Dr", "city": "Chicago", "state": "IL", "postalCode": "60606", "country": "US" } },
  "bill": { "description": "Parking - 2h", "paymentTiming": "immediate",
    "amountDue": { "amount": 600, "currency": "USD" } },
  "paymentNotification": { "kind": "DEFAULT" },
  "paymentMethods": [ { "currency": "USD", "amount": 600, "validUntil": "2030-12-31T23:59:59Z",
    "networks": { "fednow": { "routingNumber": "071000013", "accountNumber": "4402219980", "protectionType": "tokenized" } } } ]
}
```

A second method may pay the same bill in `USDC` or `FRNT` on Solana:
`{"currency": "USDC", "amount": 600, "validUntil": "…", "networks": {"solana": {"recipient": "<base58 address>", "memo": "parking"}}}`.

## 3. Why a create returns 400 (check these first)

The rule behind them is **strict to generate, tolerant to read** (`official-spec/INTERPRETATION.md`
I-16): create and PATCH refuse anything unknown, by name; decoding another bank's QR Code carries an
unknown rail intact and drops unknown fields. Every 400 is an RFC 7807 problem whose `violations`
list names the field.

- **Networks:** only `fednow`, `rtp`, `ach` and `solana`, spelled exactly so (lowercase keys).
  `FedNow`, `tron`, `zelle`, `pix`… → `Unsupported network(s): … This service supports fednow, rtp,
  ach and solana.` (I-1, I-2, I-9; ADR-0010, ADR-0012).
- **Currencies:** only `USD`, `USDC` and `FRNT` (`internal/billing/supported-currencies.json`),
  in uppercase — `usd` is refused with the spelling to use; `EUR` → `Currency EUR is not settled by
  any payment rail this deployment supports.` All three sit in one peg group
  (`pegged-currencies.json`), so they may appear together on one QR Code (I-3). No currency is ever
  implied: every payment method states its own.
- **Unknown fields at any level** are refused with their path:
  `creditor.address.county: is not a field of address, whose fields are …`. That includes
  server-assigned fields such as `status` or `id` on a create.
- **Rail fields are fixed:** bank rails take `routingNumber` (9 digits), `accountNumber` (4–17
  letters/digits) and `protectionType` (`tokenized` | `encrypted` | `plaintext`); `solana` takes
  `recipient` (base58, 32–44 chars) and optional `memo` — no `walletAddress`
  (`official-spec/SOLANA-FIELDS.md`).
- **Amounts are integer minor units** — `600` = $6.00, `19825` = $198.25 — at most **18 digits**
  (`999999999999999999`, ADR-0005), including after a late fee grows the amount. Send integers only:
  a fractional number is not refused today, it is truncated (`6.5` is stored as `6`).
- **`paymentTiming`**: `immediate` (default) | `deferred`. `deferred` **requires** `bill.invoice`
  with `number`, `date` (`YYYY-MM-DD`) and `dueDate` (`Bill invoice must be informed when DEFERRED
  payment timing.`).
- **Timestamps** (`validUntil`, `dueDate`) are UTC ending in `Z`; an offset such as `-03:00` is a
  400. `dueDate` and each `paymentMethods[].validUntil` must be no later than `validUntil`.
- **Tips** are integer percentages `0–999` (`presets`, `range`); `{"allowed": true}` needs a `range`
  or `presets`. The payer's `amount` is the total, tip on top of the bill (I-10).
- **`editable`** on a payment method means the amount is editable, and `range` is then required.
- **`paymentMethods` is an array**, with at least one entry, even for one method (I-5).
- **`status`** is server-assigned (`ACTIVE` → `PAYMENT_INITIATED` → `PAID` / `CANCELLED`,
  `STATE-MACHINE.md`); the payment system changes it with
  `POST /api/v1/payment-request/{id}/status-update`.

## 4. The database (PostgreSQL)

- One schema, `x9_qrcode` by default; tables `qr_code`, `qr_code_audit_log`,
  `qr_code_notification`, `payment_event` (the event log and outbox the payment system reads, by
  `GET /pub/api/v1/events` or by SQL/CDC — `docs/EVENTS-TABLE.md`), `event_sequence` and lookups.
- Four roles: owner (Liquibase only), application (the service), read-only, event reader.
- The changelog is `db/changelog/`. Liquibase applies it **before** the service starts; the service
  never runs DDL, and refuses to start on a schema it was not built for.
- Look at the local data: `docker compose exec postgres psql -U x9_qrcode_readonly postgres`
  (e.g. `select status_code, count(*) from x9_qrcode.qr_code group by 1;`).
- Your own PostgreSQL: the DBA's one-time step and everything else is in `db/README.md`
  ("Installing it in your database").

Configuration is one YAML file (`-config <path>` or `X9_CONFIG_FILE`) plus environment variables,
which win: `cmd/x9-qrcode/config.example.yaml` lists every key and its variable. Required:
`DATABASE_HOST`, `DATABASE_NAME`, `DATABASE_USER`, `DATABASE_PASSWORD` or `DATABASE_PASSWORD_FILE`,
and `DATABASE_SSLMODE`. Unknown keys, and any Spring/MongoDB/Jasypt setting from 2.x, are refused at
start, naming the key.

## 5. Deploy

- **Image:** `make image` builds `x9-qrcode:latest` for this machine (a multi-stage `Dockerfile`:
  static binary on distroless, non-root). Prebuilt, amd64 and arm64:
  `docker pull materainc/x9-qrcode:3` (3.0.0 and later). The image is the service only: it needs a
  PostgreSQL whose schema Liquibase has applied. `docker run` example and tags: `DOCKERHUB.md`.
- **Kubernetes (Helm):** the chart in `others/helm/x9-qrcode/chart` runs the service with
  Liquibase as an init container, against **your** PostgreSQL (it ships none). Required values
  (`values-minimal.yaml`): `application.x9.public-endpoints.host`, `go.database.host`,
  `go.database.name`; passwords come from a Secret you create (`go.existingSecret`, default
  `x9-qrcode-go`). One instance only (`HIGH-AVAILABILITY.md`).
  ```bash
  make helm-check   # lint and render the chart
  helm upgrade --install x9-qrcode others/helm/x9-qrcode/chart \
    -n <namespace> --create-namespace -f <your-values>.yaml --dry-run   # drop --dry-run to apply
  ```
  Guide: `others/helm/x9-qrcode/README.md`.

## 6. Test and contribute

- `make test` — the Go tests (race, shuffled, leak-checked; the database tests start PostgreSQL in
  Docker). `go test -short ./...` skips those. `others/scripts/ci-go.sh` runs everything CI's `go`
  job runs.
- `make compose-smoke` — `docker-compose.yml` on its own project and port: health and one create.
- `make blackbox-image` — the black-box suite against the image, all in containers
  (`others/blackbox/README.md`).
- `main` is protected: open a PR, sign off commits (`git commit -s`, DCO), let CI go green. See
  `CONTRIBUTING.md`.

## History: the Java build (2.x)

Up to 2.x the service was Java/Spring Boot on MongoDB. Its code is in git history under the tag
`java-final`, and its last image is `materainc/x9-qrcode:java-final` (frozen, no fixes). Someone
moving a 2.x deployment to 3.0.0 should read `UPGRADING-TO-3.md` and
`docs/CONFIGURATION-FROM-JAVA.md` (every 2.x setting mapped to its key). There is no data
migration: 3.0.0 starts on an empty database (ADR-0038). Never suggest Maven, `./mvnw`,
MongoDB or `SPRING_*` settings for the current build.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
