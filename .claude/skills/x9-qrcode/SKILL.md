---
name: x9-qrcode
description: >-
  Onboard to the X9.150 Payment QR Code backend. Use when someone wants to run the app locally,
  generate or decode QR codes, ask how the API / data model works (grounded in the OpenAPI + code),
  understand payment-request fields and why a create returns 400, configure MongoDB, or deploy via
  Docker or Kubernetes. Lets a freshly-cloned developer start playing without reading the docs first.
---

# X9.150 QR Code — getting started

This backend is the **Payee-PSP** side of ANSI X9.150: it creates payment QR codes, serves the
signed payload a payer's app fetches, and receives payment notifications. Help a newcomer go from a
fresh clone to a generated QR code, and answer their questions about how it works.

## Ground rules for answering "spec" / data-model questions

The ANSI X9.150 standard text is **copyrighted and not in this repo**. Ground every answer only in
tracked sources — never quote or reproduce the ANSI PDF/MD (even if a copy is on the machine):

- `x9-qrcode-infrastructure/src/main/resources/apis/openapi.yaml` — the authoritative, annotated
  contract for every endpoint, field, and rule.
- `x9-qrcode-domain/src/main/java/com/matera/x9qrcode/domain/` — value objects, `entity/QRCodeEntity`
  + `entity/validator/QRCodeEntityValidator`, enums (`vo/enumerated/`), policies (`service/`).
- Tracked docs: `README.md`, `RUNNING.md`, `ENDPOINTS.md`, `STATE-MACHINE.md`, `playground/README.md`.

Describing how *this software* behaves is fine — that's our own implementation.

## 1. Run it locally

Prereqs: JDK 25, Docker, (Python 3 for the playground). Maven is bundled (`./mvnw`).

```bash
make up          # app :8080 + MongoDB (1-node replica set) + mongo-express :9091
make smoke       # health check
# ... work ...
make down        # stop everything
```

Health: `http://localhost:8080/actuator/health`. To run on the host JVM instead of a container, use
`make run-local` (needs a reachable Mongo replica set). Details: `RUNNING.md`.

## 2. Generate a QR code (two ways)

**A — Playground (simplest; stdlib Python, nothing to install):**

```bash
cd playground
python3 simulate_payee.py parking     # POSTs requests/qr-parking-createqr.json -> writes qr-parking.emv, prints the EMV + id/loc
python3 simulate_payer.py parking     # fetches & prints the signed Payment Payload for that QR
```

`simulate_payee.py` with no argument lists the available `requests/qr-*-createqr.json` scenarios
(burger, waterbill, lab, parking, donation). See `playground/README.md`.

**B — curl against the create endpoint:**

```bash
curl -sS -X POST http://localhost:8080/api/v1/payment-request \
  -H 'Content-Type: application/json' \
  --data-binary @playground/requests/qr-parking-createqr.json
```

The response carries `qrCode` (the EMV string to render) and `qrCodeB64`. Decode any EMV with
`POST /api/v1/qrcode-emv-decoder` (`{"qrCode":"…"}`). Endpoint reference: `ENDPOINTS.md`.

Minimal create body (single USD FedNow rail):

```json
{
  "validUntil": "2030-12-31T23:59:59Z",
  "creditor": { "name": "MetroPark", "MCC": "7523",
    "address": { "line1": "210 Wacker Dr", "city": "Chicago", "state": "IL", "postalCode": "60606", "country": "US" } },
  "bill": { "description": "Parking - 2h", "paymentTiming": "immediate",
    "amountDue": { "amount": 600, "currency": "USD" } },
  "paymentNotification": { "kind": "DEFAULT" },
  "paymentMethods": [ { "currency": "USD", "amount": 600, "validUntil": "2030-12-31T23:59:59Z",
    "networks": { "FedNow": { "routingNumber": "071000013", "accountNumber": "4402219980", "protectionType": "tokenized" } } } ]
}
```

## 3. Field/value rules that cause a 400 (check these first)

- **`paymentTiming`** is lowercase: `immediate` | `deferred`. `deferred` **requires**
  `bill.invoice` with `number`, `date`, and a `dueDate` (UTC, ends in `Z`; ≤ `validUntil`).
  Enforced in `domain/vo/BillVO.java`.
- **Amounts are int64 minor units** — `600` = $6.00, `19825` = $198.25. No decimals/floats.
- **Currency mixing:** all currencies on one QR must share a pegged group, or be a single currency
  (a lone `BTC` is fine; `USD`+`BTC` is rejected). Groups in `pegged-currencies.json`; rule in
  `domain/service/PeggedCurrencyMixPolicy.java`.
- **`protectionType`** (bank rails) is lowercase (`tokenized`); crypto networks carry only a
  `walletAddress`.
- **`status`** is UPPERCASE and server-assigned: `ACTIVE`, `PAYMENT_INITIATED`, `PAID`, `CANCELLED`
  (`STATE-MACHINE.md`) — you don't set it on create.
- **Tips** are integer percentages `0–999`. **`editable`**: its presence means editable; `range`
  is then required.

## 4. Configure MongoDB

Transactions require a **replica set** — `make up` starts a 1-node set automatically. Keys:
`spring.data.mongodb.{host,port,replica-set-name,database}` (`application.yml` /
`application-default.yml`), overridable via `SPRING_DATA_MONGODB_*` env vars; in Kubernetes it's a
full URI secret `spring.data.mongodb.uri`. See `docker-compose.yml`, `HIGH-AVAILABILITY.md`.

## 5. Deploy

- **Container image** (buildpacks, no Dockerfile): `make image` → `x9-qrcode:latest`
  (`make image-amd64` for amd64/Intel).
- **Kubernetes (Helm):**
  ```bash
  helm upgrade --install x9-qrcode others/helm/x9-qrcode/chart \
    -n <namespace> --create-namespace \
    -f others/helm/x9-qrcode/chart/values.yaml --dry-run   # drop --dry-run to apply
  ```
  The chart deploys the **app only** and expects a reachable external Mongo replica set (secret
  `spring.data.mongodb.uri`). Guide: `others/helm/x9-qrcode/README.md`.

## 6. Contributing

`main` is protected: open a PR, sign commits (`git commit -s`, DCO), and let CI (`build`) + DCO go
green before squash-merging. See `CONTRIBUTING.md`.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
