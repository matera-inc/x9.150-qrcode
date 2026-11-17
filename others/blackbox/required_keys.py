# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""The tests a run requires, and every other one reported as informative (Story 9.1, S9.1-AC3).

Inert unless ``X9_REQUIRED_KEYS`` names a list file. No CI run sets it: since Story 10.8 the run
is judged on the whole suite, and the Go run's ``go-required.txt`` is gone.
It stays as a tool for a partial run, such as a build being ported one story at a time.

When it is set, the run's exit code is decided by the listed tests alone:

- a listed test that fails, errors, is an expected failure, is skipped, or never ran fails the run;
- every other test is informative: its outcome is counted and printed, and never fails the run;
- an entry that matches no collected test fails the run before any test runs, so a typo or a
  renamed test cannot make a list pass by requiring nothing.

The list file: one entry per line; ``#`` starts a comment; blank lines are ignored. An entry is

- a test key, ``X9-LIFE-502``: every test whose docstring starts with that key (a parametrised
  test, and a key shared by several tests, are all required); or
- a pytest node-id prefix starting with ``tests/``: ``tests/test_qr_code.py`` (a whole file), or
  ``tests/test_contract.py::test_a_contract_constraint_is_refused[create:`` (some cases of one test);
- either of those preceded by ``!``: excluded from what the other entries require
  (``tests/test_qr_code.py`` then ``!X9-DEC-016``). An exclusion must also match a collected test.

pytest's own exit codes for a run that could not happen (interrupted, internal error, usage error,
no tests collected) are kept as they are.
"""
import os
import re

import pytest

ENV = "X9_REQUIRED_KEYS"
KEY = re.compile(r"X9-[A-Z0-9]+-\d+")
_KEY_ENTRY = re.compile(r"^X9-[A-Z0-9]+-\d+$")

# The outcomes a test can end in, in the order they are printed.
OUTCOMES = ("passed", "failed", "error", "skipped", "xfailed", "xpassed", "not run")
# What a listed test may end in without failing the run.
ACCEPTED = ("passed", "xpassed")


def parse(text):
    """``(includes, excludes)``: the entries of a list file, in order, without ``!``. A malformed
    entry raises ``ValueError`` naming its line."""
    includes, excludes = [], []
    for number, line in enumerate(text.splitlines(), start=1):
        entry = line.split("#", 1)[0].strip()
        if not entry:
            continue
        target = excludes if entry.startswith("!") else includes
        entry = entry.lstrip("!").strip()
        if not (_KEY_ENTRY.match(entry) or entry.startswith("tests/")):
            raise ValueError(f"line {number}: '{line.strip()}' is neither a test key (X9-AREA-NNN) "
                             f"nor a node-id prefix starting with tests/")
        target.append(entry)
    return includes, excludes


def key_of(docstring):
    """The test key a docstring starts with, or ``None``."""
    match = KEY.match((docstring or "").strip())
    return match.group(0) if match else None


def matches(entry, nodeid, key):
    if entry.startswith("tests/"):
        return nodeid.startswith(entry)
    return key == entry


def select(includes, excludes, tests):
    """``(required node ids, entries that match nothing)`` for ``tests``, a list of
    ``(nodeid, key)``."""
    unmatched = [entry for entry in includes + excludes
                 if not any(matches(entry, nodeid, key) for nodeid, key in tests)]
    required = {nodeid for nodeid, key in tests
                if any(matches(entry, nodeid, key) for entry in includes)
                and not any(matches(entry, nodeid, key) for entry in excludes)}
    return required, unmatched


def outcome_of(reports):
    """One outcome from a test's setup, call and teardown reports (``pytest.TestReport``)."""
    if not reports:
        return "not run"
    for report in reports:
        if report.failed:
            return "failed" if report.when == "call" else "error"
    for report in reports:
        if report.skipped:
            if hasattr(report, "wasxfail"):
                return "xfailed"
            return "skipped"
    call = next((r for r in reports if r.when == "call"), None)
    if call is not None and hasattr(call, "wasxfail"):
        return "xpassed"
    return "passed"


def verdict(outcomes, required):
    """``(required counts, informative counts, listed tests that fail the run)`` from
    ``{nodeid: outcome}``."""
    counts = {"required": dict.fromkeys(OUTCOMES, 0), "informative": dict.fromkeys(OUTCOMES, 0)}
    failing = []
    for nodeid, outcome in sorted(outcomes.items()):
        side = "required" if nodeid in required else "informative"
        counts[side][outcome] += 1
        if side == "required" and outcome not in ACCEPTED:
            failing.append((nodeid, outcome))
    return counts["required"], counts["informative"], failing


def _format(counts):
    return ", ".join(f"{n} {name}" for name, n in counts.items() if n) or "none"


class RequiredKeys:

    def __init__(self, path):
        self.path = path
        with open(path, encoding="utf-8") as handle:
            try:
                self.includes, self.excludes = parse(handle.read())
            except ValueError as e:
                raise pytest.UsageError(f"{path}: {e}") from None
        self.required = set()
        self.items = {}
        self.reports = {}

    @pytest.hookimpl(tryfirst=True)
    def pytest_collection_modifyitems(self, items):
        # tryfirst: judged on everything collected, before -k or -m deselect anything.
        tests = [(item.nodeid, key_of(getattr(getattr(item, "function", None), "__doc__", None)))
                 for item in items]
        self.required, unmatched = select(self.includes, self.excludes, tests)
        if unmatched:
            raise pytest.UsageError(
                f"{self.path}: these entries match no collected test (a typo, or a renamed or "
                f"deleted test): {', '.join(unmatched)}")

    def pytest_collection_finish(self, session):
        self.items = {item.nodeid: item for item in session.items}

    def pytest_runtest_logreport(self, report):
        self.reports.setdefault(report.nodeid, []).append(report)

    @pytest.hookimpl(trylast=True)
    def pytest_sessionfinish(self, session, exitstatus):
        if exitstatus not in (pytest.ExitCode.OK, pytest.ExitCode.TESTS_FAILED):
            return
        self.result = verdict(self._outcomes(), self.required & set(self.items))
        session.exitstatus = pytest.ExitCode.TESTS_FAILED if self.result[2] else pytest.ExitCode.OK

    def _outcomes(self):
        return {nodeid: outcome_of(self.reports.get(nodeid, []))
                for nodeid in self.items}

    def pytest_terminal_summary(self, terminalreporter):
        result = getattr(self, "result", None)
        if result is None:
            return
        required, informative, failing = result
        write = terminalreporter.write_line
        terminalreporter.section(f"required keys ({os.path.basename(self.path)})")
        write(f"{len(self.includes)} entries, {len(self.excludes)} exclusions, "
              f"{sum(required.values())} tests required")
        write(f"required:    {_format(required)}")
        write(f"informative: {_format(informative)} (never fail this run)")
        for nodeid, outcome in failing:
            write(f"REQUIRED {outcome.upper()}  {nodeid}")
        write("the listed tests pass" if not failing
              else f"{len(failing)} listed tests did not pass: this run fails")


def pytest_configure(config):
    path = os.environ.get(ENV, "").strip()
    if path:
        config.pluginmanager.register(RequiredKeys(path), "x9-required-keys")
