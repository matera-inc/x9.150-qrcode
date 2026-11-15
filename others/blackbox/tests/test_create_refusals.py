# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""It refuses what it cannot honour, by name (acceptance.py section 6, folded in by Story 3.4)."""
from x9client import samples
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


def test_payment_methods_as_a_bare_object_is_refused(api):
    """X9-RAIL-120 — ``paymentMethods`` sent as one object instead of an array is refused: 400
    naming ``paymentMethods``, no id.

    Source: Ours. INTERPRETATION I-5: ``paymentMethods`` is an array, whatever the standard's
      examples show (some print a bare object).
    Why: A payer reading the payload expects an array. Accepting an object here and storing it as
      a one-element list would teach an issuer that the example's shape works, and the next
      implementation that reads the schema literally would refuse what this one issued.
    """
    body = samples.payment_request()
    body["paymentMethods"] = body["paymentMethods"][0]

    response = api.post("/api/v1/payment-request", body=body)

    # Java refuses it before any field is read, as "Malformed JSON", so it cannot name the field;
    # a build that reads the body and names paymentMethods is as right.
    assert response.status == 400, response
    assert response.problem, f"a problem+json refusal: {response}"
    assert "paymentMethods" in response.text or \
        response.problem.get("type", "").endswith("/malformed-json"), response.body
    assert "id" not in response.body
