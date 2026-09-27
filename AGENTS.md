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

### Quoting the standard: short attributed fragments are allowed

Do **not** reproduce the ANSI PDF/MD wholesale, and never treat a local copy as a source to copy from
at length. But a **short quoted fragment — one or two lines — with attribution is allowed and
encouraged**, because it grounds a claim in the normative text instead of a paraphrase a reader has to
take on faith, and it points readers at a standard worth buying.

The boundary:

- **Allowed:** a normative sentence or two, quoted verbatim, attributed to its section — e.g.
  > "**Shall** be a 64 bit integer with minimum value = 0." — ANSI X9.150-2026 §2.1
- **Not allowed:** whole sections, field tables, figures, or a series of fragments that together
  substitute for reading the standard. If a reader could skip buying it, we have gone too far.
- **Always attribute** the section, and link [`official-spec/README.md`](official-spec/README.md),
  which points at the [ANSI Web Store](https://webstore.ansi.org/standards/ascx9/ansix91502026).
- **Never commit the PDF/MD itself.** It stays git-ignored.

(Describing how *this software* behaves needs no quotation at all — that is our own implementation,
documented below.)

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
- **Networks: X9.150 specifies the style and the root; the inner JSON belongs to the network's
  owner.** A network is interpreted only once its authority publishes how it embeds
  ([ADR-0010](docs/adr/0010-networks-are-interpreted-only-once-their-authority-publishes.md)).
  **`NetworkEnum` holds four rails: `fednow`, `rtp`, `ach` (the standard's) and `solana` (the Solana
  Foundation's — `recipient` + optional `memo`, see official-spec/SOLANA-FIELDS.md).** Everything
  else is **refused at creation, by name**
  ([ADR-0012](docs/adr/0012-refuse-what-this-deployment-cannot-honour.md)).
- **Every rail dispatch must be an exhaustive switch EXPRESSION.** Adding Solana reopened a
  fall-through in `QRCodeEntityValidator` because that one was a switch *statement*: Solana
  notifications were accepted with none of their validation running. `isBankRail()` and
  `isInterpretedRail()` are also deliberately different — conflating them skipped the destination
  check for Solana, which unlike a bank rail does name one.
- **Network keys are lower-case, always** (`fednow`, `rtp`, `ach` — §14.5's normative paths). The
  OpenAPI contract declares them that way, so a conformant caller binds to the generated properties
  and no casing logic is needed; anything else falls into `additionalProperties` and is refused by
  the unsupported-network rule. §2.4's "all-uppercase" contradiction governs `$.payment.network`,
  the notification VALUE — a different field, and one that gets the opposite rule.
- **A third-party payer's notification is matched case-insensitively**, on both the rail and the
  currency, and its `$.payment.network` is echoed back verbatim. Their implementation is not ours to
  correct and refusing a payment over the case of a string would be indefensible; a notification
  records what somebody claimed, so normalising it would rewrite their words. Leniency is about
  spelling only — a wrong amount, an unoffered currency or an uninterpreted rail still refuses, and
  still leaves the QR Code untouched. See
  [official-spec/INTERPRETATION.md](official-spec/INTERPRETATION.md) I-1.
- **Currencies are gated by what the rails settle.** The payload format is currency-agnostic and
  carries any code verbatim, but this deployment accepts only what `supported-currencies.json` lists
  (`USD` by default, in exactly that spelling); an empty list disables the check. Separate from the peg-mixing rule, which asks
  whether currencies may appear *together*.

- **JWS works with both RSA and EC, in both directions.** Verification reads `alg` from the header
  and picks the verifier (`RS*`/`PS*` → RSA, `ES*` → ECDSA). Signing follows the key this deployment
  was issued, so an EC identity works — `x9.certificate.jwk-algorithm` must then be an `ES*` value,
  and a mismatch fails at startup naming both halves. X9.150 names no algorithm (it defers to the
  X9-approved suite SD-34) and its own examples use `ES256`, so assuming RSA anywhere is an
  interoperability bug rather than a policy.

- **Transient transaction conflicts are retried** (`TransientTransactionRetry`, ADR-0013) — but only
  when MongoDB labelled the failure `TransientTransactionError`, never by exception type: Spring
  maps a write conflict and a duplicate key to the same `DataIntegrityViolationException`, and
  retrying a duplicate key can only fail slowly. The advice orders OUTSIDE `@Transactional` so each
  attempt gets a fresh transaction; that ordering is asserted by a test, not assumed.

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
