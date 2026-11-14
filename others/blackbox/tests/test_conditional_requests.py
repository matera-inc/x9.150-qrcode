# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Conditional requests, and what a revision counts (acceptance.py section 8, folded in by
Story 3.4).

These exist because a suite that exercises only the payment flow would pass against a build that
had silently lost ETag support, If-Match enforcement or the revision rule; that happened once, and
cost an adopter an afternoon proving a published image wrong when their node served a stale one.
"""
from x9client import samples

STALE = '"never-existed"'


def _qr_with_repeated_labels(api):
    response = api.post("/api/v1/payment-request", body=samples.payment_request(
        additionalInformation=[{"key": "Partial payment", "value": "first"},
                               {"key": "Partial payment", "value": "second"}]))
    assert response.status == 201, response
    return response.body["id"]


def test_get_serves_an_etag(api):
    """S3.4-AC1, section 8 — the token a conditional request echoes."""
    assert api.qr(api.create_qr()).etag, "GET must serve an ETag"


def test_if_match_is_enforced_on_patch_and_nothing_moves(api):
    """S3.4-AC1, section 8 — a stale If-Match on PATCH: 412, amount and ETag unchanged."""
    qr = api.create_qr()
    before = api.qr(qr)

    status, _ = api.patch(f"/api/v1/payment-request/{qr}", if_match=STALE, body={
        "paymentMethods": [samples.payment_method(amount=11111)]})

    assert status == 412, f"got {status}, so a stale edit would apply"
    after = api.qr(qr)
    assert after.etag == before.etag
    assert after.body["paymentMethods"][0]["amount"] == before.body["paymentMethods"][0]["amount"]


def test_if_match_is_enforced_on_status_update_and_nothing_moves(api):
    """S3.4-AC1, section 8 — a stale If-Match on status-update: 412, still ACTIVE."""
    qr = api.create_qr()

    status, _ = api.status_update(qr, {"status": "CANCELLED"}, if_match=STALE)

    assert status == 412, f"got {status}, so a stale cancel would apply"
    assert api.status_of(qr) == "ACTIVE"


def test_a_repeated_additional_information_label_is_not_collapsed(api):
    """S3.4-AC1, section 8 — both lines survive: they explain the amount to the payer."""
    entries = api.qr(_qr_with_repeated_labels(api)).body.get("additionalInformation") or []
    assert len(entries) == 2, f"{len(entries)} of 2 survived"


def test_a_status_change_is_not_a_new_revision(api):
    """S3.4-AC1, section 8 — PAYMENT_INITIATED through status-update keeps the revision: a
    status is not a version of the request."""
    qr = api.create_qr()
    before = api.qr(qr).body["revision"]

    api.set_status(qr, "PAYMENT_INITIATED")

    after = api.qr(qr).body
    assert after["status"] == "PAYMENT_INITIATED"
    assert after["revision"] == before, f"{before} -> {after['revision']}"
