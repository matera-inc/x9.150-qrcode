#!/usr/bin/env python3
"""
Playground — simulate the PAYEE (the merchant creating a QR).

Lists the create requests in requests/x9-create-*.json, lets you pick one, POSTs it to the app, and
saves the resulting EMV QR string as x9-created-<scenario>.emv (which simulate_payer.py then reads).

    python3 simulate_payee.py            # interactive menu
    python3 simulate_payee.py burger     # non-interactive: pick by name (or by number)

Needs the app running (`make up`). Override the target with X9_BASE_URL. Stdlib only.
"""
import os
import sys
import json
import glob
import urllib.request
import urllib.error

BASE = os.environ.get("X9_BASE_URL", "http://localhost:8080")
HERE = os.path.dirname(os.path.abspath(__file__))
REQ_DIR = os.path.join(HERE, "requests")


def http(method, url, data=None, headers=None):
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


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


creates = sorted(glob.glob(os.path.join(REQ_DIR, "x9-create-*.json")))
if not creates:
    sys.exit(f"no x9-create-*.json in {REQ_DIR}")

chosen = pick(creates, "create request", sys.argv[1] if len(sys.argv) > 1 else None)
scenario = os.path.basename(chosen)[len("x9-create-"):-len(".json")]
print(f"[payee]   {os.path.basename(chosen)}  (scenario: {scenario})")

body = json.load(open(chosen))
code, resp = http("POST", f"{BASE}/api/v1/payment-request",
                  json.dumps(body).encode(), {"Content-Type": "application/json"})
# if a fixed locationId is already in use, retry letting the server generate a fresh one
if code not in (200, 201) and "locationId" in body:
    print(f"[payee]   HTTP {code} — retrying with a server-generated locationId")
    body.pop("locationId", None)
    code, resp = http("POST", f"{BASE}/api/v1/payment-request",
                      json.dumps(body).encode(), {"Content-Type": "application/json"})
print(f"[payee]   HTTP {code}")
if code not in (200, 201):
    print(resp[:600]); sys.exit(1)

d = json.loads(resp)
out = os.path.join(HERE, f"x9-created-{scenario}.emv")
open(out, "w").write(d["qrCode"])
print(f"[payee]   id={d['id']}  loc={d['location']['id']}")
print(f"[payee]   saved {os.path.basename(out)}\n\n{d['qrCode']}")
