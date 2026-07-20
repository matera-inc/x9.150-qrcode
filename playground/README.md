# 🛝 Playground

Your sandbox for the **ANSI X9.150** payment-QR flow. Build the app, run it, and drive it end to
end with two little scripts:

- **`simulate_payee.py`** — the *merchant*: pick a bill, create the QR, get its EMV string.
- **`simulate_payer.py`** — the *payer*: pick a QR, fetch the signed payment payload.

Everything runs locally against the bundled **non-production** demo keystore — no real bank, no real
certificates. Run the commands below **from the repository root** unless noted.

---

## 0. Prerequisites
- **JDK 25** (`java -version`). Maven is bundled — use `./mvnw`.
- **Docker** (Docker Desktop or **Colima**).
- **Python 3** (standard library only — nothing to `pip install`).

## 1. Build the image
```bash
make image           # native arch  → x9-qrcode:latest        (arm64 on Apple Silicon)
make image-amd64     # amd64 / intel → x9-qrcode:latest-amd64  (uses Rosetta on Apple Silicon)
```
> amd64 on Apple Silicon needs **Rosetta**: `docker run --rm --platform=linux/amd64 alpine uname -m` → `x86_64`.

## 2. Run it
```bash
make up          # app (:8080) + MongoDB + mongo-express (:9091)
make smoke       # quick health check
make down        # stop everything
```
- App: <http://localhost:8080> (open, unauthenticated API) · mongo-express: <http://localhost:9091> (`admin`/`admin`)

## 3. Play — merchant → payer

From inside `playground/`:

### a) 🧾 Merchant — create a QR
```bash
python3 simulate_payee.py            # menu of requests/qr-*-createqr.json
python3 simulate_payee.py burger     # or pick by name / number
```
POSTs the chosen create request, the app stores it (Mongo) and generates the QR, and the script
writes **`qr-<name>.emv`** — the EMV string a QR image would encode.

### b) 📲 Payer — fetch the payload
```bash
python3 simulate_payer.py            # menu of the qr-*.emv you've made
python3 simulate_payer.py burger     # or pick directly
```
It base64-wraps the scanned EMV, signs it as a **JWS**, makes the single call
`POST /pub/api/v1/loc/{id}` (the payload comes back as a JWS), verifies the response **echoes the
correlationId**, and prints the full payload on screen. Then it asks:

```
Save the payload JSON (Y/n):                     → <name>-payload.json
Save the JWS exchange (call + response) (Y/n):   → <name>-call.jws  +  <name>-response.jws
```
(Enter = yes.) The payload holds the **full** bill — amount + every payment method — none of which
was in the scannable QR.

## 4. The create requests (`requests/`)

The only committed source: the create-request bodies. Edit them or add your own
`qr-<name>-createqr.json` (it shows up in the menu automatically).

| File | Scenario | Shows off |
|------|----------|-----------|
| `qr-burger-createqr.json` | Burger joint, $24.50 | **tip** presets/range |
| `qr-waterbill-createqr.json` | Water utility, $87.30 | **discount + late-fee formula**, 3 bank rails (FedNow/RTP/ACH) |
| `qr-lab-createqr.json` | Helix Diagnostics Lab, $312.00 | invoice + USDC on Ethereum |
| `qr-parking-createqr.json` | Parking, $6.00 | tiny amount, USDC on Polygon |
| `qr-donation-createqr.json` | Charity | **editable amount** + **Bitcoin-only** (non-pegged can't mix) |

> A `qr-*-createqr.json` is the **create request** — the merchant's input to *make* a QR. It is
> **not** a full X9.150 payload: it may carry a **late-fee formula** (the server computes the amount)
> and it has **no digitally-signed QR content**. The signed, fully-resolved payload is what the payer
> fetches in step (b).

## 5. Files
Committed: the two scripts, `requests/qr-*-createqr.json`, this README. **Generated** and git-ignored
(regenerate any time): `qr-<name>.emv`, `<name>-payload.json`, `<name>-call.jws`, `<name>-response.jws`.

## 6. Ideas
- **Edit a bill** in `requests/` — amount, tip, networks, adjustments. (They omit `locationId`, so the
  server mints a fresh QR each run — no collisions.) Remember: USD-pegged currencies (USD/USDC) may
  share a QR; a non-pegged currency (BTC) must be the only one.
- **Decode any EMV**: `POST /api/v1/qrcode-emv-decoder` with `{"qrCode":"…"}`.
- **Scan from a phone on 5G** — point `x9.public-endpoints.host` at a tunnel; see [`../ENDPOINTS.md`](../ENDPOINTS.md).
- **Point the scripts elsewhere:** `X9_BASE_URL=http://host:port python3 simulate_payer.py`.

Break things, read the errors (field-level RFC-7807 problems), and enjoy. 🛝
