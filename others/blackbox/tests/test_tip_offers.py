# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""A tip is money on top of the bill, never a slice of it (acceptance.py section 9, folded in by
Story 3.4).

ANSI X9.150-2026 §13.6.2: the computed total (amount + tip) applies to the payment instruction and
the notification, so ``amount`` is the whole transfer and the merchant's share is
``amount - tipAmount``. The notification-side rules are pinned in detail by
``test_payment_notification.TestTip``; this module keeps the section's create-side checks and its
end-to-end cases on the acceptance amount.
"""
import json

import pytest

AMOUNT = 22500
RANGE_TIP = {"allowed": True, "range": {"min": 0, "max": 25}}
IN_RANGE = AMOUNT // 10


@pytest.mark.parametrize("label, tip", [
    ("tips refused", {"allowed": False}),
    ("tips allowed within a range", {"allowed": True, "range": {"min": 0, "max": 25}}),
    ("tips allowed with presets", {"allowed": True, "presets": [10, 15, 20]}),
])
def test_each_tip_form_can_be_created(api, label, tip):
    """S3.4-AC1, section 9 — the three forms that used to be impossible to create at all."""
    response = api.create_qr_detailed(tip=tip, amount=AMOUNT)
    assert response.status == 201, f"{label}: {response.status} {response.body}"


def test_a_tip_offer_with_neither_range_nor_presets_is_refused(api):
    """S3.4-AC1, section 9 — "tips welcome" with nothing to render is not an offer (ours,
    stricter than the standard, which makes both MAY)."""
    response = api.create_qr_detailed(tip={"allowed": True}, amount=AMOUNT)
    assert response.status == 400, response
    assert "id" not in response.body


def test_omitting_a_tip_and_refusing_one_are_the_same_stored_state(api):
    """S3.4-AC1, section 9 — no tip block is stored as allowed: false."""
    qr = api.create_qr(amount=AMOUNT)
    assert (api.qr(qr).body["bill"].get("tip") or {}).get("allowed") is False


def _tipping(api, tip, total, tip_amount):
    qr = api.create_qr(tip=tip, amount=AMOUNT)
    return qr, api.notify(qr, amount=total, tip=tip_amount)


def test_the_bill_plus_a_tip_is_accepted(api):
    """S3.4-AC1, section 9 — a payer who tips correctly is not refused."""
    _, response = _tipping(api, RANGE_TIP, AMOUNT + IN_RANGE, IN_RANGE)
    assert response.status == 200, response


@pytest.mark.parametrize("why, tip, total, tip_amount", [
    ("taken out of the merchant's share", RANGE_TIP, AMOUNT, IN_RANGE),
    ("above the published range", RANGE_TIP, AMOUNT + AMOUNT, AMOUNT),
    ("on a bill that refuses tips", {"allowed": False}, AMOUNT + IN_RANGE, IN_RANGE),
])
def test_a_wrong_tip_is_refused_naming_the_tip_and_reserves_nothing(api, why, tip, total,
                                                                     tip_amount):
    """S3.4-AC1, section 9 — refused for the tip, and the QR Code stays ACTIVE."""
    qr, response = _tipping(api, tip, total, tip_amount)
    assert response.status == 400, f"{why}: {response.status} {response.body}"
    assert "tip" in json.dumps(response.body).lower(), f"{why}: refused, but not for the tip: {response.body}"
    assert api.status_of(qr) == "ACTIVE", f"{why}: a refused tip must not reserve"


def test_a_tip_matching_no_preset_is_accepted_when_no_range_is_published(api):
    """S3.4-AC1, section 9 — presets are suggested buttons; the range is the rule (A.10)."""
    odd = AMOUNT * 13 // 100
    _, response = _tipping(api, {"allowed": True, "presets": [10, 15, 20]}, AMOUNT + odd, odd)
    assert response.status == 200, response
