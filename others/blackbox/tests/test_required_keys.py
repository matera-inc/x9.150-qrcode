# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""The required-keys list decides the Go run, and only it (Story 9.1, S9.1-AC3). No deployment.

A list that let a listed failure through, or that failed on an informative one, would make "a Go
story is done when its tests pass" mean nothing, so the mechanism gets its own known answers.
"""
import os
import subprocess
import sys
import textwrap
import types

import pytest

import required_keys
from required_keys import outcome_of, parse, select, verdict

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _report(when, outcome, xfail=False):
    report = types.SimpleNamespace(when=when, failed=outcome == "failed",
                                   skipped=outcome == "skipped", passed=outcome == "passed")
    if xfail:
        report.wasxfail = "reason"
    return report


def test_the_list_reads_keys_prefixes_exclusions_and_comments():
    """X9-SUITE-513 — S9.1-AC3: the list file holds test keys and node-id prefixes, one per line,
    ``!`` excludes, ``#`` comments; a line that is neither is refused, naming its line.

    Source: Ours (Story 9.1).
    Why: Every story of Epics 9 and 10 adds to this file; an entry silently read as nothing would
      make a story "done" without its tests.
    """
    includes, excludes = parse(textwrap.dedent("""\
        # a comment
        X9-LIFE-502
        tests/test_qr_code.py   # a whole file
        !X9-DEC-016

        tests/test_contract.py::test_a_contract_constraint_is_refused[create:
        """))
    assert includes == ["X9-LIFE-502", "tests/test_qr_code.py",
                        "tests/test_contract.py::test_a_contract_constraint_is_refused[create:"]
    assert excludes == ["X9-DEC-016"]
    assert parse("# only comments\n\n") == ([], [])
    with pytest.raises(ValueError, match="line 2"):
        parse("X9-LIFE-502\ntest_qr_code.py\n")


def test_listed_tests_must_pass_and_the_others_are_informative():
    """X9-SUITE-514 — S9.1-AC3: a listed test that fails, errors, is skipped, is an expected
    failure or never ran fails the run; a build-marker skip does not; an unlisted test never does,
    and every outcome is counted on its side; an entry matching nothing is reported.

    Source: Ours (Story 9.1).
    Why: The Go build passes few tests at first; only the listed ones may decide the job, and all of
      them must.
    """
    tests = [("tests/test_a.py::test_one", "X9-LIFE-502"),
             ("tests/test_a.py::test_two[x]", "X9-LIFE-503"),
             ("tests/test_a.py::test_two[y]", "X9-LIFE-503"),
             ("tests/test_b.py::test_three", "X9-DEC-016")]
    required, unmatched = select(["X9-LIFE-503", "tests/test_b.py", "X9-NONE-001"],
                                 ["X9-DEC-016"], tests)
    assert required == {"tests/test_a.py::test_two[x]", "tests/test_a.py::test_two[y]"}
    assert unmatched == ["X9-NONE-001"]

    assert outcome_of([]) == "not run"
    assert outcome_of([_report("setup", "passed"), _report("call", "passed"),
                       _report("teardown", "passed")]) == "passed"
    assert outcome_of([_report("setup", "failed")]) == "error"
    assert outcome_of([_report("setup", "passed"), _report("call", "passed"),
                       _report("teardown", "failed")]) == "error"
    assert outcome_of([_report("setup", "passed"), _report("call", "failed")]) == "failed"
    assert outcome_of([_report("setup", "skipped")]) == "skipped"
    assert outcome_of([_report("setup", "passed"), _report("call", "skipped", xfail=True)]) == "xfailed"
    assert outcome_of([_report("setup", "passed"), _report("call", "passed", xfail=True)]) == "xpassed"

    outcomes = {f"r{n}": outcome for n, outcome in enumerate(required_keys.OUTCOMES)}
    outcomes.update({f"i{n}": outcome for n, outcome in enumerate(required_keys.OUTCOMES)})
    req, info, failing = verdict(outcomes, {f"r{n}" for n in range(len(required_keys.OUTCOMES))})
    assert req == info == dict.fromkeys(required_keys.OUTCOMES, 1)
    assert sorted(outcome for _, outcome in failing) == sorted(
        ["failed", "error", "skipped", "xfailed", "not run"])
    assert all(nodeid.startswith("r") for nodeid, _ in failing)


@pytest.mark.parametrize("listed, exit_code, says", [
    ("X9-DEMO-001\n", 0, "the listed tests pass"),
    ("X9-DEMO-002\n", 1, "REQUIRED FAILED"),
    ("tests/test_demo.py::test_skipped\n", 1, "REQUIRED SKIPPED"),
    ("", 0, "0 tests required"),
    ("X9-DEMO-404\n", 4, "match no collected test"),
], ids=["listed-pass", "listed-fail", "listed-skip", "empty", "stale-entry"])
def test_a_run_exits_on_the_listed_tests_alone(tmp_path, listed, exit_code, says):
    """X9-SUITE-515 — S9.1-AC3: a whole pytest run under ``X9_REQUIRED_KEYS`` exits 0 when every
    listed test passes although an unlisted one fails, 1 when a listed one fails or is skipped, and
    refuses an entry that matches no test; it prints the counts of each outcome.

    Source: Ours (Story 9.1).
    Why: The CI job's verdict is pytest's exit code; the functions above are only right if the
      plugin really replaces it.
    """
    (tmp_path / "tests").mkdir()
    (tmp_path / "conftest.py").write_text(textwrap.dedent("""\
        pytest_plugins = ["required_keys"]
        """))
    (tmp_path / "pytest.ini").write_text("[pytest]\n")
    (tmp_path / "tests" / "test_demo.py").write_text(textwrap.dedent('''\
        import pytest
        def test_passes():
            """X9-DEMO-001 — passes."""
        def test_fails():
            """X9-DEMO-002 — fails, and is informative unless listed."""
            assert False
        def test_skipped():
            """X9-DEMO-003 — skipped."""
            pytest.skip("no capability")
        '''))
    (tmp_path / "required.txt").write_text(listed)
    env = dict(os.environ, X9_REQUIRED_KEYS=str(tmp_path / "required.txt"),
               PYTHONPATH=HERE, PYTHONDONTWRITEBYTECODE="1")
    run = subprocess.run([sys.executable, "-m", "pytest", "-q", "-p", "no:cacheprovider",
                          "--rootdir", str(tmp_path), str(tmp_path / "tests")],
                         cwd=tmp_path, env=env, capture_output=True, text=True, timeout=120)
    output = run.stdout + run.stderr
    assert run.returncode == exit_code, output
    assert says in output, output
    if exit_code != 4:
        assert "informative:" in output and "1 failed" in output, output
