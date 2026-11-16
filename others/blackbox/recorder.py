# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Tells ``x9client.recording`` which test is running (Story 11.1, S11.1-AC1).

Inert unless ``X9_RECORD_DIR`` is set. When it is:

- while a test runs (setup, call and teardown), its exchanges go to its node id;
- while a fixture wider than one test is built (``session``, ``module``, ``class`` scope), its
  exchanges go to ``<session>::<fixture>`` instead, so the trust probe and the other shared set-ups
  are compared on their own and never shift the exchanges of whichever test built them first;
- once a test is over, its outcome and build markers go to the test index.
"""
import pytest

from x9client import recording


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_protocol(item, nextitem):
    if not recording.enabled():
        yield
        return
    previous = recording.set_bucket(item.nodeid)
    try:
        yield
    finally:
        recording.set_bucket(previous)


@pytest.hookimpl(hookwrapper=True)
def pytest_fixture_setup(fixturedef, request):
    if not recording.enabled() or fixturedef.scope == "function":
        yield
        return
    # A module- or class-scoped fixture is built once per module or class: name that too.
    where = "" if fixturedef.scope == "session" else f":{getattr(request.node, 'nodeid', '')}"
    previous = recording.set_bucket(
        f"<{fixturedef.scope}{where}>::{fixturedef.argname}::{recording.worker()}")
    try:
        yield
    finally:
        recording.set_bucket(previous)


_reports = {}


def pytest_runtest_logreport(report):
    if not recording.enabled():
        return
    _reports.setdefault(report.nodeid, []).append(report)


def pytest_runtest_logfinish(nodeid, location):
    if not recording.enabled():
        return
    reports = _reports.pop(nodeid, [])
    outcome = "not run"
    for report in reports:
        if report.failed:
            outcome = "failed" if report.when == "call" else "error"
            break
    else:
        skipped = next((r for r in reports if r.skipped), None)
        call = next((r for r in reports if r.when == "call"), None)
        if skipped is not None:
            outcome = "xfailed" if hasattr(skipped, "wasxfail") else "skipped"
        elif call is not None:
            outcome = "xpassed" if hasattr(call, "wasxfail") else "passed"
    recording.record_outcome(nodeid, outcome, _markers.get(nodeid, []))


_markers = {}


def pytest_collection_modifyitems(items):
    if not recording.enabled():
        return
    for item in items:
        _markers[item.nodeid] = [name for name in ("go_only", "java_only", "serial")
                                 if item.get_closest_marker(name) is not None]
