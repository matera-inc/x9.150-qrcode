# 🛝 Playground

Welcome — your sandbox for the **ANSI X9.150** payment-QR flow. Build the app, run it, and drive it
end to end with two little scripts:

- **`simulate_payee.py`** — the *merchant*: pick a bill, create the QR, get its EMV string.
- **`simulate_payer.py`** — the *payer*: pick a QR, sign a request, fetch the signed payload.

No real bank, no real certificates — everything runs locally against the bundled **non-production**
demo keystore. Have fun. 🎉

Run everything below **from the repository root** unless noted.

---

## 0. Prerequisites

- **JDK 25** (`java -version`). Maven is bundled — use `./mvnw`.
- **Docker** (Docker Desktop or **Colima**).
- **Python 3** (standard library only — nothing to `pip install`).

## 1. Build the container image

Cloud Native Buildpacks (no Dockerfile). Pick your architecture:

```bash
make image           # native arch  → x9-qrcode:latest        (arm64 on Apple Silicon)
make image-amd64     # amd64 / intel → x9-qrcode:latest-amd64  (uses Rosetta on Apple Silicon)
```

> Building amd64 on Apple Silicon needs **Rosetta** in your Docker/Colima VM. Check with
> `docker run --rm --platform=linux/amd64 alpine uname -m` → should print `x86_64`.

## 2. Run it

```bash
make up          # app (:8080) + MongoDB + mongo-express (:9091)
make smoke       # quick health check
make down        # stop everything when you're done
```

- App: <http://localhost:8080> — open, unauthenticated API
- **mongo-express** (browse the DB): <http://localhost:9091> — `admin` / `admin`

## 3. Play — merchant → payer

Everything below runs from **inside `playground/`**.

### a) 🧾 Merchant creates a QR — `simulate_payee.py`

```bash
cd playground
python3 simulate_payee.py            # shows a menu of the bills in requests/
# or non-interactively:
python3 simulate_payee.py burger     # by name (or by number)
```

It POSTs the chosen `requests/x9-create-*.json` to the app and saves the EMV QR string as
`x9-created-<scenario>.emv`. The EMV only carries a reference URL + merchant basics — **not** the
amount or the payment networks.

### b) 📲 Payer scans it and fetches the payload — `simulate_payer.py`

```bash
python3 simulate_payer.py            # menu of the x9-created-*.emv you've made
python3 simulate_payer.py burger     # or pick directly
```

It base64-wraps the scanned EMV, signs it as a **JWS** (via the app's own
`/api/v1/signature/generate`, which uses the demo cert the truststore trusts — a real payer signs
with its **own X9-issued certificate**), makes the single call `POST /pub/api/v1/loc/{id}`, verifies
the response **echoes the payer's `correlationId`**, and saves the decoded payload as
`x9-payer-<scenario>.json`. That payload holds the **full** bill — amount + every payment method —
none of which were in the scannable QR.

Expected tail:

```
[verify]  correlationId echoed back? True   (statusCode=200)
[payer]   saved x9-payer-burger.json
```

## 4. The scenarios (`requests/`)

Hand-crafting valid X9.150 bodies is fiddly, so here's a ready-made library — edit them or add your
own `x9-create-<name>.json`:

| File | Scenario | Shows off |
|------|----------|-----------|
| `x9-create-burger.json` | Burger joint, $24.50 | **tip** presets/range |
| `x9-create-waterbill.json` | Water utility, $87.30 | **discount + late-fee adjustments**, 3 bank rails (FedNow/RTP/ACH) |
| `x9-create-lab.json` | Helix Diagnostics Lab, $312.00 | invoice + USDC on Ethereum |
| `x9-create-parking.json` | Parking, $6.00 | tiny amount, USDC on Polygon |
| `x9-create-donation.json` | Charity | **editable amount** + **Bitcoin-only** (non-pegged currencies can't mix in v1.0) |

Bonus bodies for the other endpoints (use with `curl`): `x9-patch-update.json` (PATCH),
`x9-status-change.json` (PUT status-update).

## 5. Files

Committed: the two scripts, the `requests/` library, this README. **Generated** by the scripts and
git-ignored (regenerate any time): `x9-created-*.emv`, `x9-payer-*.json`, `x9-payer-*.jws`.

## 6. Ideas to mess around with

- **Edit a bill** in `requests/` — change the amount, add a `tip`, swap networks, tweak the
  adjustments. (These omit `locationId`, so the server mints a fresh QR each run — no conflicts.)
- **Add your own** `requests/x9-create-<name>.json`; it shows up in the menu automatically.
- **Decode any EMV**: `POST /api/v1/qrcode-emv-decoder` with `{"qrCode":"…"}`.
- **Scan from a phone on 5G** — point `x9.public-endpoints.host` at a tunnel; see
  [`../ENDPOINTS.md`](../ENDPOINTS.md).
- **Point the scripts elsewhere:** `X9_BASE_URL=http://host:port python3 simulate_payer.py`.
- Browse the `qrcodes` collection in mongo-express (<http://localhost:9091>).

Break things, read the errors (they're field-level RFC-7807 problems), and enjoy. 🛝
