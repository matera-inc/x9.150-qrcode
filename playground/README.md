# 🛝 Playground

Welcome — this is your sandbox for the **ANSI X9.150** payment-QR flow. Build the app, run it, and
poke at it end to end: a **merchant** creates a payment QR Code, and a **payer** scans it and
retrieves the signed payment payload. No real bank, no real certificates — everything runs locally
against the bundled **non-production** demo keystore. Have fun. 🎉

Run everything below **from the repository root** unless noted.

---

## 0. Prerequisites

- **JDK 25** (`java -version`). Maven is bundled — use `./mvnw`, no install needed.
- **Docker** (Docker Desktop, or **Colima**).
- **Python 3** (standard library only — no `pip install` needed).

## 1. Build the container image

The image is built with Cloud Native Buildpacks (no Dockerfile). Pick your architecture:

```bash
make image           # native arch  → x9-qrcode:latest        (arm64 on Apple Silicon)
make image-amd64     # amd64 / intel → x9-qrcode:latest-amd64  (uses Rosetta on Apple Silicon)
```

> Building **amd64** on Apple Silicon needs **Rosetta** in your Docker/Colima VM. Quick check:
> `docker run --rm --platform=linux/amd64 alpine uname -m` → should print `x86_64`.

(You don't strictly need to build the image to play — `make up` will use whatever `x9-qrcode:latest`
you last built. But building it is part of the fun.)

## 2. Run it

```bash
make up          # starts the app (:8080) + MongoDB + mongo-express (:9091)
make smoke       # quick health check
make down        # stop everything when you're done
```

- App: <http://localhost:8080> — open, unauthenticated API
- Health: <http://localhost:8080/actuator/health>
- **mongo-express** (browse the DB): <http://localhost:9091> — `admin` / `admin`

## 3. Play with Python

### a) Merchant — create a QR

`x9-create.json` describes a coffee shop charging **$100.00**, payable via **FedNow** (USD) or
**USDC** on **Solana / Ethereum**, no tip, no late fee:

```bash
curl -s -X POST localhost:8080/api/v1/payment-request \
  -H 'Content-Type: application/json' --data @playground/x9-create.json | jq .
```

The response's `qrCode` is the **EMV string** (saved here as `x9-created.emv`). Notice it only carries
a reference URL + merchant basics — **not** the amount or the payment networks. Those live in the
protected payload.

### b) Payer — scan it and fetch the payload

```bash
python3 playground/simulate_payer.py
```

What it does (all stdlib, no dependencies):

1. base64-encodes the scanned EMV as `qrCodeContent`.
2. Signs it as a **JWS** — for convenience via the app's own `/api/v1/signature/generate` (it signs
   with the demo cert the truststore trusts). A real payer would sign with its **own X9-issued
   certificate**.
3. The single payer call: `POST /pub/api/v1/loc/{id}` with the JWS → the payload comes back as a JWS.
4. Verifies the response **echoes the payer's `correlationId`** (proof it's the reply to *this*
   request), then decodes and saves the payload JSON.

Expected tail:

```
[verify]  echoed back?  True   (statusCode=200)
[payload] saved x9-payer.json
```

The retrieved `x9-payer.json` now holds the **full** bill — the $100 amount and every payment method
(FedNow account, Solana/Ethereum wallets) — none of which were in the scannable QR.

## 4. Files in here

| File | What it is |
|------|------------|
| `x9-create.json` | Merchant's create request (edit me!) |
| `x9-created.emv` | The EMV QR string that request produced |
| `simulate_payer.py` | The payer simulation (stdlib only) |
| `x9-payer.json` | Example decoded payload the payer retrieves |
| `x9-payer.jws` | The payer's signed request — **generated** each run (git-ignored) |

## 5. Ideas to mess around with

- **Change the bill** in `x9-create.json` — amount, add a `tip`, swap networks, add a discount/late
  fee. ⚠️ Use a **new `locationId`** each time (a location can't be reused while its QR is ACTIVE).
- **Decode any EMV** string: `POST /api/v1/qrcode-emv-decoder` with `{"qrCode":"…"}`.
- **Scan from a phone on 5G** — point `x9.public-endpoints.host` at a tunnel; see
  [`../ENDPOINTS.md`](../ENDPOINTS.md).
- **Point the payer script elsewhere:** `X9_BASE_URL=http://host:port python3 simulate_payer.py`.
- Browse what got stored in the `qrcodes` collection via mongo-express (<http://localhost:9091>).

Break things, read the errors (they're field-level RFC-7807 problems), and enjoy. 🛝
