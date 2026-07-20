# AGENTS.md

Orientation for AI coding agents (and humans) working in this repository. Read this first; it lets
you help immediately without reading every doc.

## What this is

An **ANSI X9.150-2026 Payment QR Code** backend. It plays the **Payee-PSP** role: it creates QR
codes, serves the signed Payment Payload a payer's app fetches, and receives payment notifications.

- **Stack:** Java 25, Spring Boot 3.5.x, MongoDB (replica set), Maven (wrapper `./mvnw`).
- **Architecture:** Clean / Hexagonal, three modules — `x9-qrcode-domain` (pure Java, no Spring),
  `x9-qrcode-application` (use cases, depends only on domain), `x9-qrcode-infrastructure` (Spring,
  web, persistence, config). Dependency direction is inward-only.
- **API:** open / unauthenticated by design — secure it at the edge. Management endpoints under
  `/api/v1/...`; payer-facing endpoints under `/pub/...`.

## Spec grounding rule (important — copyright)

The ANSI X9.150 standard text is **copyrighted and is not in this repository** (only
`official-spec/README.md`, a pointer, is tracked). When answering questions about "the spec" or the
data model, ground your answers **only** in tracked sources:

- **The OpenAPI contract:** `x9-qrcode-infrastructure/src/main/resources/apis/openapi.yaml` — the
  authoritative, richly-annotated description of every field and rule as implemented.
- **The domain code:** `x9-qrcode-domain/src/main/java/com/matera/x9qrcode/domain/` — value objects
  (self-validating), `entity/QRCodeEntity.java` + `entity/validator/QRCodeEntityValidator.java`,
  enums under `vo/enumerated/`, and policies under `service/`.
- **The tracked docs** listed below.

Do **not** source, quote, or reproduce the ANSI PDF/MD even if a copy exists on the local machine.
(Describing how *this software* behaves is fine — that is our own implementation, documented below.)

## Data model conventions (as implemented)

These describe how our software behaves; they're enforced in `openapi.yaml` + the domain code.

- **QR status** (lifecycle, server-assigned; UPPERCASE): `ACTIVE` → `PAYMENT_INITIATED` → `PAID`
  or `CANCELLED`. `PAYMENT_INITIATED` may revert to `ACTIVE` (reactivate). Enum:
  `domain/vo/enumerated/QRCodeStatusEnum.java`; transitions in `STATE-MACHINE.md`.
- **`paymentTiming`** (lowercase): `immediate` = due when the QR is scanned; `deferred` = due at a
  future date — and `deferred` **requires** `bill.invoice` with a `dueDate`. Wire values are
  lowercase; input is accepted case-insensitively but always emitted lowercase.
- **Money is int64 minor units** — integer cents (or the currency's smallest unit): `600` = $6.00,
  `19825` = $198.25. Never floating-point; the module never converts minor↔major (the paying PSP
  resolves a currency's decimals). Non-money counters (tip %, revision) are plain integers.
- **Currency** is an open string (ISO 4217 code or a digital-asset ticker like `USDC`/`BTC`), not an
  enum. Currencies on one QR must share a pegged group or be a single currency
  (`pegged-currencies.json`; `domain/service/PeggedCurrencyMixPolicy.java`).
- **`protectionType`** (bank rails) is lowercase and always `tokenized`; **tips** are integer
  percentages `0–999`; **timestamps** are UTC, `Z`-terminated.

## Build / test / run

```bash
./mvnw clean install        # build + test all modules
./mvnw test                 # full test suite
make up                     # app on :8080 + MongoDB (1-node replica set) + mongo-express :9091
make smoke                  # health check (curl /actuator/health + /pub/.well-known/jwks)
make down                   # stop everything
```

Default port `8080`; health at `http://localhost:8080/actuator/health`. MongoDB **must** be a
replica set (transactions) — Compose starts one automatically. See `RUNNING.md` for host-JVM runs
(`make run-local`) and details.

## Generate a QR code (the first thing to try)

With the app running (`make up`), the quickest path is the playground — plain Python 3, stdlib only,
nothing to `pip install`:

```bash
cd playground
python3 simulate_payee.py parking   # POSTs requests/qr-parking-createqr.json -> writes qr-parking.emv, prints EMV + id/loc
python3 simulate_payer.py parking   # fetches & prints the signed Payment Payload for that QR
```

Run `python3 simulate_payee.py` with no argument to pick from the sample scenarios (burger,
waterbill, lab, parking, donation). Or create one directly against the API:

```bash
curl -sS -X POST http://localhost:8080/api/v1/payment-request \
  -H 'Content-Type: application/json' \
  --data-binary @playground/requests/qr-parking-createqr.json
```

The response carries the QR **content**, not an image: `qrCode` (the EMV string) and `qrCodeB64`
(that same EMV content, Base64-encoded — reuse it as `qrCodeContent` in the payload-retrieval flow).

**This service never renders the QR image.** It emits only the EMV content string; any off-the-shelf
QR library turns that into an image. Render in the **last mile**: for payment terminals, transmit the
small content string and render the image on the terminal rather than shipping a bitmap over the wire
— it's faster and lighter. Generating the image as late as possible is the general recommendation.

### Render a `.emv` string as a scannable PNG (dev convenience)

The playground writes the EMV content to `playground/qr-<name>.emv`. To eyeball / scan one, turn it
into a PNG with **`qrencode`** (`brew install qrencode`, or `apt-get install qrencode`):

```bash
qrencode -8 -m 4 -s 8 -l M -o playground/qr-cloudprovider.png < playground/qr-cloudprovider.emv
#        │      │    │    └─ error-correction level M   -o <out.png>   < <in.emv>
#        │      │    └────── module size (px per cell)
#        │      └─────────── quiet-zone margin (4 modules — required by the QR spec)
#        └────────────────── 8-bit/byte mode (the EMV payload is mixed-case, so byte mode is correct)
```

The `.emv` payload is **plain text** — it starts with `0002…`, it is **not** a URL. A phone camera
may show it with an `https://` prefix and offer to "open" it: that is the camera *linkifying* the
`…example.com/…` substring inside the EMV for its own preview UI — the `https://` is **not** in the
encoded bytes, and no generator/decoder switch adds or removes it. A real X9.150 payer app reads the
raw EMV content, not a URL. Confirm what is actually encoded (note: `zbar` only *decodes*, it cannot
generate images):

```bash
zbarimg --raw -q playground/qr-cloudprovider.png   # prints the exact EMV string, no https:// prefix
```

Full walkthrough: the `x9-qrcode` skill and `playground/README.md`.

## Architecture rules (mandatory)

- **No Spring in `domain` or `application`** — no `@Component`/`@Service`/`@Autowired` there. All
  wiring lives in `infrastructure/configuration/` via `@Configuration` + `@Bean`.
- Entities use factory methods (`create()` / `restore()`), never public constructors.
- Value Objects validate in their constructor and throw `ValueObjectRuleException`.
- Use cases extend `UseCase<INPUT, OUTPUT>` with a single `execute(...)`.
- Ports (interfaces) in `application/`; adapters (controllers/persistence/services) in
  `infrastructure/`.

## Contribution workflow

`main` is protected. Every change goes through a pull request that must pass **CI (`build`)** and the
**DCO** check — sign commits with `git commit -s`. Squash-merge. See `CONTRIBUTING.md`.

## Guided onboarding skill

A Claude Code skill named **`x9-qrcode`** (at `.claude/skills/x9-qrcode/`) walks a newcomer through
running the app, generating QR codes, answering API/spec questions (from the sources above),
configuring MongoDB, and deploying via Docker/Kubernetes. Use it when helping someone get started.

## Where to look

| Doc | Purpose |
|---|---|
| `README.md` | Project overview and structure |
| `RUNNING.md` | First-run guide (build, run, smoke-test) |
| `ENDPOINTS.md` | Endpoint reference + local scan testing |
| `STATE-MACHINE.md` | QR lifecycle (ACTIVE → PAYMENT_INITIATED → PAID / CANCELLED) |
| `HIGH-AVAILABILITY.md` | MongoDB replica set & HA guidance |
| `others/helm/x9-qrcode/README.md` | Kubernetes / Helm deploy |
| `playground/README.md` | Payee/payer simulation scripts (generate & fetch QRs) |
| `CONTRIBUTING.md` / `SECURITY.md` | Contribution flow / vulnerability reporting |
| `TODO.md` | Planned, not-yet-implemented work |

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
