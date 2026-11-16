# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
"""Tests of the rule-coverage check (Story 4.6).

    python3 -m unittest discover -s docs/check -v

Each test builds a small repository in a temporary directory: the stories, one ADR per expected
number, INTERPRETATION with two entries, two test files, and a coverage table. A case then breaks
one thing and asserts the check names it.

The criterion IDs of the made-up stories are spelled in pieces (`"S7.1-" + "AC1"`), so that this
file, which the check also scans, cites only IDs that exist.
"""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_rule_coverage as crc  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]

AC1 = "S7.1-" + "AC1"
AC2 = "S7.1-" + "AC2"
UNKNOWN = "S9.9-" + "AC9"

STORIES = f"""# Stories

**{AC1}.** **Given** a thing **Then** it holds.

**{AC2}. With a title.** **Then** it holds too.

Mentioning {UNKNOWN} in prose does not define it.
"""

JAVA_TEST = f"""class ThingTest {{
    /**
     * <b>X9-LIFE-001</b> — a thing holds. Proves {AC1}.
     */
    @Test
    void aThingHolds() {{ }}

    /** <b>X9-LIFE-002</b> — another thing holds. */
    @Test
    void anotherThingHolds() {{ }}
}}
"""

PYTHON_TEST = f'''def test_map_010_first(api):
    """X9-MAP-010 — the first row of the map. {AC2}."""


def test_named_by_function(api):
    """X9-LIFE-003 — a case a row names by file and function."""
'''

MAP_ADR = """# ADR-0035

| Key | Row | Tests | Java |
|---|---|---|---|
| X9-MAP-010 | status-update | `test_map_010_first` | agrees |
"""

HEADER = "| Rule | What it says | Tests | Proves |\n|---|---|---|---|\n"


def rows():
    """A coverage table with one row per source the check expects, all valid."""
    out = {
        "R-SM-01": f"| `R-SM-01` | A thing holds. | `X9-LIFE-001` | `{AC1}` |",
        "R-EP-01": "| `R-EP-01` | An endpoint answers. | `X9-LIFE-002` | — |",
        "R-I1-01": "| `R-I1-01` | A reading. | `test_things.py::test_named_by_function` | — |",
        "R-I2-01": "| `R-I2-01` | Another reading. | `ThingTest.java::aThingHolds` | — |",
        "R-MAP-010": f"| `R-MAP-010` | The first row. | `X9-MAP-010` | `{AC2}` |",
    }
    for number in crc.ADRS:
        key = f"R-ADR{number:04d}-01"
        out[key] = f"| `{key}` | A decision. | `X9-LIFE-001`, `X9-LIFE-002` | — |"
    return out


def coverage(table, gaps=()):
    text = "# Rule coverage\n\n## Gaps\n\n| Rule | Why | Recommendation |\n|---|---|---|\n"
    text += "".join(line + "\n" for line in gaps)
    text += "\n## Rules\n\n" + HEADER + "".join(line + "\n" for line in table.values())
    return text


class Repo:
    def __init__(self, table=None, gaps=(), extra=None):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        files = {
            crc.STORIES: STORIES,
            crc.COVERAGE: coverage(rows() if table is None else table, gaps),
            crc.INTERPRETATION: "# Reading\n\n## I-1 — one\n\n## I-2 — two\n",
            "x9-qrcode-domain/src/test/java/ThingTest.java": JAVA_TEST,
            "others/blackbox/tests/test_things.py": PYTHON_TEST,
            f"{crc.ADR_DIR}/0035-the-map.md": MAP_ADR,
        }
        for number in crc.ADRS:
            files[f"{crc.ADR_DIR}/{number:04d}-a-decision.md"] = f"# ADR-{number:04d}\n"
        files.update(extra or {})
        for name, text in files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")

    def check(self):
        return [str(v) for v in crc.check(self.root)]

    def close(self):
        self.tmp.cleanup()


def run(**kwargs):
    repo = Repo(**kwargs)
    try:
        return repo.check()
    finally:
        repo.close()


def changed(key, line):
    table = rows()
    table[key] = line
    return table


class TheRealRepository(unittest.TestCase):
    def test_the_coverage_table_in_this_repository_passes(self):
        self.assertEqual([], [str(v) for v in crc.check(REPO_ROOT)])

    def test_every_gap_in_this_repository_has_a_reason_and_a_recommendation(self):
        text = (REPO_ROOT / crc.COVERAGE).read_text(encoding="utf-8")
        rules, gaps, problems = crc.read_coverage(text)
        self.assertEqual([], problems)
        self.assertEqual(sorted(r.key for r in rules if r.gap), sorted(g.key for g in gaps))
        for gap in gaps:
            self.assertTrue(gap.reason and gap.recommendation, gap.key)

    def test_the_command_line_exits_zero_and_counts_the_rules(self):
        self.assertEqual(0, crc.main(["--root", str(REPO_ROOT)]))


class AConformingTable(unittest.TestCase):
    def test_it_passes(self):
        self.assertEqual([], run())

    def test_a_test_is_named_by_key_or_by_file_and_function(self):
        repo = Repo()
        try:
            tests = crc.read_tests(repo.root)
            self.assertEqual(["x9-qrcode-domain/src/test/java/ThingTest.java"],
                             crc.files_of("X9-LIFE-001", tests))
            self.assertEqual(["others/blackbox/tests/test_things.py"],
                             crc.files_of("test_things.py::test_named_by_function", tests))
            self.assertEqual([], crc.files_of("test_things.py::test_that_is_not_there", tests))
            self.assertEqual([], crc.files_of("X9-LIFE-999", tests))
        finally:
            repo.close()


class S46AC2ATestCitesAnUnknownCriterion(unittest.TestCase):
    """S4.6-AC2: the check fails when a test cites an unknown criterion ID."""

    def test_an_unknown_criterion_in_a_black_box_test_fails_with_file_and_line(self):
        cited = PYTHON_TEST + f'\n\ndef test_more(api):\n    """X9-LIFE-004 — more. Proves {UNKNOWN}."""\n'
        out = run(extra={"others/blackbox/tests/test_things.py": cited})
        self.assertEqual(1, len(out), out)
        self.assertIn("others/blackbox/tests/test_things.py: line 10", out[0])
        self.assertIn(UNKNOWN, out[0])

    def test_an_unknown_criterion_in_a_junit_test_fails(self):
        cited = JAVA_TEST.replace("another thing holds.", f"another thing holds ({UNKNOWN}).")
        out = run(extra={"x9-qrcode-domain/src/test/java/ThingTest.java": cited})
        self.assertEqual(1, len(out), out)
        self.assertIn("ThingTest.java", out[0])

    def test_an_unknown_criterion_in_a_shell_or_go_test_fails(self):
        out = run(extra={"db/test/roles-test.sh": f"# {UNKNOWN}\n",
                         "cmd/x9-qrcode/main_test.go": f"// {UNKNOWN}\n"})
        self.assertEqual(2, len(out), out)

    def test_a_criterion_with_a_title_is_defined(self):
        cited = PYTHON_TEST + f"\n# also {AC2} and {AC1}\n"
        self.assertEqual([], run(extra={"others/blackbox/tests/test_things.py": cited}))


class S46AC2ARuleRowHasNoTest(unittest.TestCase):
    """S4.6-AC2: the check fails when a rule row has no test."""

    def test_an_empty_tests_cell_fails(self):
        out = run(table=changed("R-SM-01", "| `R-SM-01` | A thing holds. |  | — |"))
        self.assertEqual(1, len(out), out)
        self.assertIn("R-SM-01", out[0])
        self.assertIn("has no test", out[0])

    def test_a_gap_that_is_not_listed_fails(self):
        out = run(table=changed("R-SM-01", "| `R-SM-01` | A thing holds. | GAP | — |"))
        self.assertEqual(1, len(out), out)
        self.assertIn("not in the Gaps table", out[0])

    def test_a_listed_gap_passes(self):
        out = run(table=changed("R-SM-01", "| `R-SM-01` | A thing holds. | GAP | — |"),
                  gaps=["| `R-SM-01` | Nobody wrote it. | Write the test. |"])
        self.assertEqual([], out)

    def test_a_listed_gap_without_a_reason_fails(self):
        out = run(table=changed("R-SM-01", "| `R-SM-01` | A thing holds. | GAP | — |"),
                  gaps=["| `R-SM-01` |  | Write the test. |"])
        self.assertEqual(1, len(out), out)
        self.assertIn("without a reason", out[0])

    def test_a_listed_gap_that_now_has_a_test_fails(self):
        out = run(gaps=["| `R-SM-01` | Nobody wrote it. | Write the test. |"])
        self.assertEqual(1, len(out), out)
        self.assertIn("now has a test", out[0])

    def test_a_listed_gap_that_is_not_a_rule_fails(self):
        out = run(gaps=["| `R-SM-77` | Nobody wrote it. | Write the test. |"])
        self.assertEqual(1, len(out), out)
        self.assertIn("is not a rule row", out[0])

    def test_a_gap_proves_nothing(self):
        out = run(table=changed("R-SM-01", f"| `R-SM-01` | A thing holds. | GAP | `{AC1}` |"),
                  gaps=["| `R-SM-01` | Nobody wrote it. | Write the test. |"])
        self.assertEqual(1, len(out), out)
        self.assertIn("proves no criterion", out[0])


class ARuleForSomethingNeverBuilt(unittest.TestCase):
    """A rule decided by an ADR and never built is marked NOT BUILT (ADR-nnnn), not a gap."""

    def test_a_not_built_rule_naming_its_adr_passes(self):
        out = run(table=changed("R-SM-01", "| `R-SM-01` | A thing holds. | NOT BUILT (ADR-0006) | — |"))
        self.assertEqual([], out)

    def test_it_must_name_an_adr_that_exists(self):
        out = run(table=changed("R-SM-01", "| `R-SM-01` | A thing holds. | NOT BUILT (ADR-0999) | — |"))
        self.assertEqual(1, len(out), out)
        self.assertIn("NOT BUILT under ADR-0999", out[0])

    def test_without_an_adr_it_is_not_a_test_name_and_fails(self):
        out = run(table=changed("R-SM-01", "| `R-SM-01` | A thing holds. | NOT BUILT | — |"))
        self.assertEqual(1, len(out), out)
        self.assertIn("has no test", out[0])

    def test_it_proves_nothing(self):
        out = run(table=changed("R-SM-01",
                                f"| `R-SM-01` | A thing holds. | NOT BUILT (ADR-0006) | `{AC1}` |"))
        self.assertEqual(1, len(out), out)
        self.assertIn("proves no criterion", out[0])

    def test_it_is_not_also_a_gap(self):
        out = run(table=changed("R-SM-01", "| `R-SM-01` | A thing holds. | NOT BUILT (ADR-0006) | — |"),
                  gaps=["| `R-SM-01` | Nobody built it. | Build it. |"])
        self.assertEqual(1, len(out), out)
        self.assertIn("marked NOT BUILT", out[0])

class EveryTestCarriesAKey(unittest.TestCase):
    """A black-box case or a JUnit test without an X9 key fails the check (docs/TEST-INDEX.md)."""

    def test_a_black_box_case_without_a_key_fails(self):
        unkeyed = PYTHON_TEST + '\n\nclass TestMore:\n    def test_unkeyed(self, api):\n' \
                                '        """A case nobody keyed."""\n'
        out = run(extra={"others/blackbox/tests/test_things.py": unkeyed})
        self.assertEqual(1, len(out), out)
        self.assertIn("others/blackbox/tests/test_things.py: line 10, test_unkeyed", out[0])
        self.assertIn("has no key", out[0])

    def test_a_black_box_case_without_a_docstring_fails(self):
        out = run(extra={"others/blackbox/tests/test_things.py":
                         PYTHON_TEST + "\n\ndef test_bare(api):\n    pass\n"})
        self.assertEqual(1, len(out), out)
        self.assertIn("test_bare", out[0])

    def test_a_key_mentioned_later_in_the_docstring_is_not_a_key(self):
        out = run(extra={"others/blackbox/tests/test_things.py":
                         PYTHON_TEST + '\n\ndef test_late(api):\n    """As X9-LIFE-001."""\n'})
        self.assertEqual(1, len(out), out)
        self.assertIn("test_late", out[0])

    def test_a_helper_outside_tests_needs_no_key(self):
        out = run(extra={"others/blackbox/x9client/helpers.py": "def test_helper():\n    pass\n"})
        self.assertEqual([], out)

    def test_a_junit_test_without_a_key_fails(self):
        unkeyed = JAVA_TEST.rstrip().rstrip("}") + """
    /** No key here. */
    @ParameterizedTest
    @CsvSource({
        "a, b"
    })
    void anUnkeyedOne(String a, String b) { }

    @Test
    void aBareOne() { }
}
"""
        out = run(extra={"x9-qrcode-domain/src/test/java/ThingTest.java": unkeyed})
        self.assertEqual(2, len(out), out)
        self.assertIn("anUnkeyedOne", out[0])
        self.assertIn("aBareOne", out[1])
        self.assertIn("has no key", out[1])

    def test_the_class_javadoc_is_not_the_first_tests_javadoc(self):
        java = """/** <b>X9-LIFE-005</b> is only mentioned in the class javadoc. */
class OtherTest {
    @Test
    void first() { }
}
"""
        out = run(extra={"x9-qrcode-domain/src/test/java/OtherTest.java": java})
        self.assertEqual(1, len(out), out)
        self.assertIn("first", out[0])


class S46AC2ARuleRowNamesATestThatDoesNotExist(unittest.TestCase):
    def test_an_unknown_key_fails(self):
        out = run(table=changed("R-SM-01", "| `R-SM-01` | A thing holds. | `X9-LIFE-999` | — |"))
        self.assertEqual(1, len(out), out)
        self.assertIn("X9-LIFE-999, which is not a test that exists", out[0])

    def test_one_unknown_key_among_known_ones_fails(self):
        out = run(table=changed(
            "R-SM-01", "| `R-SM-01` | A thing holds. | `X9-LIFE-001`, `X9-LIFE-999` | — |"))
        self.assertEqual(1, len(out), out)

    def test_an_unknown_function_fails(self):
        out = run(table=changed(
            "R-I1-01", "| `R-I1-01` | A reading. | `test_things.py::test_gone` | — |"))
        self.assertEqual(1, len(out), out)
        self.assertIn("test_things.py::test_gone", out[0])

    def test_a_key_that_is_only_mentioned_not_defined_fails(self):
        mentioned = PYTHON_TEST + "\n# see X9-LIFE-777 for the other half\n"
        out = run(table=changed("R-SM-01", "| `R-SM-01` | A thing holds. | `X9-LIFE-777` | — |"),
                  extra={"others/blackbox/tests/test_things.py": mentioned})
        self.assertEqual(1, len(out), out)


class S46AC1TheCriteriaARuleProves(unittest.TestCase):
    """S4.6-AC1: each rule maps to the acceptance-criterion IDs its tests prove."""

    def test_an_undefined_criterion_fails(self):
        out = run(table=changed(
            "R-SM-01", f"| `R-SM-01` | A thing holds. | `X9-LIFE-001` | `{UNKNOWN}` |"))
        self.assertEqual(1, len(out), out)
        self.assertIn(f"proves {UNKNOWN}", out[0])

    def test_a_criterion_none_of_the_rows_test_files_cites_fails(self):
        out = run(table=changed(
            "R-SM-01", f"| `R-SM-01` | A thing holds. | `X9-LIFE-001` | `{AC2}` |"))
        self.assertEqual(1, len(out), out)
        self.assertIn("none of its tests' files cites", out[0])

    def test_an_empty_proves_cell_fails(self):
        out = run(table=changed("R-SM-01", "| `R-SM-01` | A thing holds. | `X9-LIFE-001` |  |"))
        self.assertEqual(1, len(out), out)


class S46AC1EverySourceHasItsRules(unittest.TestCase):
    def without(self, key):
        table = rows()
        del table[key]
        return table

    def test_state_machine_without_a_row_fails(self):
        out = run(table=self.without("R-SM-01"))
        self.assertEqual(1, len(out), out)
        self.assertIn("STATE-MACHINE.md", out[0])

    def test_endpoints_without_a_row_fails(self):
        out = run(table=self.without("R-EP-01"))
        self.assertEqual(1, len(out), out)
        self.assertIn("ENDPOINTS.md", out[0])

    def test_an_interpretation_entry_without_a_row_fails(self):
        out = run(table=self.without("R-I2-01"))
        self.assertEqual(1, len(out), out)
        self.assertIn("I-2", out[0])

    def test_an_adr_without_a_row_fails(self):
        out = run(table=self.without("R-ADR0012-01"))
        self.assertEqual(1, len(out), out)
        self.assertIn("0012-a-decision.md", out[0])

    def test_adr_0036_is_expected_with_its_amendments(self):
        out = run(table=self.without("R-ADR0036-01"))
        self.assertEqual(1, len(out), out)
        self.assertIn("0036", out[0])

    def test_a_row_of_the_transition_map_without_a_rule_fails(self):
        out = run(table=self.without("R-MAP-010"))
        self.assertEqual(1, len(out), out)
        self.assertIn("X9-MAP-010", out[0])

    def test_a_map_rule_must_name_the_test_of_its_row(self):
        out = run(table=changed(
            "R-MAP-010", "| `R-MAP-010` | The first row. | `X9-LIFE-001` | — |"))
        self.assertEqual(1, len(out), out)
        self.assertIn("does not name X9-MAP-010", out[0])


class TheTableItself(unittest.TestCase):
    def test_a_malformed_key_fails(self):
        table = rows()
        table["bad"] = "| `R-XX-01` | Nothing. | `X9-LIFE-001` | — |"
        out = run(table=table)
        self.assertEqual(1, len(out), out)
        self.assertIn("is not a rule key", out[0])

    def test_a_repeated_key_fails(self):
        table = rows()
        table["again"] = table["R-SM-01"]
        out = run(table=table)
        self.assertEqual(1, len(out), out)
        self.assertIn("again on line", out[0])

    def test_a_row_with_a_stray_pipe_fails_and_an_escaped_one_passes(self):
        stray = run(table=changed(
            "R-SM-01", "| `R-SM-01` | A thing | holds. | `X9-LIFE-001` | — |"))
        self.assertTrue(any("four cells" in line for line in stray), stray)
        escaped = run(table=changed(
            "R-SM-01", "| `R-SM-01` | A thing \\| holds. | `X9-LIFE-001` | — |"))
        self.assertEqual([], escaped)

    def test_a_rule_without_words_fails(self):
        out = run(table=changed("R-SM-01", "| `R-SM-01` |  | `X9-LIFE-001` | — |"))
        self.assertEqual(1, len(out), out)
        self.assertIn("does not say what the rule is", out[0])


GO_TEST = """package thing

// X9-LIFE-005 (a known difference): a Go test keyed in its doc comment.
//
// It also mentions X9-LIFE-006, which is a reference, not a definition.
func TestKeyedInItsComment(t *testing.T) {}

// X9-LIFE-007: a subtest run by a parent test is keyed the same way.
func testASubtest(t *testing.T, e *env) {}

// A comment that cites X9-LIFE-008 later on defines nothing.
func TestNotKeyed(t *testing.T) {}
"""


class S112GoTestsAreKeyedInTheirComments(unittest.TestCase):
    """S11.2: a Go test's key is the first word of its doc comment; the tolerated list names tests
    that exist."""

    def test_a_go_test_is_named_by_the_key_its_doc_comment_starts_with(self):
        repo = Repo(extra={"internal/thing/thing_test.go": GO_TEST})
        try:
            tests = crc.read_tests(repo.root)
            self.assertEqual(["internal/thing/thing_test.go"], crc.files_of("X9-LIFE-005", tests))
            self.assertEqual(["internal/thing/thing_test.go"], crc.files_of("X9-LIFE-007", tests))
            self.assertEqual([], crc.files_of("X9-LIFE-006", tests), "a later mention is a reference")
            self.assertEqual([], crc.files_of("X9-LIFE-008", tests), "a key not first defines nothing")
        finally:
            repo.close()

    def test_a_rule_row_may_name_a_go_test_by_its_key(self):
        out = run(table=changed("R-EP-01", "| `R-EP-01` | An endpoint answers. | `X9-LIFE-005` | — |"),
                  extra={"internal/thing/thing_test.go": GO_TEST})
        self.assertEqual([], out)

    def test_a_tolerated_difference_naming_a_key_no_test_defines_fails(self):
        tolerated = "## 1. Whitespace\n\n**Keys:** `X9-LIFE-005`, `X9-LIFE-999`\n"
        out = run(extra={"internal/thing/thing_test.go": GO_TEST, crc.TOLERATED: tolerated})
        self.assertEqual(1, len(out), out)
        self.assertIn("TOLERATED-DIFFERENCES.md: line 3", out[0])
        self.assertIn("X9-LIFE-999", out[0])


if __name__ == "__main__":
    unittest.main()
