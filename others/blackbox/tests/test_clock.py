# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""The movable clock, driven from outside (ADR-0033).

These skip with "test clock not enabled" against a deployment without the clock; they never fail
for that reason (S3.1-AC4). They move a clock shared by the whole deployment, so they are
``serial`` and reset it before and after.
"""
import datetime
import json

import pytest

pytestmark = pytest.mark.serial


def test_the_clock_moves_and_every_answer_says_so(api, clock):
    """S3.1-AC1, S3.1-AC4 — move_to, now and reset; X-Test-Clock-Offset while it is moved.

    Why: a moved clock that nobody can see is how a test deployment's results get mistaken for real
    ones. The header is on every API answer while the offset is non-zero, and gone after reset.
    """
    target = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=1)

    moved = clock.move_to(target)
    assert abs((moved - target).total_seconds()) < 1, f"asked for {target}, got {moved}"
    assert abs((clock.now() - target).total_seconds()) < 5

    offset = api.get("/actuator/health").header("X-Test-Clock-Offset")
    assert offset is not None, "a moved clock must be visible on every answer"
    assert abs(int(offset) - 86_400_000) < 5_000, offset

    clock.reset()
    assert clock.offset_millis() == 0
    assert api.get("/actuator/health").header("X-Test-Clock-Offset") is None, \
        "and invisible again once it is back to real time"


def test_a_payload_stops_being_served_once_the_clock_passes_valid_until(api, clock):
    """X9-LIFE-126 on the test clock — no sleeping for real time.

    The same rule the sleeping test pins (an expired QR Code does not serve its payload), reached
    by moving the clock one second past ``validUntil`` instead of waiting for it.
    """
    valid_until = (datetime.datetime.now(datetime.timezone.utc)
                   + datetime.timedelta(hours=1)).replace(microsecond=0)
    created = api.create_qr_detailed(valid_until=valid_until.strftime("%Y-%m-%dT%H:%M:%SZ"))
    assert created.status == 201, created.body

    before = api.fetch_payload(created.body["location"]["id"], created.body["qrCode"])
    assert before.status == 200, f"a live QR Code serves its payload: {before.status} {before.body}"

    clock.move_to(valid_until + datetime.timedelta(seconds=1))
    after = api.fetch_payload(created.body["location"]["id"], created.body["qrCode"])

    assert after.status >= 400, f"past validUntil the payload must not be served: {after}"
    assert "expired" in json.dumps(after.body).lower(), f"and the refusal says why: {after.body}"
