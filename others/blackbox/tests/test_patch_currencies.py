# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""A patch names every currency, or none of them (acceptance.py section 10, folded in by
Story 3.4).

Our rule, not the standard's. Both halves replace a SILENT failure: an unknown currency used to be
discarded and answered 200, and an omitted currency kept its old amount while the others moved: one
debt with two prices. The revision moved in both cases, so the ETag moved too and a conditional
client saw every sign of success. So these assert the APPLIED amounts, never the status alone.
"""
import pytest

from x9client import samples
from x9client.samples import BILL, FAR_FUTURE, FEDNOW, RECIPIENT

REDUCED = BILL // 2


def _method(currency, amount):
    networks = {"fednow": FEDNOW} if currency == "USD" else {"solana": {"recipient": RECIPIENT}}
    return {"currency": currency, "validUntil": FAR_FUTURE, "amount": amount, "networks": networks}


@pytest.fixture
def two_currency_qr(api):
    body = samples.payment_request(currency="USD", networks={"fednow": FEDNOW})
    body["paymentMethods"].append(_method("USDC", BILL))
    response = api.post("/api/v1/payment-request", body=body)
    assert response.status == 201, f"two currencies of one peg group: {response}"
    return response.body["id"]


def _amounts(api, qr):
    return {pm["currency"]: pm["amount"] for pm in api.qr(qr).body["paymentMethods"]}


def _patch(api, qr, methods):
    current = api.qr(qr)
    body = {"bill": {"description": "blackbox",
                     "paymentTiming": current.body["bill"].get("paymentTiming", "immediate"),
                     "amountDue": current.body["bill"]["amountDue"]},
            "paymentMethods": methods}
    return api.patch(f"/api/v1/payment-request/{qr}", body=body, if_match=current.etag)


def test_naming_every_currency_changes_every_currency(api, two_currency_qr):
    """S3.4-AC1, section 10 — the supported edit."""
    status, body = _patch(api, two_currency_qr, [_method("USD", REDUCED), _method("USDC", REDUCED)])
    assert status == 200, body
    assert _amounts(api, two_currency_qr) == {"USD": REDUCED, "USDC": REDUCED}


def test_omitting_a_currency_is_refused_naming_it_and_nothing_moves(api, two_currency_qr):
    """S3.4-AC1, section 10 — one debt must not end up with two prices."""
    before, etag = _amounts(api, two_currency_qr), api.qr(two_currency_qr).etag

    status, body = _patch(api, two_currency_qr, [_method("USD", REDUCED)])

    assert status == 400 and "USDC" in str(body), f"{status} {body}"
    assert _amounts(api, two_currency_qr) == before
    assert api.qr(two_currency_qr).etag == etag, "a refused patch does not move the ETag"


def test_a_currency_it_does_not_offer_is_refused_naming_it_and_nothing_moves(api, two_currency_qr):
    """S3.4-AC1, section 10 — a patch may change an amount, never add a currency."""
    before = _amounts(api, two_currency_qr)

    status, body = _patch(api, two_currency_qr, [_method("USD", REDUCED), _method("USDC", REDUCED),
                                                 _method("FRNT", REDUCED)])

    assert status == 400 and "FRNT" in str(body), f"{status} {body}"
    assert _amounts(api, two_currency_qr) == before


def test_a_patch_naming_only_an_unknown_currency_is_refused_not_a_silent_200(api, two_currency_qr):
    """S3.4-AC1, section 10 — the case that used to be a no-op answered 200, revision and all."""
    etag = api.qr(two_currency_qr).etag

    status, body = _patch(api, two_currency_qr, [_method("FRNT", REDUCED)])

    assert status == 400, f"{status} {body}"
    assert api.qr(two_currency_qr).etag == etag, \
        "a moved ETag tells a conditional client the write landed"
