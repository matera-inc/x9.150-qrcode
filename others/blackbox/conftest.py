# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""
Fixtures for the black-box suite. The protocol lives in ``x9client``; this wires it to pytest.

    make blackbox                                   # against http://localhost:8080
    X9_BASE_URL=http://localhost:8070 pytest others/blackbox

Environment:

- ``X9_BASE_URL``: the deployment's API (default ``http://localhost:8080``).
- ``X9_CLOCK_URL``: its test-clock listener (default ``http://127.0.0.1:8099``). Time tests skip
  with "test clock not enabled" when nothing answers there.
- ``X9_DATABASE_URL``: optional read-only database connection. Database checks skip, with the
  reason, when it is unset or its driver is not installed.
- ``X9_REQUIRE_ALL=1``: turn every skip for a missing capability into a failure. CI sets it, so a
  suite that could not run is a red job, never a green one.
"""
import os

import pytest

from x9client import clock as clock_module
from x9client import db as db_module
from x9client.api import ServiceSigner, X9Api
from x9client.events import EventReader

REQUIRE_ALL = os.environ.get("X9_REQUIRE_ALL", "") not in ("", "0", "false")


def unavailable(reason):
    """Skip, or fail when the run requires every capability (``X9_REQUIRE_ALL``)."""
    if REQUIRE_ALL:
        pytest.fail(f"{reason} (X9_REQUIRE_ALL is set, so this is a failure, not a skip)")
    pytest.skip(reason)


@pytest.fixture(scope="session")
def api():
    base_url = os.environ.get("X9_BASE_URL", "http://localhost:8080")
    client = X9Api(base_url)

    status, _ = client.get("/actuator/health")
    if status != 200:
        unavailable(f"no deployment answering at {base_url} (health returned {status})")

    client.signer = ServiceSigner(client)
    return client


@pytest.fixture(scope="session")
def events(api):
    """A reader that starts at the end of the stream as it stood when the session began."""
    reader = EventReader(api)
    reader.mark()
    return reader


@pytest.fixture(scope="session")
def _clock():
    return clock_module.Clock(os.environ.get("X9_CLOCK_URL", "http://127.0.0.1:8099"))


@pytest.fixture
def clock(_clock):
    """The test clock, reset before and after the test. Skips when the deployment has none."""
    if not _clock.available():
        unavailable(f"{clock_module.NOT_ENABLED} (nothing answers at {_clock.url})")
    _clock.reset()
    yield _clock
    _clock.reset()


@pytest.fixture(scope="session")
def db():
    inspector, reason = db_module.connect()
    if inspector is None:
        unavailable(f"database checks skipped: {reason}")
    return inspector
