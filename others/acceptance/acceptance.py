#!/usr/bin/env python3
"""
Black-box acceptance tests for a RUNNING X9.150 deployment.

Every check goes over HTTP against a deployment you point it at — a container, a pod, a VM — and
asserts the OUTCOME, not the status code alone. Nothing here imports the application, so it tests
the thing you would actually ship rather than the thing the unit tests compile against.

    ./others/acceptance/acceptance.py                         # http://localhost:8080
    ./others/acceptance/acceptance.py https://x9.example.com

Exit code 0 = every check passed. Non-zero = the number that failed.

NON-PRODUCTION: it signs with the deployment's OWN key via /api/v1/signature/generate, which is
what lets one deployment stand in for both sides of a payment. A real payer signs with its own X9
certificate; interoperability between two identities is what others/demo/two-instance-payment-cycle.sh
covers instead.

Python 3 standard library only — no pip install, so it runs on a bare test VM.
"""
import base64
import json
import re
import sys
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")

RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
PAYER_WALLET = "7cVfgArCheMR6Cs4t6vz5rfnqd56vZq4ndaBrY5xkxXy"
TX_HASH = "5VfydnLu4XwV2FquzyFbENPBqbCyJLxq5wRhtZ8rLraBdHfDDmWXnc8nBtCqPU4f"
AMOUNT = 22500
CURRENCY = "USDC"

passed, failed = 0, 0
section_name = ""


def section(name):
    global section_name
    section_name = name
    print(f"\n\033[1m{name}\033[0m")


def check(description, condition, detail=""):
    global passed, failed
    if condition:
        passed += 1
        print(f"  \033[32mPASS\033[0m  {description}")
    else:
        failed += 1
        print(f"  \033[31mFAIL\033[0m  {description}")
        if detail:
            for line in str(detail).splitlines()[:6]:
                print(f"        {line}")


def call(method, path, body=None, headers=None, raw_body=None):
    """Returns (status, parsed-or-text). Never raises on an HTTP error status."""
    url = BASE + path
    data = None
    hdrs = dict(headers or {})

    if raw_body is not None:
        data = raw_body.encode()
    elif body is not None:
        data = json.dumps(body).encode()
        hdrs.setdefault("Content-Type", "application/json")

    request = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(request, timeout=40) as response:
            text = response.read().decode()
            status = response.status
    except urllib.error.HTTPError as e:
        text, status = e.read().decode(), e.code
    except Exception as e:                                    # connection refused, DNS, timeout
        return 0, str(e)

    try:
        return status, json.loads(text)
    except ValueError:
        return status, text


def sign(payload):
    """Sign JSON with the deployment's own key. Both headers are required."""
    status, body = call("POST", "/api/v1/signature/generate", body=payload, headers={
        "Correlation-Id": str(uuid.uuid4()), "TTL-Seconds": "300"})
    return body if status == 200 else None


def jws_claims(token):
    """The claims of a compact JWS, without verifying it — the signature is checked by the payee."""
    try:
        segment = token.split(".")[1]
        segment += "=" * (-len(segment) % 4)
        return json.loads(base64.urlsafe_b64decode(segment))
    except Exception:
        return None


def emv_crc(emv):
    """CRC16/CCITT-FALSE over everything up to and including the '6304' tag, per EMVCo."""
    data = emv[:-4].encode()
    crc = 0xFFFF
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return f"{crc:04X}"


def qr_request(currency=CURRENCY, networks=None, amount=AMOUNT):
    return {
        "validUntil": "2030-12-31T23:59:59Z",
        "creditor": {"name": "Acceptance Co", "phone": "+15552223333",
                     "email": "billing@example.com",
                     "address": {"line1": "1 A St", "city": "Los Angeles", "state": "CA",
                                 "postalCode": "90012", "country": "US"},
                     "MCC": "4900"},
        "bill": {"description": "acceptance",
                 "invoice": {"number": "INV-ACC-1", "date": "2030-01-10",
                             "dueDate": "2030-12-31T23:59:59Z"},
                 "amountDue": {"amount": amount, "currency": currency}},
        "paymentNotification": {"kind": "DEFAULT"},
        "paymentMethods": [{"currency": currency, "validUntil": "2030-12-31T23:59:59Z",
                            "amount": amount,
                            "networks": networks or {"solana": {"recipient": RECIPIENT}}}],
    }


def notification_with_tip(qr_id, amount, tip_amount, action="PAYMENT_INITIATED"):
    """`amount` is the TOTAL transferred, tip included (ANSI X9.150-2026 13.6.2)."""
    payment = {"qrcodeId": qr_id, "amount": amount, "tipAmount": tip_amount,
               "currency": CURRENCY, "network": "Solana"}
    return {"payment": payment,
            "payer": {"info": "acceptance@example.com"},
            "blockchain": {"action": action, "from": PAYER_WALLET, "to": RECIPIENT}}


def notification(qr_id, action, transaction_id=None):
    payment = {"qrcodeId": qr_id, "amount": AMOUNT, "currency": CURRENCY, "network": "Solana"}
    if transaction_id:
        payment["transactionId"] = transaction_id
    return {"payment": payment,
            "payer": {"info": "acceptance@example.com"},
            "blockchain": {"action": action, "from": PAYER_WALLET, "to": RECIPIENT}}


# ===================================================================== 0. reachable

print(f"X9.150 acceptance — {BASE}")

section("0. The deployment is up")
status, health = call("GET", "/actuator/health")

# Do NOT gate on the aggregate alone. On Kubernetes the Kubernetes health indicator can drag it to
# DOWN while the application is perfectly able to serve — see README.md. What decides whether there
# is any point continuing is the readiness group, so fall back to it and say why.
if status != 200:
    ready_status, readiness = call("GET", "/actuator/health/readiness")
    if ready_status == 200:
        check("the deployment is ready (aggregate /actuator/health is DOWN, readiness is UP)", True)
        print("        the aggregate is DOWN but readiness is UP, so the application can serve.")
        print("        A common cause on Kubernetes is the Kubernetes health indicator — see")
        print("        MANAGEMENT_HEALTH_KUBERNETES_ENABLED in others/acceptance/README.md.")
    else:
        check("GET /actuator/health is 200", False, health)
        print("\nNothing else can be tested. Is the deployment running and reachable?")
        sys.exit(1)
else:
    check("GET /actuator/health is 200", True)

# ========================================================== 1. the QR Code is well formed

section("1. A generated QR Code is correct")
status, created = call("POST", "/api/v1/payment-request", body=qr_request())
check("POST /api/v1/payment-request is 201", status == 201, created)
if status != 201:
    sys.exit(1)

qr_id = created.get("id")
emv = created.get("qrCode", "")

check("the id is 32 uppercase hex characters",
      bool(re.fullmatch(r"[0-9A-F]{32}", qr_id or "")), qr_id)
check("the EMV payload starts with the format indicator 000201",
      emv.startswith("000201"), emv[:20])
check("the EMV carries the X9 GUI 'org.x9' in tag 26",
      "0006org.x9" in emv, emv[:80])
check("the EMV embeds the loc URL for this QR Code",
      f"/pub/api/v1/loc/{qr_id}" in emv, emv[:120])
check("the EMV CRC is valid (a wrong one makes every scanner reject the code)",
      emv[-4:] == emv_crc(emv), f"trailing={emv[-4:]} computed={emv_crc(emv)}")
check("tag 26 fits the 99-character EMV limit",
      len(emv.split("26")[1][:2]) == 2 and int(emv[emv.index("0006org.x9") - 2:
                                                   emv.index("0006org.x9")]) <= 99,
      emv[:120])
check("qrCodeB64 decodes to exactly the EMV string",
      base64.b64decode(created.get("qrCodeB64", "")).decode() == emv)
check("location.endpoint is HTTPS and names this QR Code",
      (created.get("location", {}).get("endpoint", "").startswith("https://")
       and created["location"]["endpoint"].endswith(qr_id)),
      created.get("location"))

status, fetched = call("GET", f"/api/v1/payment-request/{qr_id}")
check("the QR Code reads back as ACTIVE", status == 200 and fetched.get("status") == "ACTIVE",
      fetched)
check("the Solana recipient survives the round trip",
      fetched.get("paymentMethods", [{}])[0].get("networks", {})
             .get("solana", {}).get("recipient") == RECIPIENT,
      fetched.get("paymentMethods"))

# ============================================== 2. the payer fetches the signed payload

section("2. A payer can fetch the signed payload")
payload_request = sign({"qrCodeContent": base64.b64encode(emv.encode()).decode()})
check("the deployment signs a payload request", payload_request is not None)

status, payload = call("POST", f"/pub/api/v1/loc/{qr_id}",
                       raw_body=payload_request or "", headers={"Content-Type": "application/jose"})
check("POST /pub/api/v1/loc/{id} is 200", status == 200, payload)

# The response is a JWS, not JSON: the payee signs the payload so the payer can prove where the
# bank details came from. Read the claims out of it.
claims = jws_claims(payload) if isinstance(payload, str) else None
check("the response is a compact JWS the payer can verify", claims is not None,
      str(payload)[:120])
claims = claims or {}
check("the payload states the amount and currency that were created",
      claims.get("bill", {}).get("amountDue", {}).get("amount") == AMOUNT
      and claims.get("bill", {}).get("amountDue", {}).get("currency") == CURRENCY,
      json.dumps(claims.get("bill", {}))[:200])
check("the payload publishes where to send the notification",
      "payment-notification" in str(claims.get("paymentNotification")),
      claims.get("paymentNotification"))
check("the payload carries the Solana recipient a payer would pay",
      claims.get("paymentMethods", [{}])[0].get("networks", {})
            .get("solana", {}).get("recipient") == RECIPIENT,
      json.dumps(claims.get("paymentMethods"))[:200])

status, refused = call("POST", f"/pub/api/v1/loc/{qr_id}", raw_body="not-a-jws",
                       headers={"Content-Type": "application/jose"})
check("an unsigned payload request is refused with 400", status == 400, f"got {status}")

# ================================================ 3. the payer notifies the payee

section("3. A payer notifies the payee (two-phase)")
pre = sign(notification(qr_id, "PAYMENT_INITIATED"))
status, body = call("POST", "/pub/api/v1/payment-notification", raw_body=pre or "",
                    headers={"Content-Type": "application/jose"})
check("a pre-payment notification is accepted", status == 200, body)

status, after_pre = call("GET", f"/api/v1/payment-request/{qr_id}")
check("the QR Code is reserved as PAYMENT_INITIATED",
      after_pre.get("status") == "PAYMENT_INITIATED", after_pre.get("status"))

status, second = call("POST", "/pub/api/v1/payment-notification", raw_body=pre or "",
                      headers={"Content-Type": "application/jose"})
check("a SECOND pre-payment is refused — the reservation holds",
      status >= 400, f"got {status}: {second}")

post = sign(notification(qr_id, "SENT", TX_HASH))
status, body = call("POST", "/pub/api/v1/payment-notification", raw_body=post or "",
                    headers={"Content-Type": "application/jose"})
check("a post-payment notification carrying the hash is accepted", status == 200, body)

status, after_post = call("GET", f"/api/v1/payment-request/{qr_id}")
check("the QR Code is STILL not PAID — X9.150 never sees the funds",
      after_post.get("status") == "PAYMENT_INITIATED", after_post.get("status"))

# ============================================================ 4. the event stream

section("4. The event stream reports it")
seen, cursor, types = {}, "", []
for _ in range(20):
    status, page = call("GET", f"/pub/api/v1/events?after={cursor}&limit=200&wait=2")
    if status != 200 or not isinstance(page, dict):
        break
    for event in page.get("events", []):
        if event.get("qrCodeId") == qr_id and event["eventId"] not in seen:
            seen[event["eventId"]] = event
            types.append(event["type"])
    cursor = page.get("nextCursor") or cursor
    if {"payment.initiated", "payment.sent"} <= set(types):
        break

check("GET /pub/api/v1/events is 200", status == 200)
check("payment.initiated and payment.sent were both published",
      types[:2] == ["payment.initiated", "payment.sent"], types)
check("every event carries a distinct eventId (the dedup key)",
      len(seen) == len(types), f"{len(seen)} ids for {len(types)} events")
check("payment.sent carries the transaction hash the payer reported",
      any(e["type"] == "payment.sent" and e.get("transactionId") == TX_HASH
          for e in seen.values()),
      [e.get("transactionId") for e in seen.values()])
check("the cursor advanced", bool(cursor), cursor)

status, again = call("GET", f"/pub/api/v1/events?after={cursor}&limit=200&wait=0")
check("reading from the cursor does not repeat our events",
      status == 200 and not [e for e in again.get("events", []) if e.get("qrCodeId") == qr_id],
      [e.get("type") for e in again.get("events", []) if e.get("qrCodeId") == qr_id])

# ======================================== 5. only the payee's own system clears it

section("5. Settlement is the consuming system's call")
status, cleared = call("PUT", f"/api/v1/payment-request/{qr_id}/status-update",
                       body={"status": "PAID", "network": "Solana", "endToEndId": TX_HASH})
check("status-update to PAID is accepted", status == 200, cleared)
status, final = call("GET", f"/api/v1/payment-request/{qr_id}")
check("the QR Code is now PAID", final.get("status") == "PAID", final.get("status"))

status, conflict = call("PUT", f"/api/v1/payment-request/{qr_id}/status-update",
                        body={"status": "PAYMENT_INITIATED", "network": "Solana"})
check("re-initiating a PAID QR Code is refused with 409 — no second payment",
      status == 409, f"got {status}: {conflict}")

# ==================================================== 6. it refuses what it cannot honour

section("6. It refuses what it cannot honour, by name")
status, body = call("POST", "/api/v1/payment-request", body=qr_request(currency="EUR"))
check("an unsupported currency is refused with 400 naming it",
      status == 400 and "EUR" in json.dumps(body), f"{status}: {json.dumps(body)[:200]}")

status, body = call("POST", "/api/v1/payment-request",
                    networks := qr_request(networks={"tron": {"walletAddress": "TXYZ"}}))
check("an uninterpreted network is refused with 400 naming it",
      status == 400 and "tron" in json.dumps(body).lower(),
      f"{status}: {json.dumps(body)[:200]}")

status, body = call("GET", "/api/v1/payment-request/" + "0" * 32)
check("an unknown QR Code is 404", status == 404, f"got {status}")

# ================================================================== 7. public metadata

section("7. Public signing metadata is served")
status, jwks = call("GET", "/pub/.well-known/jwks")
check("GET /pub/.well-known/jwks is 200 and has keys",
      status == 200 and isinstance(jwks, dict) and bool(jwks.get("keys")), jwks)

# ============================== 8. the behaviours most recently changed

section("8. Conditional requests and what a revision counts")

# These exist because the suite above would pass with all of them missing. It exercises the payment
# flow, so an image that had silently lost ETag support, If-Match enforcement or the revision rule
# would still score full marks — which is exactly what happened once, and cost an adopter an
# afternoon proving a published image was wrong when their node was serving a stale one.

status, created = call("POST", "/api/v1/payment-request", body={
    **qr_request(),
    "additionalInformation": [
        {"key": "Partial payment", "value": "first"},
        {"key": "Partial payment", "value": "second"},
    ],
})
probe_id = created.get("id") if status == 201 else None
check("a QR Code with repeated additionalInformation labels is created", status == 201, created)

if probe_id:
    # ETag: the token a conditional request echoes. Without it a caller silently falls back to
    # something weaker, which is the failure that hides.
    url = BASE + f"/api/v1/payment-request/{probe_id}"
    request = urllib.request.Request(url, method="GET")
    etag = None
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            etag = response.headers.get("ETag")
    except Exception:
        pass

    check("GET serves an ETag for conditional requests", bool(etag), f"headers carried none")

    status, _ = call("PATCH", f"/api/v1/payment-request/{probe_id}",
                     body={"paymentMethods": [{"currency": CURRENCY,
                                               "validUntil": "2030-12-31T23:59:59Z",
                                               "amount": 11111,
                                               "networks": {"solana": {"recipient": RECIPIENT}}}]},
                     headers={"If-Match": '"never-existed"'})
    check("If-Match is enforced on PATCH", status == 412, f"got {status}, so a stale edit would apply")

    status, _ = call("PUT", f"/api/v1/payment-request/{probe_id}/status-update",
                     body={"status": "CANCELLED"}, headers={"If-Match": '"never-existed"'})
    check("If-Match is enforced on status-update", status == 412,
          f"got {status}, so a stale cancel would apply")

    status, back = call("GET", f"/api/v1/payment-request/{probe_id}")
    entries = back.get("additionalInformation") or []
    check("a repeated additionalInformation label is not collapsed", len(entries) == 2,
          f"{len(entries)} of 2 survived — these lines explain the amount to the payer")

    before = back.get("revision")
    call("PUT", f"/api/v1/payment-request/{probe_id}/status-update",
         body={"status": "PAYMENT_INITIATED"})
    status, after = call("GET", f"/api/v1/payment-request/{probe_id}")
    check("a status change does not create a new revision", after.get("revision") == before,
          f"{before} -> {after.get('revision')}: a status is not a version of the request")

section("9. A tip is money on top of the bill, never a slice of it")

# ANSI X9.150-2026 13.6.2: "The computed total (amount + tip) SHALL apply only to the payment
# instruction sent to the payment network and related payment notification". So `amount` is the
# whole transfer and the merchant's share is `amount - tipAmount`.
#
# The suite above never sent a tip in any form, so it scored full marks while a tip could be paid
# OUT OF the merchant's money and the QR Code still marked paid in full.

def tipping_qr(tip):
    body = qr_request()
    body["bill"]["tip"] = tip
    status, created = call("POST", "/api/v1/payment-request", body)
    return (created.get("id") if status == 201 else None), status, created


# --- the three tip forms that used to be impossible to create at all

for label, tip in [("tips refused", {"allowed": False}),
                   ("tips allowed within a range", {"allowed": True, "range": {"min": 0, "max": 25}}),
                   ("tips allowed with presets", {"allowed": True, "presets": [10, 15, 20]})]:
    _, status, created = tipping_qr(tip)
    check(f"a bill can be created with {label}", status == 201,
          f"got {status}: {created.get('violations', created)}")

# A tip offer has to tell the payer something. Stricter than the standard, which makes both range
# and presets MAY, and deliberate: "tips welcome" with no range and no buttons is not an offer a
# payer-facing app can render.
_, status, created = tipping_qr({"allowed": True})
check("a tip offer with neither a range nor presets is refused", status == 400,
      f"got {status}: {created.get('violations', created)}")

qr_no_tip, _, _ = tipping_qr({"allowed": False})
status, stored = call("GET", f"/api/v1/payment-request/{qr_no_tip}")
check("omitting a tip and refusing one are the same stored state",
      (stored.get("bill", {}).get("tip") or {}).get("allowed") is False,
      f"stored as {stored.get('bill', {}).get('tip')}")

# --- what a payer may actually send

RANGE_TIP = {"allowed": True, "range": {"min": 0, "max": 25}}   # 0..250 on a 10000 bill
in_range = AMOUNT // 10                                          # 10%

def tip_notification(tip_config, total, tip_amount):
    """Create a tipping QR Code and announce a payment against it.

    Returns (status, body). A QR Code that could not be created returns (None, reason) rather than
    a status, because a refusal earned by a missing qrcodeId is not the refusal under test. The
    first draft of this section did not do that, and three checks passed against a build where
    tipping was broken: the setup 400'd, the notification carried no id, and "is it refused?"
    was answered yes by the wrong rule.
    """
    qid, status, created = tipping_qr(tip_config)

    if qid is None:
        return None, f"the QR Code could not be created: {created.get('violations', created)}"

    token = sign(notification_with_tip(qid, total, tip_amount))
    return call("POST", "/pub/api/v1/payment-notification", raw_body=token or "",
                headers={"Content-Type": "application/jose"})


def refuses_the_tip(body):
    """A refusal that actually names the tip, not just any refusal."""
    return "tip" in json.dumps(body).lower()


status, body = tip_notification(RANGE_TIP, AMOUNT + in_range, in_range)
check("paying the bill PLUS a tip is accepted", status == 200,
      f"got {status}: {body} — a payer who tips correctly must not be refused")

status, body = tip_notification(RANGE_TIP, AMOUNT, in_range)
check("a tip taken OUT of the merchant's share is refused",
      status is not None and status >= 400 and refuses_the_tip(body),
      f"got {status}: {body} — the merchant would be short by the tip, QR Code marked paid")

status, body = tip_notification(RANGE_TIP, AMOUNT + AMOUNT, AMOUNT)   # 100%, range allows 25%
check("a tip above the published range is refused",
      status is not None and status >= 400 and refuses_the_tip(body), f"got {status}: {body}")

status, body = tip_notification({"allowed": False}, AMOUNT + in_range, in_range)
check("a tip on a bill that refuses tips is refused",
      status is not None and status >= 400 and refuses_the_tip(body),
      f"got {status}: {body} — bill.tip.allowed was false")

# presets suggest, they do not bind - A.10 validates even a preset-selected tip against min..max
odd_tip = AMOUNT * 13 // 100
status, body = tip_notification({"allowed": True, "presets": [10, 15, 20]}, AMOUNT + odd_tip, odd_tip)
check("a tip matching no preset is accepted when no range is published", status == 200,
      f"got {status}: {body} — presets are the suggested buttons, the range is the rule")


# ============================================================================ result

print(f"\n\033[1m{passed} passed, {failed} failed\033[0m")
sys.exit(0 if failed == 0 else min(failed, 250))
