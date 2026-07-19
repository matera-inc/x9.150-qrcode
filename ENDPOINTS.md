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
| `/api/v1/qrcode-emv-decoder` | POST | Decode an EMV QR string |
| `/api/v1/signature/generate` | POST | Generate a JWS for JSON content |
| `/api/v1/signature/validate` | POST | Validate a JWS |

The authoritative contract is the OpenAPI spec:
[`x9-qrcode-infrastructure/src/main/resources/apis/openapi.yaml`](x9-qrcode-infrastructure/src/main/resources/apis/openapi.yaml).

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
- Ephemeral `*.trycloudflare.com` names can be long and may blow the 37-char host budget. For a
  stable, short URL use a **named tunnel with your own domain** (e.g. `x9.mydomain.dev`).
- The bundled keystore is **self-signed**, so a strict payment client may not trust the signature
  chain — fine for scan/plumbing tests; supply real X9-issued keys via `secrets/` for full trust.
- Any equivalent public-HTTPS tool works the same way: `ngrok http 8080`, Tailscale Funnel, etc.
  — just keep the resulting hostname ≤ 37 chars.

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
