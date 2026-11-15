# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""What only the database can show: that nothing was written (ADR-0034).

Each test asserts through the API first, then below it. They skip with the reason when
``X9_DATABASE_URL`` is unset or its driver is missing (S3.1-AC3); the API tests are unaffected.
"""
from x9client.checks import assert_refused
from x9client.samples import BILL


def test_a_refused_create_writes_no_qr_code(api, db):
    """X9-LIFE-506 — a create refused for its currency leaves the store exactly as it was.

    S3.1-AC3 — a create refused for its currency leaves the store exactly as it was."""
    before = db.qr_count()

    response = api.create_qr_detailed(currency="EUR")

    assert_refused(response, naming="EUR")
    assert db.qr_count() == before, "a refused create must not write a QR Code"


def test_a_refused_notification_writes_no_event(api, db, events):
    """X9-LIFE-507 — a notification refused for its amount stores nothing and publishes nothing.

    S3.1-AC3 — a notification refused for its amount publishes nothing, and stores nothing."""
    qr = api.create_qr()
    assert db.qr_exists(qr), "the inspector must see the QR Code the API created"

    assert_refused(api.notify(qr, amount=BILL + 1), naming="payment.amount")

    assert events.none_for(qr) == [], "no event on the stream"
    assert db.events_for(qr) == [], "and none in the store"
