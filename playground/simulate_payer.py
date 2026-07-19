#!/usr/bin/env python3
"""
Playground — simulate the PAYER (scanning a QR and fetching its payload).

Lists the created EMVs (x9-created-*.emv, produced by simulate_payee.py), lets you pick one, signs a
JWS request, makes the single payload-retrieval call, verifies the response echoes the payer's
correlationId, and saves the decoded payment payload as x9-payer-<scenario>.json.

    python3 simulate_payer.py            # interactive menu
    python3 simulate_payer.py burger     # non-interactive: pick by name (or by number)

Run simulate_payee.py first to produce an EMV. Needs the app running (`make up`). Stdlib only.

The JWS here is signed via the app's own /api/v1/signature/generate (it uses the bundled demo
certificate, which the truststore trusts). A real payer signs with its own X9-issued certificate.
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


def pick(paths, label, arg):
    names = [os.path.basename(p) for p in paths]
    if arg:  # non-interactive: 1-based index, exact name, or a substring
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
        sel = input("> ").strip()
        if sel.isdigit() and 1 <= int(sel) <= len(paths):
            return paths[int(sel) - 1]
        print("  pick a number from the list")


emvs = sorted(glob.glob(os.path.join(HERE, "x9-created-*.emv")))
if not emvs:
    sys.exit("no x9-created-*.emv found — run simulate_payee.py first")

chosen = pick(emvs, "created EMV", sys.argv[1] if len(sys.argv) > 1 else None)
scenario = os.path.basename(chosen)[len("x9-created-"):-len(".emv")]
emv = open(chosen).read().strip()
locid = emv[emv.find("/loc/") + 5:][:32]
print(f"[payer]   {os.path.basename(chosen)}  (scenario: {scenario}, loc={locid})")

# the payer wraps the scanned QR content (base64) and signs it as a JWS (fresh correlationId)
qr_content = base64.b64encode(emv.encode()).decode()
cid = str(uuid.uuid4())
code, jws = http("POST", f"{BASE}/api/v1/signature/generate",
                 json.dumps({"qrCodeContent": qr_content}).encode(),
                 {"Content-Type": "application/json", "Correlation-Id": cid, "TTL-Seconds": "300"})
print(f"[payer]   sign  HTTP {code}  (correlationId={cid})")
if code != 200:
    print(jws[:600]); sys.exit(1)
jws = jws.strip()
open(os.path.join(HERE, f"x9-payer-{scenario}.jws"), "w").write(jws)

# the single payer call: POST the JWS to the loc endpoint -> payload as a JWS
code, resp = http("POST", f"{BASE}/pub/api/v1/loc/{locid}",
                  jws.encode(), {"Content-Type": "application/jose", "dateForPayment": "2030-06-01"})
print(f"[payer]   loc   HTTP {code}")
if code != 200:
    print(resp[:800]); sys.exit(1)

parts = resp.strip().split(".")
hdr = b64url(parts[0])
print(f"[verify]  correlationId echoed back? {hdr.get('correlationId') == cid}   (statusCode={hdr.get('statusCode')})")

payload = b64url(parts[1])
out = os.path.join(HERE, f"x9-payer-{scenario}.json")
open(out, "w").write(json.dumps(payload, indent=2))
print(f"[payer]   saved {os.path.basename(out)}\n")
print(json.dumps(payload, indent=2))
