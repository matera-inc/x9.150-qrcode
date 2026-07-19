#!/usr/bin/env python3
"""
Playground: simulate an X9.150 *payer* against a locally running x9-qrcode app.

Flow (see README.md):
  1. (merchant) A QR was created from `x9-create.json`, producing `x9-created.emv`.
  2. (payer) base64 the scanned EMV string as `qrCodeContent`.
  3. (payer) sign it as a JWS — here via the app's own /api/v1/signature/generate
     (it signs with the bundled demo certificate, which the truststore trusts).
     A real payer would sign with its own X9-issued certificate.
  4. (payer) POST that JWS to /pub/api/v1/loc/{id} — the single call that returns
     the payment payload as a JWS.
  5. verify the response echoes our correlationId, then decode the payload JSON.

Requires the app running (e.g. `make up`). Override the base URL with X9_BASE_URL.
Reads/writes files next to this script. No third-party dependencies (stdlib only).
"""
import os
import json
import uuid
import base64
import urllib.request
import urllib.error

BASE = os.environ.get("X9_BASE_URL", "http://localhost:8080")
HERE = os.path.dirname(os.path.abspath(__file__))
CREATE_FILE = os.path.join(HERE, "x9-create.json")
EMV_FILE = os.path.join(HERE, "x9-created.emv")
JWS_FILE = os.path.join(HERE, "x9-payer.jws")
JSON_FILE = os.path.join(HERE, "x9-payer.json")


def http(method, url, data=None, headers=None):
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def b64url(seg):
    return json.loads(base64.urlsafe_b64decode(seg + "=" * (-len(seg) % 4)).decode())


# 0. ensure the QR exists. 201 = created fresh; 400 "needs to be inactive" = it already
#    exists and is ACTIVE (from an earlier run) — either way we can proceed.
code, _ = http("POST", f"{BASE}/api/v1/payment-request",
               open(CREATE_FILE, "rb").read(), {"Content-Type": "application/json"})
print(f"[create]  HTTP {code} ({'created' if code in (200, 201) else 'already ACTIVE — proceeding'})")

# 1. read the scanned EMV and derive the loc id from it
emv = open(EMV_FILE).read().strip()
locid = emv[emv.find("/loc/") + 5:][:32]
print(f"[emv]     loc id = {locid}")

# 2. the payer wraps the scanned QR content (base64) as the request payload
qr_content = base64.b64encode(emv.encode()).decode()

# 3. the payer signs the request as a JWS (fresh correlationId it generates itself)
cid = str(uuid.uuid4())
code, jws = http("POST", f"{BASE}/api/v1/signature/generate",
                 json.dumps({"qrCodeContent": qr_content}).encode(),
                 {"Content-Type": "application/json", "Correlation-Id": cid, "TTL-Seconds": "300"})
print(f"[sign]    HTTP {code}  (correlationId={cid})")
if code != 200:
    print(jws[:600]); raise SystemExit(1)
jws = jws.strip()
open(JWS_FILE, "w").write(jws)
print(f"[sign]    saved {os.path.basename(JWS_FILE)}  ({len(jws)} chars)")

# 4. THE single payer call: POST the signed JWS to the loc endpoint -> payload JWS
code, resp = http("POST", f"{BASE}/pub/api/v1/loc/{locid}",
                  jws.encode(), {"Content-Type": "application/jose", "dateForPayment": "2030-06-01"})
print(f"[loc]     HTTP {code}")
if code != 200:
    print(resp[:800]); raise SystemExit(1)

# 5. verify the response echoes OUR correlationId, then decode the payment payload
parts = resp.strip().split(".")
resp_hdr = b64url(parts[0])
print(f"[verify]  request  correlationId = {cid}")
print(f"[verify]  response correlationId = {resp_hdr.get('correlationId')}")
print(f"[verify]  echoed back?            {resp_hdr.get('correlationId') == cid}   (statusCode={resp_hdr.get('statusCode')})\n")

payload = b64url(parts[1])
open(JSON_FILE, "w").write(json.dumps(payload, indent=2))
print(f"[payload] saved {os.path.basename(JSON_FILE)}\n")
print(json.dumps(payload, indent=2))
