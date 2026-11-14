# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""The movable test clock (ADR-0033): ``move_to``, ``now``, ``reset``.

The clock lives on its own listener, never on the API port, and only in a deployment started with
``X9_TESTING_CLOCKCONTROL=true`` and a bundled certificate. Point the suite at it with
``X9_CLOCK_URL`` (default ``http://127.0.0.1:8099``). The service's clock is the real one plus an
offset in milliseconds; it keeps ticking, it is never frozen.
"""
import datetime
import json
import urllib.error
import urllib.request

NOT_ENABLED = "test clock not enabled"


def parse_instant(text):
    """An RFC 3339 instant as an aware datetime (accepts the trailing ``Z``)."""
    return datetime.datetime.fromisoformat(text.replace("Z", "+00:00"))


def format_instant(at):
    """An aware datetime as RFC 3339 in UTC, kept to the millisecond, as the clock accepts it."""
    at = at.astimezone(datetime.timezone.utc)
    return at.strftime("%Y-%m-%dT%H:%M:%S.") + f"{at.microsecond // 1000:03d}Z"


class ClockUnavailable(RuntimeError):
    pass


class Clock:
    def __init__(self, url, timeout=10):
        self.url = url.rstrip("/") + "/test/clock"
        self.timeout = timeout

    def _call(self, method, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.url, data=data, method=method,
                                         headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return json.loads(response.read().decode())
        except urllib.error.HTTPError as e:
            raise ClockUnavailable(f"{method} {self.url}: {e.code} {e.read().decode(errors='replace')}")
        except Exception as e:                                    # noqa: BLE001 - not listening
            raise ClockUnavailable(f"{method} {self.url}: {e}")

    def available(self):
        try:
            self._call("GET")
            return True
        except ClockUnavailable:
            return False

    def state(self):
        """``{"now": datetime, "offsetMillis": int}`` as the service reports it."""
        body = self._call("GET")
        return {"now": parse_instant(body["now"]), "offsetMillis": int(body["offsetMillis"])}

    def now(self):
        """The service's business instant, as an aware datetime."""
        return self.state()["now"]

    def now_millis(self):
        return int(self.now().timestamp() * 1000)

    def offset_millis(self):
        return self.state()["offsetMillis"]

    def move_to(self, at):
        """Make the service's clock read ``at`` (an aware datetime or an RFC 3339 string)."""
        text = at if isinstance(at, str) else format_instant(at)
        return parse_instant(self._call("PUT", {"at": text})["now"])

    def move_by(self, millis):
        """Set the offset from real time (not a step from the current offset)."""
        return parse_instant(self._call("PUT", {"offsetMillis": int(millis)})["now"])

    def reset(self):
        """Back to real time."""
        return parse_instant(self._call("DELETE")["now"])
