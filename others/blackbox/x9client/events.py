# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""A cursor reader of ``GET /pub/api/v1/events``, deduplicating by ``eventId``.

Delivery is at-least-once, so the reader keeps every ``eventId`` it has seen and never reports
one twice. Java publishes an event about a second after its transition (the outbox drain,
ADR-0034), so a read for one QR Code polls the long-polling cursor instead of reading once and
racing the drain.

The reader starts at the END of the stream as it stood when the reader was made (``mark``), so a
suite run against a long-lived deployment does not page through all of its history every time.
"""
import time

PATH = "/pub/api/v1/events"


class EventReader:
    def __init__(self, api, page_size=500):
        self.api = api
        self.page_size = page_size
        self.start = ""

    def page(self, after="", wait=0, limit=None):
        """One page: ``(events, next_cursor)``. Raises AssertionError on anything but a 200."""
        response = self.api.get(f"{PATH}?after={after}&limit={limit or self.page_size}&wait={wait}")
        assert response.status == 200 and isinstance(response.body, dict), \
            f"the event stream did not answer: {response.status} {response.body}"
        return response.body.get("events", []), response.body.get("nextCursor") or after

    def mark(self):
        """Move the starting point to the current end of the stream, and return it."""
        cursor = self.start
        while True:
            events, cursor = self.page(after=cursor, wait=0)
            if len(events) < self.page_size:
                break
        self.start = cursor
        return cursor

    def read_all(self, after=None, wait=0):
        """Every event after ``after`` (default: the reader's start), deduplicated, in order."""
        seen, cursor = {}, self.start if after is None else after
        while True:
            events, cursor = self.page(after=cursor, wait=wait)
            for event in events:
                seen.setdefault(event["eventId"], event)
            if len(events) < self.page_size:
                return list(seen.values()), cursor

    def for_qr(self, qr_id, *, until=None, timeout=15.0, settle=1.5):
        """The events for one QR Code, deduplicated, in stream order.

        - ``until``: a predicate over the list; polls until it holds or ``timeout`` passes.
        - without ``until``: polls until at least one event appears, then reads for ``settle``
          more seconds, so a second event that should NOT exist has had its chance to appear.

        Returns whatever was seen; the test asserts on it.
        """
        seen, cursor = {}, self.start
        deadline = time.monotonic() + timeout
        settled_at = None

        while True:
            remaining = deadline - time.monotonic()
            wait = max(0, min(2, int(remaining)))
            events, cursor = self.page(after=cursor, wait=wait)
            for event in events:
                if event.get("qrCodeId") == qr_id:
                    seen.setdefault(event["eventId"], event)
            mine = list(seen.values())

            if until is not None:
                if until(mine) or time.monotonic() >= deadline:
                    return mine
                continue

            if mine and settled_at is None:
                settled_at = time.monotonic() + settle
            if settled_at is not None and time.monotonic() >= settled_at:
                return mine
            if time.monotonic() >= deadline:
                return mine
            if settled_at is not None and not events:
                time.sleep(0.2)

    def types_for(self, qr_id, **kwargs):
        return [event.get("type") for event in self.for_qr(qr_id, **kwargs)]

    def none_for(self, qr_id, wait=2.5):
        """Assert-friendly: the events for ``qr_id`` after waiting ``wait`` seconds (expect [])."""
        time.sleep(wait)
        return [e for e in self.read_all()[0] if e.get("qrCodeId") == qr_id]
