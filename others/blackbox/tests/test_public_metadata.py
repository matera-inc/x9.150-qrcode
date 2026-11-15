# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Public signing metadata is served (acceptance.py section 7, folded in by Story 3.4)."""
from x9client import jws


def test_the_jwk_set_is_served_with_a_certificate_chain(api):
    """X9-SIG-507 — the JWK set is served, each key with its x5c chain.

    S3.4-AC1, section 7 — GET /pub/.well-known/jwks has keys, each with its x5c, so the other
    side can verify this payee's signatures."""
    status, jwks = api.get("/pub/.well-known/jwks")
    assert status == 200 and isinstance(jwks, dict) and jwks.get("keys"), jwks
    certificate = jws.certificate_from_jwks(jwks)
    assert certificate.subject.rfc4514_string()
