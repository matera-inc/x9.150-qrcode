#!/usr/bin/env python3
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
"""Every documented rule points to a test, and every cited criterion exists (Story 4.6).

    python3 docs/check/check_rule_coverage.py [--root .]

It reads docs/RULE-COVERAGE.md, docs/rewrite/EPICS-AND-STORIES.md, docs/adr/ and the test sources,
and fails (exit 1), naming the file and the rule or line, when:

  S4.6-AC2  a test source cites an acceptance-criterion ID (`S4.2-AC1`) that
            EPICS-AND-STORIES.md does not define.
  S4.6-AC2  a rule row has no test: its Tests cell is empty, or it says GAP and the rule is not in
            the "Gaps" table with a reason and a recommendation.
  S4.6-AC2  a rule row names a test that does not exist: an `X9-AREA-NNN` key no test defines, or a
            `File::function` that is not in that test file.
  S4.6-AC1  a rule row's "Proves" cell names a criterion that is not defined, or that no file
            holding one of the row's tests cites.
  S4.6-AC1  a source has no rule row: STATE-MACHINE (`R-SM-`), ENDPOINTS (`R-EP-`), an entry of
            INTERPRETATION.md (`R-I7-`), an ADR from 0004 to 0021 or ADR-0036 (`R-ADR0012-`), or a
            key of ADR-0035's map (`X9-MAP-010` needs `R-MAP-010`, tested by `X9-MAP-010`).

It also fails on a malformed or repeated rule key, and on a "Gaps" entry whose rule now has a test
(remove the entry) or does not exist.

A rule for something that was decided but never built is marked `NOT BUILT (ADR-0006)` in its Tests
cell, naming the ADR that decided it: it has no test because there is nothing to observe, and it
proves no criterion. The ADR must exist, and the rule must not also be listed under "Gaps".

A test is named by its key (`X9-HOLD-104`, see docs/TEST-CONVENTIONS.md) or, for a test that has no
key yet, by `File::function` (`test_signatures.py::test_a_revoked_payer_certificate_is_refused`).
Standard library only.
"""

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

COVERAGE = "docs/RULE-COVERAGE.md"
STORIES = "docs/rewrite/EPICS-AND-STORIES.md"
INTERPRETATION = "official-spec/INTERPRETATION.md"
ADR_DIR = "docs/adr"
MAP_ADR = 35
ADRS = list(range(4, 22)) + [36]          # ADR-0035 is covered row by row, as R-MAP-NNN

# Where tests live. Criterion IDs are checked in all of them; test keys are defined in the first two.
JAVA_TESTS = "x9-qrcode-*/src/test/**/*.java"
BLACKBOX = "others/blackbox/**/*.py"
OTHER_TESTS = ("db/test/*", "db/**/test_*.py", "docs/**/test_*.py", "cmd/**/*_test.go",
               "internal/**/*_test.go")

CRITERION = re.compile(r"\bS\d+\.\d+-AC\d+[a-z]?\b")
CRITERION_DEFINED = re.compile(r"^\*\*(S\d+\.\d+-AC\d+[a-z]?)\.", re.M)
TEST_KEY = re.compile(r"X9-[A-Z0-9]+-\d{3}")
KEY_IN_JAVADOC = re.compile(r"<b>(X9-[A-Z0-9]+-\d{3})</b>")
KEY_IN_DOCSTRING = re.compile(r'"""\s*(X9-[A-Z0-9]+-\d{3})\b')
FUNCTION_REF = re.compile(r"^([\w.-]+\.(?:py|java|go|sh))::(\w+)$")
RULE_KEY = re.compile(r"^R-(SM|EP|I\d{1,2}|ADR\d{4}|MAP)-\d{2,3}$")
MAP_KEY = re.compile(r"^\| (X9-MAP-\d{3}) \|", re.M)
INTERPRETATION_ENTRY = re.compile(r"^## I-(\d+) ", re.M)
NO_CRITERION = "—"
GAP = "GAP"
NOT_BUILT = re.compile(r"^NOT BUILT \((ADR-\d{4})\)$")


@dataclass(frozen=True)
class Violation:
    file: str
    where: str
    message: str

    def __str__(self):
        where = f"{self.where}: " if self.where else ""
        return f"{self.file}: {where}{self.message}"


@dataclass
class Rule:
    key: str
    line: int
    text: str
    tests: list
    proves: list
    gap: bool
    not_built: str = ""                             # the ADR named by NOT BUILT (ADR-nnnn)


@dataclass
class Gap:
    key: str
    line: int
    reason: str
    recommendation: str


@dataclass
class Tests:
    """What the test sources define and cite."""
    keys: dict = field(default_factory=dict)        # X9 key -> set of files defining it
    files: dict = field(default_factory=dict)       # basename -> list of (path, text)
    cited: dict = field(default_factory=dict)       # path -> set of criterion IDs cited
    citations: list = field(default_factory=list)   # (path, line number, criterion ID)


def read_tests(root):
    tests = Tests()
    seen = set()
    for pattern, key_pattern in ((JAVA_TESTS, KEY_IN_JAVADOC), (BLACKBOX, KEY_IN_DOCSTRING),
                                 *((p, None) for p in OTHER_TESTS)):
        for path in sorted(root.glob(pattern)):
            if not path.is_file() or path in seen:
                continue
            seen.add(path)
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            rel = path.relative_to(root).as_posix()
            tests.files.setdefault(path.name, []).append((rel, text))
            tests.cited[rel] = set(CRITERION.findall(text))
            for number, line in enumerate(text.splitlines(), 1):
                for criterion in CRITERION.findall(line):
                    tests.citations.append((rel, number, criterion))
            if key_pattern:
                for key in key_pattern.findall(text):
                    tests.keys.setdefault(key, set()).add(rel)
    return tests


def cells(line):
    """The cells of one markdown table row; a literal pipe is written as a backslash and a pipe."""
    inner = line.strip().strip("|")
    return [c.strip().replace("\\|", "|") for c in re.split(r"(?<!\\)\|", inner)]


def names(cell):
    """The names in a cell: backticked, separated by commas or spaces."""
    return re.findall(r"`([^`]+)`", cell)


def read_coverage(text):
    """The rule rows and the gap entries of RULE-COVERAGE.md, plus what is malformed in it."""
    rules, gaps, problems = [], [], []
    section = ""
    for number, line in enumerate(text.splitlines(), 1):
        if line.startswith("## "):
            section = line[3:].strip()
            continue
        if not line.startswith("| `R-"):
            continue
        row = cells(line)
        key = row[0].strip("`")
        if not RULE_KEY.match(key):
            problems.append((number, key, "is not a rule key (R-SM-01, R-EP-01, R-I7-01, "
                                          "R-ADR0012-01, R-MAP-010)"))
            continue
        if section.lower().startswith("gaps"):
            if len(row) != 3:
                problems.append((number, key, "a gap row has three cells: rule, reason, recommendation"))
                continue
            gaps.append(Gap(key, number, row[1], row[2]))
            continue
        if len(row) != 4:
            problems.append((number, key, "a rule row has four cells: rule, what it says, tests, "
                                          "proves (write a literal pipe as \\|)"))
            continue
        tests_cell, proves_cell = row[2], row[3]
        not_built = NOT_BUILT.match(tests_cell)
        rules.append(Rule(key=key, line=number, text=row[1], tests=names(tests_cell),
                          proves=[] if proves_cell == NO_CRITERION else
                          (names(proves_cell) or [proves_cell]),
                          gap=tests_cell == GAP,
                          not_built=not_built.group(1) if not_built else ""))
    return rules, gaps, problems


def files_of(reference, tests):
    """The test files a reference names, or [] when it names no test that exists."""
    if TEST_KEY.fullmatch(reference):
        return sorted(tests.keys.get(reference, ()))
    match = FUNCTION_REF.match(reference)
    if not match:
        return []
    name, function = match.groups()
    defined = re.compile(r"\b" + re.escape(function) + r"\s*\(")
    return [path for path, text in tests.files.get(name, ()) if defined.search(text)]


def check(root):
    root = Path(root)
    out = []
    defined = set(CRITERION_DEFINED.findall((root / STORIES).read_text(encoding="utf-8")))
    tests = read_tests(root)

    # S4.6-AC2: a test cites a criterion that does not exist.
    for path, number, criterion in tests.citations:
        if criterion not in defined:
            out.append(Violation(path, f"line {number}",
                                 f"cites {criterion}, which {STORIES} does not define"))

    rules, gaps, problems = read_coverage((root / COVERAGE).read_text(encoding="utf-8"))
    for number, key, message in problems:
        out.append(Violation(COVERAGE, f"line {number}, {key}", message))

    by_key = {}
    for rule in rules:
        if rule.key in by_key:
            out.append(Violation(COVERAGE, rule.key, f"is on line {by_key[rule.key].line} and "
                                                     f"again on line {rule.line}"))
        by_key.setdefault(rule.key, rule)
    listed = {}
    for gap in gaps:
        if gap.key in listed:
            out.append(Violation(COVERAGE, gap.key, "is in the Gaps table twice"))
        listed.setdefault(gap.key, gap)

    adrs = {f"ADR-{p.name[:4]}" for p in (root / ADR_DIR).glob("[0-9][0-9][0-9][0-9]-*.md")}
    for rule in rules:
        if not rule.text:
            out.append(Violation(COVERAGE, rule.key, "does not say what the rule is"))
        if rule.not_built:
            # Decided, never built: nothing to observe, so no test, but the decision is named.
            if rule.not_built not in adrs:
                out.append(Violation(COVERAGE, rule.key, f"is NOT BUILT under {rule.not_built}, "
                                                         f"which is not in {ADR_DIR}"))
            if rule.proves:
                out.append(Violation(COVERAGE, rule.key, "is not built, so it proves no criterion: "
                                                         f"write {NO_CRITERION}"))
            continue
        if rule.gap:
            # S4.6-AC2: a rule with no test is allowed only when it is listed, with a reason.
            if rule.key not in listed:
                out.append(Violation(COVERAGE, rule.key, "has no test (GAP) and is not in the Gaps "
                                                         "table: add a test, or list it with a reason"))
            if rule.proves:
                out.append(Violation(COVERAGE, rule.key, "has no test, so it proves no criterion: "
                                                         f"write {NO_CRITERION}"))
            continue
        if not rule.tests:
            out.append(Violation(COVERAGE, rule.key, "has no test: name one, or write GAP and list "
                                                     "the rule in the Gaps table"))
            continue
        holding = set()
        for reference in rule.tests:
            found = files_of(reference, tests)
            if not found:
                out.append(Violation(COVERAGE, rule.key, f"names {reference}, which is not a test "
                                                         "that exists"))
            holding.update(found)
        for criterion in rule.proves:
            if criterion not in defined:
                out.append(Violation(COVERAGE, rule.key, f"proves {criterion}, which {STORIES} "
                                                         "does not define"))
            elif holding and not any(criterion in tests.cited[path] for path in holding):
                out.append(Violation(COVERAGE, rule.key, f"proves {criterion}, which none of its "
                                                         "tests' files cites"))

    for gap in gaps:
        rule = by_key.get(gap.key)
        if rule is None:
            out.append(Violation(COVERAGE, gap.key, "is in the Gaps table but is not a rule row"))
        elif rule.not_built:
            out.append(Violation(COVERAGE, gap.key, "is in the Gaps table but is marked NOT BUILT: "
                                                    "remove it from the table"))
        elif not rule.gap:
            out.append(Violation(COVERAGE, gap.key, "is in the Gaps table but now has a test: "
                                                    "remove it from the table"))
        if not gap.reason or not gap.recommendation:
            out.append(Violation(COVERAGE, gap.key, "is in the Gaps table without a reason and a "
                                                    "recommendation"))

    out.extend(uncovered_sources(root, by_key))
    return out


def uncovered_sources(root, by_key):
    """S4.6-AC1: every source has at least one rule, and every row of ADR-0035's map has its own."""
    out = []
    prefixes = {"STATE-MACHINE.md": "R-SM-", "ENDPOINTS.md": "R-EP-"}
    interpretation = (root / INTERPRETATION).read_text(encoding="utf-8")
    for entry in INTERPRETATION_ENTRY.findall(interpretation):
        prefixes[f"{INTERPRETATION} I-{entry}"] = f"R-I{entry}-"
    adrs = {int(p.name[:4]): p for p in (root / ADR_DIR).glob("[0-9][0-9][0-9][0-9]-*.md")}
    for number in ADRS:
        if number not in adrs:
            out.append(Violation(ADR_DIR, f"ADR-{number:04d}", "is not there, and this check expects it"))
            continue
        prefixes[adrs[number].relative_to(root).as_posix()] = f"R-ADR{number:04d}-"
    for source, prefix in prefixes.items():
        if not any(key.startswith(prefix) for key in by_key):
            out.append(Violation(COVERAGE, source, f"has no rule row (no key starts with {prefix})"))

    if MAP_ADR in adrs:
        for map_key in sorted(set(MAP_KEY.findall(adrs[MAP_ADR].read_text(encoding="utf-8")))):
            rule_key = "R-MAP-" + map_key[-3:]
            rule = by_key.get(rule_key)
            if rule is None:
                out.append(Violation(COVERAGE, map_key, f"is a row of ADR-0035's map with no rule "
                                                        f"row ({rule_key})"))
            elif not rule.gap and map_key not in rule.tests:
                out.append(Violation(COVERAGE, rule_key, f"does not name {map_key}, the test of "
                                                         "that row of the map"))
    else:
        out.append(Violation(ADR_DIR, "ADR-0035", "is not there, and this check expects it"))
    return out


def summary(root):
    rules, gaps, _ = read_coverage((Path(root) / COVERAGE).read_text(encoding="utf-8"))
    return len(rules), sum(1 for r in rules if r.gap), sum(1 for r in rules if r.not_built)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=".", help="the repository root (default: .)")
    args = parser.parse_args(argv)
    violations = check(args.root)
    for violation in violations:
        print(violation, file=sys.stderr)
    total, without, not_built = summary(args.root)
    if violations:
        print(f"\n{len(violations)} problem(s) in the rule coverage.", file=sys.stderr)
        return 1
    print(f"{total} rules, {total - without - not_built} with a test, {without} listed as gaps, "
          f"{not_built} not built; every cited criterion exists.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
