# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""A location handed over goes to somebody (acceptance.py section 11, folded in by Story 3.4).

The suite once had no coverage of locationId at all, which is how a hand-over shipped that
released the donor's location and gave it to nobody: the QR Code already printed and in a payer's
hands stopped resolving, and the API answered 200 throughout. The donor must be CANCELLED before
its location can be reused.
"""
import pytest

from x9client import jws


def _location(api, qr):
    return (api.qr(qr).body.get("location") or {}).get("id")


def _hand_over(api, taker, location):
    current = api.qr(taker)
    body = {"locationId": location,
            "bill": {"description": "blackbox",
                     "paymentTiming": current.body["bill"].get("paymentTiming", "immediate"),
                     "amountDue": current.body["bill"]["amountDue"]},
            "paymentMethods": [{k: pm[k] for k in ("currency", "validUntil", "amount", "networks")}
                               for pm in current.body["paymentMethods"]]}
    return api.patch(f"/api/v1/payment-request/{taker}", body=body, if_match=current.etag)


@pytest.fixture
def three(api):
    """A donor, a taker, and a bystander: the negative control."""
    return api.create_qr(), api.create_qr(), api.create_qr()


def test_a_live_qr_code_keeps_its_location(api, three):
    """X9-LOC-501 — a live QR Code keeps its location: another create cannot take it.

    S3.4-AC1, section 11 — the donor is still payable, so its location is not up for grabs."""
    donor, taker, _ = three
    donated = _location(api, donor)

    status, _ = _hand_over(api, taker, donated)

    assert status == 400, "a payer holding the printed code is still able to pay it"
    assert _location(api, donor) == donated, "the refused hand-over left the donor its location"


def test_a_cancelled_qr_codes_location_goes_to_the_taker_and_the_donor_gets_a_new_one(
        api, three, payee_certificate, business_now):
    """X9-LOC-502 — a CANCELLED QR Code's location goes to the taker, and the donor gets a new one.

    S3.4-AC1, section 11 — after the donor is CANCELLED: the taker holds the location, the
    donor holds a fresh one (THE BUG THAT SHIPPED: released, and given to nobody), the printed
    code resolves to the taker, and the bystander's location never moved."""
    donor, taker, bystander = three
    donated, bystander_before = _location(api, donor), _location(api, bystander)
    api.status_update(donor, {"status": "CANCELLED"}, if_match=api.qr(donor).etag)

    status, handed = _hand_over(api, taker, donated)

    assert status == 200, f"a cancelled QR Code's location can be handed over: {handed}"
    assert _location(api, taker) == donated
    donor_now = _location(api, donor)
    assert donor_now and donor_now != donated, \
        f"the donor holds {donor_now!r}; a QR Code owning no location resolves to nothing"

    payload = api.fetch_payload(donated, handed["qrCode"])
    assert payload.status == 200, payload
    _, claims = jws.verify(payload.text, payee_certificate, now_ms=business_now)
    assert (claims.get("qrcodeId") or claims.get("id")) == taker, \
        "the printed code at that location serves the taker"

    assert _location(api, bystander) == bystander_before, "an unrelated QR Code kept its location"
