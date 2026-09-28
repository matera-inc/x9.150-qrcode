# Endpoints & Local Scan Testing

This service plays the **Payee PSP** role in the ANSI X9.150 flow. It exposes two kinds of HTTP
endpoints:

- **Management APIs** — used by the merchant/biller back office to create and manage QR code
  payment requests.
- **Public endpoints** — served under the `/pub` base path. Called by the **Payer PSP** and
  payment apps to retrieve the signed payload, verify signatures, and post payment notifications.

> **This service exposes an open, unauthenticated API.** Access control is intentionally out of
> scope — protect it at your edge (API gateway, mTLS, or network policy). The management and
> public endpoints alike ship with no built-in authentication; the deployer is responsible for
> restricting who can reach them.

All public URLs share **one origin** — `x9.public-endpoints.host` — plus `base-path` (`/pub`).
Change the host and the payload, notification, JWKS, and certificate URLs all move together.

```
x9:
  public-endpoints:
    host: x9-150.example.com          # advertised origin (no scheme); default placeholder
    base-path: /pub
    payload-path:              ${base-path}/api/v1/loc
    payment-notification-path: ${base-path}/api/v1/payment-notification
    jwk-set-path:              ${base-path}/.well-known/jwks
    certificate-path:          ${base-path}/.well-known/certificate
```

## Public endpoints (origin = `${host}`, prefix = `/pub`)

| Path | Method | Purpose | Advertised **where** |
|------|--------|---------|----------------------|
| `/pub/api/v1/loc/{id}` | POST | Return the JWS-signed **Payment Payload** for a QR location (the Payer PSP sends a signed request body). | **Inside the QR string** — EMV tag `26` (GUI `org.x9`) |
| `/pub/api/v1/payment-notification` | POST | Receive a signed **Payment Notification** from the Payer PSP confirming payment status. | Signed payload response (`paymentNotification.endpoint`) |
| `/pub/.well-known/jwks` | GET | **JWK Set** — the public signing keys. | JWS header `jku` |
| `/pub/.well-known/certificate/{pemFileName}` | GET | Public **X9 signing certificate** (PEM). | JWS header `x5u` |
| `/pub/api/v1/events` | GET | **Payment event stream** — cursor-paged, long-polling. How the software around X9.150 learns that a QR Code was paid. | Not advertised; the consuming system is configured with it |

> Only the **loc URL** is embedded in the QR itself. The notification URL travels inside the
> signed payload response; the JWKS/certificate URLs travel in JWS headers.
>
> **Per-QR override:** if a QR carries a creditor-supplied `paymentNotification.endpoint`, that
> value is used for the notification URL of that QR; otherwise the host-derived URL is used. The
> loc URL is always host-derived.

## Management endpoints (unauthenticated — protect at your edge)

| Path | Method | Purpose |
|------|--------|---------|
| `/api/v1/payment-request` | POST | Create a new QR code payment request |
| `/api/v1/payment-request/{id}` | GET | Retrieve QR code data by revision |
| `/api/v1/payment-request/{id}` | PATCH | Update a QR code payment request |
| `/api/v1/payment-request/{id}/status-update` | PUT | Change QR code status |
| `/api/v1/qrcode-emv-decoder` | POST | Decode an EMV QR string, fetching and verifying the payee's signed payload |
| `/api/v1/payment-notification/pre-payment` | POST | **Payer side.** Announce a payment to the payee and return their verdict |
| `/api/v1/payment-notification/post-payment` | POST | **Payer side.** Report a completed payment, with its `transactionId` |
| `/api/v1/signature/generate` | POST | Generate a JWS for JSON content |
| `/api/v1/signature/validate` | POST | Validate a JWS |

> **Two roles, one service.** Most endpoints serve the **payee** — the side that issues a QR Code
> and is told about payments. The two under `/api/v1/payment-notification/` serve the **payer**: they
> compose, sign and deliver a notification to *someone else's* X9.150 deployment, so a PSP
> integrating here never builds a JWS or manages a keystore. A deployment may play either role, or
> both. `others/demo/two-instance-payment-cycle.sh` runs one of each against the other.

The authoritative contract is the OpenAPI spec:
[`x9-qrcode-infrastructure/src/main/resources/apis/openapi.yaml`](x9-qrcode-infrastructure/src/main/resources/apis/openapi.yaml).

## Consuming the payment event stream

This is how the software around X9.150 learns that a QR Code was paid. It is the integration point
that matters most and the one least visible from the endpoint table, so it gets its own section.

```bash
curl "https://x9.example.com/pub/api/v1/events?after=&limit=100&wait=25"
```

```json
{
  "events": [
    { "eventId": "63e79f1f-986e-4564-81d1-f18fd2343752",
      "type": "payment.sent",
      "occurredAt": "2026-09-27T22:31:40.230Z",
      "qrCodeId": "01A0E4FE9141F8EBAA2DB007D4B88A81",
      "qrCodeRevision": 2,
      "amount": 22500, "currency": "USDC", "network": "Solana",
      "transactionId": "5Vfydn…", "invoiceNumber": "INV-2026-09-00124",
      "schemaVersion": "1.0" }
  ],
  "nextCursor": "01M3JFXNB4XNR7P61NZVG8474J",
  "hasMore": false
}
```

| Parameter | Meaning |
|---|---|
| `after` | The previous response's `nextCursor`. Omit or send empty to start at the beginning of retained history. |
| `limit` | 1–500, default 100. |
| `wait` | Seconds to hold the request open when there is nothing to return (0–30, default 0). An idle consumer costs one parked request instead of a poll loop; virtual threads make the hold nearly free. |

**The five rules that decide whether your integration is correct:**

1. **Deduplicate by `eventId`.** Delivery is at-least-once. A consumer that crashes before
   persisting its cursor re-reads events, and `eventId` is stable across re-publishes.
2. **Persist `nextCursor` only after the events are safely stored.** Cursor first means silent loss.
3. **One poller per deployment.** Two pollers each see everything, which gains no isolation and only
   invites the belief that it provides some.
4. **`payment.sent` is not "paid".** It means a payer *reported* a transaction. X9.150 never touches
   money and cannot observe settlement — only `payment.cleared` says funds arrived, emitted when a
   system that actually saw them says so via `PUT /api/v1/payment-request/{id}/status-update`.
5. **Ignore what you do not recognise.** Unknown fields and unknown `type` values are additive
   within a major version; failing on them will break you on our next release.

Ordering is per `qrCodeId`. Nothing is promised across QR Codes, and `qrCodeRevision` is monotonic
per QR Code but **not gap-free** — a notification that only records details bumps the revision
without emitting an event.

The stream is **operator-internal, not merchant-facing**: it returns every QR Code's events for the
deployment, because X9.150 is tenant-agnostic and has no axis to filter on. Fanning out to the right
biller is the consuming system's job, using the mapping it already owns from having created the QR
Code. See [ADR-0014](docs/adr/0014-we-transport-and-sequence-the-consumer-reconciles.md).

## Host length constraint

The loc URL is packed into the QR's EMV tag `26`. The EMV Merchant Account Information GUI field
caps the whole URL at `99 − 4 − 4 − len("org.x9")` = **85 characters**. The path and shortened id
consume `/pub/api/v1/loc/` (16) + a 32-char id = 48, so:

> **The host must be ≤ 37 characters.** Longer hosts are rejected at startup (`X9Properties`
> validates the payload domain length).

## Configuring at launch (Docker Compose)

Every setting is a Spring property, and Spring maps environment variables to properties via
**relaxed binding** — so you can define the URLs (and anything else) at launch without editing YAML.

> **Relaxed-binding rule:** uppercase the property and replace `.` / `-` with `_`, dropping the
> dash inside kebab segments — `x9.public-endpoints.host` → `X9_PUBLICENDPOINTS_HOST`.

`docker-compose.prod.yml` is built for this: all configuration is environment variables with
sensible defaults. Override them three ways:

1. **`.env` file** (Compose auto-loads it) — copy the sample and edit:
   ```bash
   cp .env.sample .env          # then set X9_PUBLICENDPOINTS_HOST=x9.mydomain.dev, etc.
   docker compose -f docker-compose.prod.yml up -d
   ```
2. **Shell, one launch:**
   ```bash
   X9_PUBLICENDPOINTS_HOST=x9.mydomain.dev \
     docker compose -f docker-compose.prod.yml up -d
   ```
3. **Edit the `environment:` block** in the compose file directly.

Common knobs:

| Env var | Property | Default |
|---------|----------|---------|
| `X9_PUBLICENDPOINTS_HOST` | `x9.public-endpoints.host` | `x9-150.example.com` |
| `X9_PUBLICENDPOINTS_BASEPATH` | `x9.public-endpoints.base-path` | `/pub` |
| `SPRING_DATA_MONGODB_HOST` | `spring.data.mongodb.host` | `mongo` (compose service) |
| `X9_CERTIFICATE_PRIVATEKEYSTORE_LOCATION` | `x9.certificate.private-keystore.location` | bundled sample keystore |
| `X9_CERTIFICATE_TRUSTSTORE_LOCATION` | `x9.certificate.truststore.location` | bundled sample truststore |

The local `docker-compose.yml` path instead mounts `application-default.yml` as the config file,
but you can still override any single value by adding an env var to its `environment:` block —
env vars win over the mounted file (the public host is already wired this way).

## Talking to another deployment (HTTPS required)

A payer normalises any host that is not its own to **`https`** before fetching a payload or a JWK
set, so two instances cannot complete a payment over plain HTTP — see
[`others/demo/README.md`](others/demo/README.md), which explains the rule, gives three ways to
satisfy it (Spring's own TLS, an nginx in front, or a tunnel), and ships a script that plays a whole
payment between two instances.

## Testing a real scan: computer → phone on 5G (Cloudflare Tunnel)

**Scenario:** you generate a QR on your laptop (app on `localhost:8080`) and want to scan it with
a phone on **cellular (5G)**. The phone is not on your LAN and cannot reach `localhost`, so the QR
must embed a **public HTTPS host** that routes back to your local app.

[Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)
gives you that public URL without opening any firewall ports.

```bash
# 1. Install cloudflared (macOS)
brew install cloudflared

# 2. Expose your local app on a public URL (ephemeral — no account/domain needed)
cloudflared tunnel --url http://localhost:8080
#   → prints something like:  https://random-words.trycloudflare.com
```

Point the app's advertised host at the tunnel hostname so generated QR codes embed it:

```bash
# no scheme, must be <= 37 chars
X9_PUBLICENDPOINTS_HOST=random-words.trycloudflare.com \
  mvn -pl x9-qrcode-infrastructure spring-boot:run
```

Now create a QR code. Its embedded loc URL — `…trycloudflare.com/pub/api/v1/loc/{id}` — resolves
from anywhere, so a phone on 5G can scan it and fetch the payload over HTTPS. The same tunnel also
serves `/pub/.well-known/jwks` and `/pub/.well-known/certificate/...`, so Payer-side signature
verification works end to end.

**Notes**
- **An anonymous quick tunnel will not fit.** `cloudflared tunnel --url` invents a four-word name,
  and those run 40–50 characters before the domain is counted — a real one, measured:
  `acne-consists-positions-logs.trycloudflare.com` is 46 against a budget of 37. The app refuses to
  start rather than emit a QR Code that cannot be scanned. There is no configuration around it:
  `payload-path` shortens only the *advertised* URL, while the path actually served is fixed by the
  OpenAPI contract, so a shorter one just 404s. Use a **named tunnel with your own domain** (e.g.
  `x9.mydomain.dev`), which also survives a restart.
- A named tunnel takes its ingress from Cloudflare when the account has a remote configuration for
  it, and then **ignores `--url` and any local config file**. If the hostname 530s while the
  connector reports healthy connections, check `cloudflared`'s log for `Updated to new
  configuration` — that is the dashboard's ingress overriding yours.
- The bundled keystore is **self-signed**, so a strict payment client may not trust the signature
  chain — fine for scan/plumbing tests; supply real X9-issued keys via `secrets/` for full trust.
- Any equivalent public-HTTPS tool works the same way: `ngrok http 8080`, Tailscale Funnel, etc.
  — just keep the resulting hostname ≤ 37 chars.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
