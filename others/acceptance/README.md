# Black-box acceptance tests

`acceptance.py` puts a whole payment through a **running** X9.150 deployment and asserts the
outcomes — not just the status codes. Nothing here imports the application, so it tests what you
would actually ship rather than what the unit tests compile against.

```bash
make acceptance                                   # http://localhost:8080
make acceptance URL=http://localhost:8090         # a container
./others/acceptance/acceptance.py https://x9.example.com
```

Exit code 0 means every check passed. Python 3 standard library only, so it runs on a bare test VM
with nothing installed.

## What it checks

| Section | The question it answers |
|---|---|
| 1. QR Code generation | Is the EMV string *correct*? Format indicator, the `org.x9` GUI, the embedded loc URL, tag 26 within the 99-character limit, `qrCodeB64` round-trip — and the **CRC**, which is what decides whether a scanner accepts the code at all |
| 2. Payload retrieval | Can a payer fetch it, is the response a verifiable JWS, and does it state the amount, currency and recipient that were created? |
| 3. Payer notifies payee | Pre-payment reserves the QR Code; a **second** pre-payment is refused; post-payment carries the hash; and the QR Code is **still not PAID** |
| 4. Event stream | Both events published in order, distinct `eventId`s, the hash present on `payment.sent`, the cursor advances and does not repeat |
| 5. Settlement | Only `status-update` clears it — and re-initiating a PAID QR Code is a 409 |
| 6. Refusals | An unsupported currency and an uninterpreted network are refused **by name**; an unknown id is 404 |
| 7. Public metadata | The JWK Set is served, so the other side can verify signatures |

## How it relates to the other suites

| | Scope |
|---|---|
| `mvn test` | 178 JUnit/integration tests, in-process, against MockMvc |
| **`make acceptance`** | one **deployed** instance over real HTTP — what this file is |
| `others/demo/two-instance-payment-cycle.sh` | **two** deployments with separate identities and databases, over HTTPS |

They answer different questions. The JUnit tests prove the logic; this proves the deployment; the
demo proves interoperability between two identities. A single instance cannot tell you whether it is
interoperable, only whether it is self-consistent.

## A note on signing

The suite signs with the deployment's **own** key via `/api/v1/signature/generate`, which is what
lets one deployment stand in for both sides. A real payer signs with its own X9 certificate — that
is what the two-instance demo covers. So a pass here does not prove cross-identity trust; it proves
this deployment does what it says.

## Use it before publishing an image

```bash
make image
docker run -d --name x9-check --network x9-qrcode-network -p 8090:8080 \
  -e SPRING_DATA_MONGODB_URI="mongodb://mongo:27017/x9check?replicaSet=x9-qrcode" \
  -e X9_PUBLICENDPOINTS_HOST=localhost:8090 x9-qrcode:latest
make acceptance URL=http://localhost:8090
docker rm -f x9-check
```

---

<sub>Copyright © 2026 Matera Systems, Inc. Licensed under the Matera Source License v1.0 (source-available; not open source) — see LICENSE.md at the repository root.</sub>
