# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""It refuses what it cannot honour, by name (acceptance.py section 6, folded in by Story 3.4)."""
from x9client.checks import assert_refused


def test_an_unsupported_currency_is_refused_naming_it(api):
    """S3.4-AC1, section 6 — EUR is not in supported-currencies.json: 400 naming EUR, no id."""
    response = api.create_qr_detailed(currency="EUR")
    assert_refused(response, naming="EUR", status=400)
    assert "id" not in response.body


def test_an_uninterpreted_network_is_refused_naming_it(api):
    """S3.4-AC1, section 6 — tron is not interpreted (ADR-0012): 400 naming it, no id."""
    response = api.create_qr_detailed(networks={"tron": {"walletAddress": "TXYZ"}})
    assert response.status == 400, response
    assert "tron" in response.text.lower(), response.body
    assert "id" not in response.body


def test_an_unknown_qr_code_is_404(api):
    """S3.4-AC1, section 6 — an id nobody issued."""
    assert api.qr("0" * 32).status == 404
