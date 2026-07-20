#!/usr/bin/env python3
"""
Dump a JWS/JWT compact token — pretty-print its JOSE header and payload.

Handles base64url + missing padding (which plain `base64 -d` does not). Does NOT verify the
signature; this is an inspector, not a validator.

    python3 dumpjws.py burger-response.jws        # from a file
    python3 dumpjws.py <<< "eyJhbGci..."           # or pipe/paste the compact string
    cat some.jws | python3 dumpjws.py

Stdlib only.
"""
import sys
import json
import base64


def b64url(seg):
    return base64.urlsafe_b64decode(seg + "=" * (-len(seg) % 4))


def pretty(raw):
    try:
        return json.dumps(json.loads(raw), indent=2)
    except Exception:
        return raw.decode("utf-8", "replace")  # non-JSON / detached payload


token = (open(sys.argv[1]).read() if len(sys.argv) > 1 else sys.stdin.read()).strip()
parts = token.split(".")
if len(parts) < 2:
    sys.exit("not a compact JWS/JWT (expected header.payload[.signature])")

print("=== JOSE header ===")
print(pretty(b64url(parts[0])))
print("\n=== payload ===")
print(pretty(b64url(parts[1])) if parts[1] else "(detached / empty)")
if len(parts) > 2 and parts[2]:
    print(f"\n=== signature ===\n({len(parts[2])} base64url chars — not verified)")
