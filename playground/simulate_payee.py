#!/usr/bin/env python3
"""
Playground — the PAYEE (merchant) creating a QR Code.

Lists the create requests in requests/qr-*-createqr.json, lets you pick one, POSTs it to the app
(which stores the request in Mongo and generates the X9.150 QR), and writes the resulting EMV QR
string to qr-<name>.emv — the input for simulate_payer.py.

Note: a qr-*-createqr.json is the *create request*, NOT a full X9.150 payload — it may carry things
like a late-fee formula and it has no digitally-signed QR content. The signed payload is what the
payer later fetches.

    python3 simulate_payee.py            # interactive menu
    python3 simulate_payee.py burger     # non-interactive: pick by name (or number)

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
        try:
            sel = input("> ").strip()
        except EOFError:
            sys.exit("no selection")
        if sel.isdigit() and 1 <= int(sel) <= len(paths):
            return paths[int(sel) - 1]
        print("  pick a number from the list")


def scenario_of(create_path):
    base = os.path.basename(create_path)          # qr-<name>-createqr.json
    return base[len("qr-"):-len("-createqr.json")]


creates = sorted(glob.glob(os.path.join(REQ_DIR, "qr-*-createqr.json")))
if not creates:
    sys.exit(f"no qr-*-createqr.json in {REQ_DIR}")

chosen = pick(creates, "create request", sys.argv[1] if len(sys.argv) > 1 else None)
name = scenario_of(chosen)
print(f"[payee]  {os.path.basename(chosen)}  (name: {name})")

body = json.load(open(chosen))
code, resp = http("POST", f"{BASE}/api/v1/payment-request",
                  json.dumps(body).encode(), {"Content-Type": "application/json"})
# if a fixed locationId is already in use, retry letting the server generate a fresh one
if code not in (200, 201) and "locationId" in body:
    print(f"[payee]  HTTP {code} - retrying with a server-generated locationId")
    body.pop("locationId", None)
    code, resp = http("POST", f"{BASE}/api/v1/payment-request",
                      json.dumps(body).encode(), {"Content-Type": "application/json"})
print(f"[payee]  HTTP {code}")
if code not in (200, 201):
    print(resp[:600]); sys.exit(1)

d = json.loads(resp)
out = os.path.join(HERE, f"qr-{name}.emv")
open(out, "w").write(d["qrCode"])
print(f"[payee]  id={d['id']}  loc={d['location']['id']}")
print(f"[payee]  wrote {os.path.basename(out)}\n\n{d['qrCode']}")
