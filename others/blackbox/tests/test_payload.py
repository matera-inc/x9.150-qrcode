# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""A payer can fetch the signed payload (acceptance.py section 2, folded in by Story 3.4).

The payload is a JWS, not JSON: the payee signs it so the payer can prove where the bank details
came from. Here it is verified against the payee's certificate, not merely decoded.
"""
import pytest

from x9client import jws
from x9client.samples import BILL, FEDNOW, RECIPIENT


@pytest.fixture(scope="module")
def fetched(api, payee_certificate, business_now):
    created = api.create_qr_detailed()
    assert created.status == 201, created
    response = api.fetch_payload(created.body["location"]["id"], created.body["qrCode"])
    assert response.status == 200, f"a live QR Code serves its payload: {response}"
    header, claims = jws.verify(response.text, payee_certificate, now_ms=business_now)
    return created.body, claims


def test_the_payload_is_a_jws_signed_by_the_payee(fetched):
    """X9-LOC-503 — the payload is a JWS that verifies against the payee's certificate.

    S3.4-AC1, section 2 — the payload verifies against the payee's certificate."""
    _, claims = fetched
    assert isinstance(claims, dict) and claims


def test_the_payload_states_what_was_created(fetched):
    """X9-LOC-504 — the payload states the amount, the currency and the Solana recipient that were
    created.

    S3.4-AC1, section 2 — the amount, the currency, and the Solana recipient a payer pays."""
    _, claims = fetched
    assert claims["bill"]["amountDue"] == {"amount": BILL, "currency": "USDC"} or (
        claims["bill"]["amountDue"]["amount"] == BILL
        and claims["bill"]["amountDue"]["currency"] == "USDC"), claims["bill"]
    assert claims["paymentMethods"][0]["networks"]["solana"]["recipient"] == RECIPIENT


def test_the_payload_says_where_to_send_the_notification(fetched):
    """X9-LOC-505 — the payload names the notification endpoint.

    S3.4-AC1, section 2 — paymentNotification names the notification endpoint."""
    _, claims = fetched
    assert "payment-notification" in str(claims.get("paymentNotification")), claims


def test_an_unsigned_payload_request_is_refused_and_serves_nothing(api, fetched):
    """X9-LOC-506 — an unsigned payload request is refused with 400 and serves nothing.

    S3.4-AC1, section 2 — not a JWS: 400, and no payload in the answer."""
    created, _ = fetched
    response = api.post_jose(f"/pub/api/v1/loc/{created['location']['id']}", "not-a-jws")

    assert response.status == 400, response
    assert RECIPIENT not in response.text, "a refused request must not carry the bank details"


@pytest.mark.parametrize("protection_type", ["tokenized", "encrypted", "plaintext"])
def test_protection_type_is_read_back_as_sent(api, payee_certificate, business_now,
                                              protection_type):
    """X9-RAIL-506 — ``protectionType`` is read back as it was sent, on ``fednow``, ``rtp`` and
    ``ach``, from GET and from the signed payload, for each allowed value.

    Source: ANSI X9.150-2026 §14.5.x.3: the protection type is a value the payee sets, from an
      enum.
    Why: The Java build answered ``tokenized`` whatever was sent (found by Story 9.6), so a payee
      that sent a plaintext account number signed a payload claiming it was tokenized.
    """
    bank = dict(FEDNOW, protectionType=protection_type)
    created = api.create_qr_detailed(currency="USD",
                                     networks={"fednow": bank, "rtp": bank, "ach": bank})
    assert created.status == 201, created

    read = api.qr(created.body["id"])
    response = api.fetch_payload(created.body["location"]["id"], created.body["qrCode"])
    assert response.status == 200, response
    _, claims = jws.verify(response.text, payee_certificate, now_ms=business_now)

    for rail in ("fednow", "rtp", "ach"):
        assert read.body["paymentMethods"][0]["networks"][rail]["protectionType"] == \
            protection_type, (rail, read.body)
        assert claims["paymentMethods"][0]["networks"][rail]["protectionType"] == \
            protection_type, (rail, claims)
