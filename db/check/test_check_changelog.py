# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
#
# Tests for check_changelog.py (Story 6.4). Run from the repository root:
#
#   python3 -m unittest discover -s db/check -v
"""Each test writes a small changelog into a temporary directory and checks what the checker says."""

import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

import check_changelog as cc

REPO_ROOT = Path(__file__).resolve().parents[2]

GOOD_TABLE = """\
databaseChangeLog:
- changeSet:
    id: 0009-01-thing
    author: x9-qrcode
    changes:
    - createTable:
        schemaName: ${x9.schema}
        tableName: thing
        remarks: A thing.
        columns:
        - column:
            name: id
            type: UUID
            remarks: The thing's id.
            constraints:
              primaryKey: true
              nullable: false
"""


def with_column(type_, extra=""):
    return GOOD_TABLE + textwrap.indent(
        f"- column:\n    name: c\n    type: {type_}\n    remarks: A column.\n{extra}", " " * 8)


def with_sql(sql, comment=None):
    comment_line = f"    comment: '{comment}'\n" if comment else ""
    return (
        "databaseChangeLog:\n- changeSet:\n    id: 0009-02-sql\n    author: x9-qrcode\n" + comment_line
        + "    changes:\n    - sql:\n        sql: |-\n" + textwrap.indent(sql, " " * 10) + "\n"
    )


class ChangelogDir:
    """A temporary db/changelog directory with a master file including every file under changes/."""

    def __init__(self, files):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / "changes").mkdir()
        for name, text in files.items():
            (self.root / "changes" / name).write_text(text)

    def check(self, base=None):
        return [str(v) for v in cc.check(self.root, base)]

    def close(self):
        self.tmp.cleanup()


def run(files, base=None):
    d = ChangelogDir(files)
    try:
        return d.check(base)
    finally:
        d.close()


class TheRealChangelog(unittest.TestCase):
    def test_the_changelog_in_this_repository_passes(self):
        self.assertEqual([], [str(v) for v in cc.check(REPO_ROOT / "db" / "changelog", None)])

    def test_a_conforming_table_passes(self):
        self.assertEqual([], run({"0009-thing.yaml": GOOD_TABLE}))


class S64AC1AppliedChangesetsAreNeverEdited(unittest.TestCase):
    def base(self, text):
        return {"changes/0009-thing.yaml": text}

    def test_an_unchanged_changeset_passes(self):
        self.assertEqual([], run({"0009-thing.yaml": GOOD_TABLE}, self.base(GOOD_TABLE)))

    def test_reformatting_and_yaml_comments_are_not_edits(self):
        reformatted = "# a new comment\n" + GOOD_TABLE.replace("remarks: A thing.", "remarks: 'A thing.'")
        self.assertEqual([], run({"0009-thing.yaml": reformatted}, self.base(GOOD_TABLE)))

    def test_an_edited_changeset_fails(self):
        edited = GOOD_TABLE.replace("type: UUID", "type: VARCHAR(32)")
        out = run({"0009-thing.yaml": edited}, self.base(GOOD_TABLE))
        self.assertEqual(1, len(out), out)
        self.assertIn("changes/0009-thing.yaml", out[0])
        self.assertIn("0009-01-thing", out[0])
        self.assertIn("edited after it was applied", out[0])

    def test_an_edited_remark_is_an_edit_too(self):
        edited = GOOD_TABLE.replace("remarks: The thing's id.", "remarks: The id.")
        self.assertEqual(1, len(run({"0009-thing.yaml": edited}, self.base(GOOD_TABLE))))

    def test_a_removed_changeset_fails(self):
        out = run({"0010-other.yaml": GOOD_TABLE.replace("0009-01", "0010-01")}, self.base(GOOD_TABLE))
        self.assertTrue(any("0009-01-thing" in o and "removed" in o for o in out), out)

    def test_a_new_changeset_in_a_new_file_passes(self):
        files = {"0009-thing.yaml": GOOD_TABLE,
                 "0010-more.yaml": GOOD_TABLE.replace("0009-01", "0010-01").replace("thing", "more")}
        self.assertEqual([], run(files, self.base(GOOD_TABLE)))

    def test_a_changeset_appended_to_an_applied_file_passes(self):
        appended = GOOD_TABLE + GOOD_TABLE.split("databaseChangeLog:\n", 1)[1].replace(
            "0009-01", "0009-02").replace("tableName: thing", "tableName: thing2")
        self.assertEqual([], run({"0009-thing.yaml": appended}, self.base(GOOD_TABLE)))

    def test_against_git_an_edit_to_a_committed_changeset_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            git = lambda *a: subprocess.run(["git", "-C", tmp, *a], check=True, capture_output=True)
            git("init", "-q", "-b", "main")
            git("config", "user.email", "check@example.com")
            git("config", "user.name", "check")
            (repo / "db" / "changelog" / "changes").mkdir(parents=True)
            f = repo / "db" / "changelog" / "changes" / "0009-thing.yaml"
            f.write_text(GOOD_TABLE)
            git("add", ".")
            git("commit", "-q", "-m", "base")
            f.write_text(GOOD_TABLE.replace("nullable: false", "nullable: true"))
            base = cc.load_base(repo / "db" / "changelog", "main")
            out = [str(v) for v in cc.check(repo / "db" / "changelog", base)]
            self.assertEqual(1, len(out), out)
            self.assertIn("edited after it was applied", out[0])


class S64AC2EveryTableAndColumnHasAComment(unittest.TestCase):
    def test_a_table_without_remarks_fails(self):
        out = run({"0009-thing.yaml": GOOD_TABLE.replace("        remarks: A thing.\n", "")})
        self.assertEqual(1, len(out), out)
        self.assertIn("table thing has no COMMENT", out[0])

    def test_a_column_without_remarks_fails(self):
        out = run({"0009-thing.yaml": GOOD_TABLE.replace("            remarks: The thing's id.\n", "")})
        self.assertEqual(1, len(out), out)
        self.assertIn("column thing.id has no COMMENT", out[0])

    def test_an_empty_remark_fails(self):
        out = run({"0009-thing.yaml": GOOD_TABLE.replace("remarks: The thing's id.", "remarks: ''")})
        self.assertIn("column thing.id has no COMMENT", out[0])

    def test_an_added_column_without_remarks_fails(self):
        text = ("databaseChangeLog:\n- changeSet:\n    id: 0009-03\n    author: x9-qrcode\n    changes:\n"
                "    - addColumn:\n        tableName: thing\n        columns:\n        - column:\n"
                "            name: extra\n            type: INT\n")
        out = run({"0009-thing.yaml": text})
        self.assertEqual(1, len(out), out)
        self.assertIn("column thing.extra has no COMMENT", out[0])

    def test_a_table_created_in_raw_sql_needs_comment_on_table(self):
        out = run({"0009-x.yaml": with_sql("CREATE TABLE ${x9.schema}.raw (id INT)")})
        self.assertTrue(any("table raw has no COMMENT" in o for o in out), out)

    def test_a_table_created_in_raw_sql_with_its_comments_passes(self):
        sql = ("CREATE TABLE ${x9.schema}.raw (id INT);\n"
               "COMMENT ON TABLE ${x9.schema}.raw IS 'Raw.';\n"
               "COMMENT ON COLUMN ${x9.schema}.raw.id IS 'Id.'")
        self.assertEqual([], run({"0009-x.yaml": with_sql(sql)}))

    def test_a_column_added_in_raw_sql_needs_comment_on_column(self):
        out = run({"0009-x.yaml": with_sql("ALTER TABLE ${x9.schema}.thing ADD COLUMN extra INT")})
        self.assertTrue(any("column thing.extra has no COMMENT" in o for o in out), out)


class S64AC3ForbiddenConstructsAreNamedWithTheirFile(unittest.TestCase):
    def assert_forbidden(self, files, construct):
        out = run(files)
        self.assertTrue(out, f"expected {construct} to be refused")
        self.assertTrue(any(construct in o and "changes/" in o for o in out), out)

    def test_column_types(self):
        for type_, construct in [
            ("JSONB", "JSONB"), ("jsonb", "JSONB"),
            ("ENUM('A','B')", "ENUM"),
            ("INT[]", "array"), ("VARCHAR(4) ARRAY", "array"),
            ("TIMESTAMPTZ", "TIMESTAMPTZ"), ("TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ"),
            ("TEXT", "unbounded TEXT"), ("VARCHAR", "unbounded TEXT"), ("CHARACTER VARYING", "unbounded TEXT"),
            ("SERIAL", "SERIAL"), ("BIGSERIAL", "SERIAL"),
        ]:
            with self.subTest(type_=type_):
                self.assert_forbidden({"0009-thing.yaml": with_column(type_)}, construct)

    def test_auto_increment_is_serial(self):
        self.assert_forbidden({"0009-thing.yaml": with_column("BIGINT", "    autoIncrement: true\n")}, "SERIAL")

    def test_a_database_clock_default_is_refused(self):
        self.assert_forbidden(
            {"0009-thing.yaml": with_column("TIMESTAMP", "    defaultValueComputed: now()\n")},
            "database-side clock")

    def test_bounded_and_portable_types_pass(self):
        for type_ in ["VARCHAR(255)", "VARCHAR(65535)", "JSON", "TIMESTAMP", "BIGINT", "UUID", "SMALLINT", "INT"]:
            with self.subTest(type_=type_):
                self.assertEqual([], run({"0009-thing.yaml": with_column(type_)}))

    def test_change_types(self):
        for change, construct in [("createProcedure", "procedure"), ("createFunction", "procedure"),
                                  ("createTrigger", "trigger")]:
            with self.subTest(change=change):
                text = (f"databaseChangeLog:\n- changeSet:\n    id: 0009-04\n    author: x9-qrcode\n"
                        f"    changes:\n    - {change}:\n        procedureName: p\n")
                self.assert_forbidden({"0009-p.yaml": text}, construct)

    def test_raw_sql(self):
        for sql, construct in [
            ("ALTER TABLE t ADD COLUMN j JSONB", "JSONB"),
            ("CREATE TYPE s AS ENUM ('A')", "ENUM"),
            ("ALTER TABLE t ADD COLUMN a INT[]", "array"),
            ("ALTER TABLE t ADD COLUMN w TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ"),
            ("ALTER TABLE t ADD COLUMN x TEXT", "unbounded TEXT"),
            ("ALTER TABLE t ADD COLUMN i BIGSERIAL", "SERIAL"),
            ("CREATE TRIGGER tr BEFORE INSERT ON t FOR EACH ROW EXECUTE FUNCTION f()", "trigger"),
            ("CREATE OR REPLACE FUNCTION f() RETURNS INT AS 'select 1' LANGUAGE sql", "procedure"),
            ("CREATE PROCEDURE p() LANGUAGE sql AS 'select 1'", "procedure"),
            ("DO $$ BEGIN PERFORM 1; END $$", "procedure"),
            ("NOTIFY payment_event", "LISTEN/NOTIFY"),
            ("SELECT pg_advisory_lock(1)", "advisory lock"),
            ("INSERT INTO t VALUES (1) ON CONFLICT (id) DO UPDATE SET v = 1", "ON CONFLICT DO UPDATE"),
            ("ALTER TABLE t ALTER COLUMN c SET DEFAULT now()", "database-side clock"),
            ("CREATE ROLE r LOGIN PASSWORD 'secret'", "PASSWORD"),
        ]:
            with self.subTest(sql=sql):
                self.assert_forbidden({"0009-x.yaml": with_sql(sql)}, construct)

    def test_words_inside_string_literals_and_sql_comments_are_not_constructs(self):
        sql = ("-- a TEXT column would be JSONB here, but it is not\n"
               "COMMENT ON TABLE ${x9.schema}.thing IS 'Holds TEXT, never a TRIGGER or SERIAL.'")
        self.assertEqual([], run({"0009-x.yaml": with_sql(sql)}))

    def test_a_partial_index_and_a_check_constraint_pass(self):
        sql = ("CREATE INDEX ix_pending ON ${x9.schema}.payment_event (seq) WHERE read_status_code = 'PEND';\n"
               "ALTER TABLE ${x9.schema}.event_sequence ADD CONSTRAINT ck CHECK (id = 1)")
        self.assertEqual([], run({"0009-x.yaml": with_sql(sql)}))

    def test_a_recorded_exception_allows_the_named_construct_only(self):
        ok = with_sql("ALTER TABLE t ADD COLUMN j JSONB;\nCOMMENT ON COLUMN t.j IS 'J.'",
                      comment="ADR-0023 exception: JSONB, because a reason is given here.")
        self.assertEqual([], run({"0009-x.yaml": ok}))
        still = with_sql("ALTER TABLE t ADD COLUMN j JSONB, ADD COLUMN x TEXT",
                         comment="ADR-0023 exception: JSONB, because a reason is given here.")
        out = run({"0009-x.yaml": still})
        self.assertTrue(any("unbounded TEXT" in o for o in out) and not any("JSONB" in o for o in out), out)

    def test_an_sql_file_is_scanned_too(self):
        text = ("databaseChangeLog:\n- changeSet:\n    id: 0009-05\n    author: x9-qrcode\n    changes:\n"
                "    - sqlFile:\n        path: 0009-raw.sql\n        relativeToChangelogFile: true\n")
        self.assert_forbidden({"0009-x.yaml": text, "0009-raw.sql": "CREATE TRIGGER tr AFTER INSERT ON t"},
                              "trigger")

    def test_a_changelog_that_is_not_yaml_is_refused(self):
        out = run({"0009-x.xml": "<databaseChangeLog/>"})
        self.assertTrue(any("0009-x.xml" in o and "YAML" in o for o in out), out)


if __name__ == "__main__":
    unittest.main()
