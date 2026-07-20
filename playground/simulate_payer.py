#!/usr/bin/env python3
"""
Playground — the PAYER (scanning a QR and fetching its payload).

Lists the created EMVs (qr-*.emv, produced by simulate_payee.py), lets you pick one, signs a JWS
request, makes the single payload-retrieval call, and prints the full payment payload on screen.
Then it asks whether to save the payload JSON, and whether to save the JWS exchange.

    python3 simulate_payer.py            # interactive menu
    python3 simulate_payer.py burger     # non-interactive: pick by name (or number)

Outputs (only if you say yes at the prompts):
    <name>-payload.json    the decoded payment payload
    <name>-call.jws        the request JWS the payer sent
    <name>-response.jws    the response JWS the app returned

The JWS here is signed via the app's own /api/v1/signature/generate (the bundled demo certificate,
trusted by the truststore). A real payer signs with its own X9-issued certificate. Stdlib only.
"""
import os
import sys
import json
import glob
import uuid
import base64
import urllib.request
import urllib.error

BASE = os.environ.get("X9_BASE_URL", "http://localhost:8080")
HERE = os.path.dirname(os.path.abspath(__file__))


def http(method, url, data=None, headers=None):
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def b64url(seg):
    return json.loads(base64.urlsafe_b64decode(seg + "=" * (-len(seg) % 4)).decode())


def ask(prompt, default=True):
    try:
        ans = input(f"{prompt} (Y/n): ").strip().lower()
    except EOFError:
        return default
    return default if ans == "" else ans in ("y", "yes")


def pick(paths, label, arg):
    names = [os.path.basename(p) for p in paths]
    if arg:
        if arg.isdigit() and 1 <= int(arg) <= len(paths):
            return paths[int(arg) - 1]
        for p, n in zip(paths, names):
            if arg == n or arg == n.rsplit(".", 1)[0] or arg in n:
                return p
        sys.exit(f"no {label} matches {arg!r}")
    print(f"Choose a {label}:")
    for i, n in enumerate(names, 1):
        print(f"  {i}) {n}")
    while True:
        try:
            sel = input("> ").strip()
        except EOFError:
            sys.exit("no selection")
        if sel.isdigit() and 1 <= int(sel) <= len(paths):
            return paths[int(sel) - 1]
        print("  pick a number from the list")


emvs = sorted(glob.glob(os.path.join(HERE, "qr-*.emv")))
if not emvs:
    sys.exit("no qr-*.emv found - run simulate_payee.py first")

chosen = pick(emvs, "created EMV", sys.argv[1] if len(sys.argv) > 1 else None)
name = os.path.basename(chosen)[len("qr-"):-len(".emv")]
emv = open(chosen).read().strip()
locid = emv[emv.find("/loc/") + 5:][:32]
print(f"[payer]  {os.path.basename(chosen)}  (name: {name}, loc: {locid})")

# 1. sign the request as a JWS (fresh correlationId the payer generates)
qr_content = base64.b64encode(emv.encode()).decode()
cid = str(uuid.uuid4())
code, call_jws = http("POST", f"{BASE}/api/v1/signature/generate",
                      json.dumps({"qrCodeContent": qr_content}).encode(),
                      {"Content-Type": "application/json", "Correlation-Id": cid, "TTL-Seconds": "300"})
print(f"[payer]  sign  HTTP {code}  (correlationId={cid})")
if code != 200:
    print(call_jws[:600]); sys.exit(1)
call_jws = call_jws.strip()

# 2. the single payer call: POST the JWS -> the payload comes back as a JWS
# dateForPayment is an optional header (UTC calendar date). Omitting it makes the backend
# use "now" (today), so early-payment discounts apply as expected. Set X9_DATE_FOR_PAYMENT
# (YYYY-MM-DD) to simulate paying on another day, e.g. after the due date to see a late fee.
loc_headers = {"Content-Type": "application/jose"}
date_for_payment = os.environ.get("X9_DATE_FOR_PAYMENT")
if date_for_payment:
    loc_headers["dateForPayment"] = date_for_payment
code, resp_jws = http("POST", f"{BASE}/pub/api/v1/loc/{locid}",
                      call_jws.encode(), loc_headers)
print(f"[payer]  loc   HTTP {code}")
if code != 200:
    print(resp_jws[:800]); sys.exit(1)
resp_jws = resp_jws.strip()

# 3. decode + show the payload
hdr = b64url(resp_jws.split(".")[0])
payload = b64url(resp_jws.split(".")[1])
print(f"[verify] correlationId echoed back? {hdr.get('correlationId') == cid}   (statusCode={hdr.get('statusCode')})\n")
print(json.dumps(payload, indent=2))
print()

# 4. offer to save the payload JSON, then the JWS exchange
if ask("Save the payload JSON"):
    p = os.path.join(HERE, f"{name}-payload.json")
    open(p, "w").write(json.dumps(payload, indent=2))
    print(f"[payer]  wrote {os.path.basename(p)}")
else:
    print("[payer]  payload shown only (not saved)")

if ask("Save the JWS exchange (call + response)"):
    open(os.path.join(HERE, f"{name}-call.jws"), "w").write(call_jws)
    open(os.path.join(HERE, f"{name}-response.jws"), "w").write(resp_jws)
    print(f"[payer]  wrote {name}-call.jws and {name}-response.jws")
else:
    print("[payer]  JWS not saved")
