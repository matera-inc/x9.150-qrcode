# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
"""Verify Go-signed tokens with the black-box suite's independent verifier (S8.4-AC3).

    python -I x9client_verify.py <blackbox-dir> <tokens.json>

<tokens.json> is a list of {"name", "token", "certificate" (base64 DER of the signing certificate),
"nowMillis", "correlationId", "statusCode" (or null), "answer", "expect" (VERIFIED or REFUSED)},
written by internal/jws's tests. Each token is given to x9client.jws.verify against its certificate
at nowMillis, with the expected correlationId. A response's statusCode must also be in its header,
in crit and, for an answer, in its body. The REFUSED controls prove the check is not a no-op.

Prints one line per token and exits 1 if any verdict is not the expected one. x9client shares no
code with Go or Java.
"""
import base64
import json
import sys


def main(blackbox_dir, tokens_file):
    sys.path.insert(0, blackbox_dir)
    from cryptography import x509
    from x9client import jws

    with open(tokens_file, encoding="utf-8") as f:
        cases = json.load(f)
    wrong = 0
    for case in cases:
        certificate = x509.load_der_x509_certificate(base64.b64decode(case["certificate"]))
        try:
            header, payload = jws.verify(case["token"], certificate,
                                         expected_correlation_id=case["correlationId"],
                                         now_ms=lambda: case["nowMillis"])
            status = case.get("statusCode")
            if status is not None:
                if header.get("statusCode") != status or "statusCode" not in header["crit"]:
                    raise jws.VerificationError("STATUS_MISMATCH", f"header {header}")
                if case.get("answer") and payload.get("statusCode") != status:
                    raise jws.VerificationError("STATUS_MISMATCH", f"body {payload}")
            verdict = "VERIFIED"
        except Exception as e:  # noqa: BLE001 — every refusal is reported, whatever raised it
            verdict = f"REFUSED {type(e).__name__}: {e}"
        expected = case.get("expect", "VERIFIED")
        ok = verdict.split(" ")[0] == expected
        wrong += 0 if ok else 1
        print(f"{'ok   ' if ok else 'WRONG'} expected {expected}, got {verdict}: {case['name']}")
    print(f"{len(cases) - wrong}/{len(cases)} as expected")
    return 1 if wrong else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
