# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""It refuses what it cannot honour, by name (acceptance.py section 6, folded in by Story 3.4)."""
import json

import pytest

from x9client import samples
from x9client.checks import assert_refused


def test_an_unsupported_currency_is_refused_naming_it(api):
    """X9-CUR-501 — a currency outside supported-currencies.json is refused at create, naming it,
    with no id.

    S3.4-AC1, section 6 — EUR is not in supported-currencies.json: 400 naming EUR, no id."""
    response = api.create_qr_detailed(currency="EUR")
    assert_refused(response, naming="EUR", status=400)
    assert "id" not in response.body


def test_an_uninterpreted_network_is_refused_naming_it(api):
    """X9-RAIL-501 — a network this deployment does not interpret is refused at create, naming it,
    with no id.

    S3.4-AC1, section 6 — tron is not interpreted (ADR-0012): 400 naming it, no id."""
    response = api.create_qr_detailed(networks={"tron": {"walletAddress": "TXYZ"}})
    assert response.status == 400, response
    assert "tron" in response.text.lower(), response.body
    assert "id" not in response.body


def test_an_unknown_qr_code_is_404(api):
    """X9-LIFE-501 — a GET of an id nobody issued is 404.

    S3.4-AC1, section 6 — an id nobody issued."""
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


UNKNOWN_SOLANA_X = ("paymentMethods[0].networks.solana.x: "
                    "is not a field of solana, whose fields are recipient and memo")


def test_an_unknown_field_in_solana_is_refused_at_create_naming_it(api):
    """X9-RAIL-502 — an unknown field inside ``solana`` is refused at create: 400 naming its path,
    no id.

    Source: Ours. INTERPRETATION I-9 and the contract: ``Solana`` is ``additionalProperties: false``,
      because a rail interpreted on a published embedding reports a field it does not know. R-I9-03.
    Why: The Java build once answered 201 and dropped the field, so a mistyped ``memo`` became a
      printed QR Code without the memo the biller meant to match payments with, and nobody was told.
    """
    response = api.create_qr_detailed(networks={"solana": {"recipient": samples.RECIPIENT, "x": 1}})

    assert response.status == 400, response
    assert response.violations == [UNKNOWN_SOLANA_X], response.body
    assert "id" not in response.body


def test_an_unknown_field_in_solana_is_refused_on_patch_and_nothing_moves(api):
    """X9-RAIL-503 — an unknown field inside ``solana`` is refused on PATCH, naming its path, and the
    ETag does not move.

    Source: Ours. INTERPRETATION I-9, R-I9-03: a PATCH replaces the same closed ``Solana`` object.
    Why: A refusal at create that a PATCH lets through is no refusal; the same field would be dropped
      one request later.
    """
    qr = api.create_qr()
    current = api.qr(qr)
    method = samples.payment_method(networks={"solana": {"recipient": samples.RECIPIENT, "x": 1}})

    response = api.patch(f"/api/v1/payment-request/{qr}", body={"paymentMethods": [method]},
                         if_match=current.etag)

    assert response.status == 400, response
    assert response.violations == [UNKNOWN_SOLANA_X], response.body
    assert api.qr(qr).etag == current.etag, "a refused patch does not move the ETag"


def test_a_query_parameter_of_the_wrong_type_answers_violations_as_an_array(api):
    """X9-SIG-528 — a typed query parameter that does not parse (``?limit=x``, ``?revision=x``) is
    400 with ``violations`` an array naming it.

    Source: Ours. ``BaseError`` in openapi.yaml declares ``violations`` an array of strings.
    Why: The Java build answered this one refusal with a bare string, so a client that iterates the
      array, as the contract tells it to, read the sentence one character at a time.
    """
    qr = api.create_qr()
    for path, violation in (("/pub/api/v1/events?limit=x", "limit has invalid value."),
                            (f"/api/v1/payment-request/{qr}?revision=x", "revision has invalid value.")):
        response = api.get(path)

        assert response.status == 400, (path, response)
        assert isinstance(response.body.get("violations"), list), (path, response.body)
        assert response.body["violations"] == [violation], (path, response.body)


BANK_FIELDS = "routingNumber, accountNumber and protectionType"


@pytest.mark.parametrize("rail", ["fednow", "rtp", "ach"])
def test_an_unknown_field_in_a_bank_rail_is_refused_at_create_naming_it(api, rail):
    """X9-RAIL-504 — an unknown field inside ``fednow``, ``rtp`` or ``ach`` is refused at create:
    400 naming its path, no id.

    Source: Ours. ADR-0012, amendment of 2026-11-15: what we generate is closed; ``FedNow``,
      ``RTP`` and ``ACH`` are ``additionalProperties: false``, as ``Solana`` is.
    Why: The Java build answered 201 and dropped the field, so a biller's extra or mistyped bank
      field vanished from the QR Code they printed, and nobody was told.
    """
    response = api.create_qr_detailed(currency="USD", networks={rail: dict(samples.FEDNOW, x=1)})

    assert response.status == 400, response
    assert response.violations == [
        f"paymentMethods[0].networks.{rail}.x: is not a field of {rail}, whose fields are "
        f"{BANK_FIELDS}"], response.body
    assert "id" not in response.body


@pytest.mark.parametrize("rail", ["fednow", "rtp", "ach"])
def test_an_unknown_field_in_a_bank_rail_is_refused_on_patch_and_nothing_moves(api, rail):
    """X9-RAIL-505 — an unknown field inside a bank rail is refused on PATCH, naming its path, and
    the ETag does not move.

    Source: Ours. ADR-0012, amendment of 2026-11-15: a PATCH replaces the same closed object.
    Why: A refusal at create that a PATCH lets through is no refusal.
    """
    qr = api.create_qr(currency="USD", networks={rail: samples.FEDNOW})
    current = api.qr(qr)
    method = samples.payment_method(currency="USD", networks={rail: dict(samples.FEDNOW, x=1)})

    response = api.patch(f"/api/v1/payment-request/{qr}", body={"paymentMethods": [method]},
                         if_match=current.etag)

    assert response.status == 400, response
    assert response.violations == [
        f"paymentMethods[0].networks.{rail}.x: is not a field of {rail}, whose fields are "
        f"{BANK_FIELDS}"], response.body
    assert api.qr(qr).etag == current.etag, "a refused patch does not move the ETag"


# ADR-0028, known difference 7: a JSON edge case Jackson reads is malformed to the Go build. Each
# case is a placeholder in an otherwise good create body, replaced by the edge, or by its control.
STRICT_JSON = [
    ("a lone surrogate escape", ("bill", "description"), '"\\ud800"', '"\\u0041"'),
    ("a date written as an array", ("bill", "order", "date"), "[2030,1,10]", '"2030-01-10"'),
]


@pytest.mark.parametrize("name, path, edge, control", STRICT_JSON,
                         ids=[case[0] for case in STRICT_JSON])
def test_a_json_edge_case_jackson_reads_is_malformed(api, name, path, edge, control):
    """X9-SIG-532 — a create body carrying a lone surrogate escape (``"\\ud800"``) or a date written
    as an array (``[2030,1,10]``) is 400 ``malformed-json``, no id; the same body with an escape
    that is a whole character (``"\\u0041"``; the description must be printable ASCII, so a
    surrogate pair would be refused for that), or the date as ``"2030-01-10"``, is created.

    S11.4-AC1.

    Source: Ours. ADR-0028, known difference 7 (Story 9.2). RFC 8259 §8.2 leaves what a lone
      surrogate means unpredictable; ``format: date`` in openapi.yaml is a string.
    Why: A body a strict parser refuses is not one a payer can rely on anywhere else: a string that
      is not Unicode text cannot be carried in a QR Code or signed as UTF-8, and an array where the
      contract says a date is a Java serialisation habit, not the contract.
    """
    body = samples.payment_request()
    if path[:2] == ("bill", "order"):
        body["bill"]["order"] = {"number": "ORD-1"}
    target = body
    for key in path[:-1]:
        target = target[key]
    target[path[-1]] = "X9-PLACEHOLDER"
    text = json.dumps(body)

    response = api.call("POST", "/api/v1/payment-request",
                        raw=text.replace('"X9-PLACEHOLDER"', edge))

    assert response.status == 400, f"{name}: {response}"
    assert response.problem.get("type", "").endswith("/malformed-json"), response.body
    assert "id" not in response.body

    control = api.call("POST", "/api/v1/payment-request",
                       raw=text.replace('"X9-PLACEHOLDER"', control))
    assert control.status == 201, f"{name}, the control: {control}"
