# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Settlement is the payment system's call (acceptance.py section 5, folded in by Story 3.4)."""
from x9client.samples import TX_HASH


def test_only_status_update_marks_paid_and_a_paid_qr_code_stays_paid(api, payer_a, events):
    """S3.4-AC1, section 5 — status-update PAID is accepted; re-initiating PAID is 409.

    ADR-0036: only the payment system marks a QR Code PAID, through this API, and a closed QR
    Code never reopens.
    """
    qr = api.create_qr()
    api.notify(qr, signer=payer_a, payer_info="settle@example.com")
    api.notify(qr, signer=payer_a, payer_info="settle@example.com", action="SENT",
               transaction_id=TX_HASH)

    status, body = api.status_update(qr, {"status": "PAID", "network": "Solana",
                                          "endToEndId": TX_HASH})
    assert status == 200, body
    assert api.status_of(qr) == "PAID"

    status, body = api.status_update(qr, {"status": "PAYMENT_INITIATED", "network": "Solana"})
    assert status == 409, f"a PAID QR Code must not be re-initiated: {status} {body}"
    assert api.status_of(qr) == "PAID", "and it stays PAID"
    cleared = [e for e in events.for_qr(qr, until=lambda es: any(
        e.get("type") == "payment.cleared" for e in es)) if e.get("type") == "payment.cleared"]
    assert len(cleared) == 1, f"one settlement, one payment.cleared: {cleared}"
