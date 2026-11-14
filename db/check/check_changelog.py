#!/usr/bin/env python3
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
# Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
# based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
"""The changelog conventions of ADR-0023, enforced (Story 6.4).

    python3 db/check/check_changelog.py [--changelog-dir db/changelog] [--base origin/main]

It fails (exit 1), naming the file and the changeset, when:

  S6.4-AC1  a changeset that exists at --base (the applied history: what is merged to main) was
            edited or removed. Reformatting and YAML '#' comments are not edits; any change to
            what the changeset says is, including its remarks. A change is a new changeset.
  S6.4-AC2  a table or column is created without a COMMENT (Liquibase `remarks`, or
            COMMENT ON TABLE / COMMENT ON COLUMN in the same changeset for raw SQL).
  S6.4-AC3  a construct ADR-0023 keeps out of the schema appears: JSONB, ENUM, arrays,
            TIMESTAMPTZ, unbounded TEXT (or VARCHAR without a length), SERIAL (or autoIncrement),
            triggers, procedures (functions and DO blocks included), LISTEN/NOTIFY, advisory locks,
            ON CONFLICT DO UPDATE, a database-side clock as a default, or a PASSWORD (role
            passwords come from the DBA, S6.2-AC5).

A construct ADR-0023 forbids can still be used where it is truly needed, as ADR-0023's "recorded
exception": the changeset's `comment` says `ADR-0023 exception: <construct>, <reason>`, using the
construct's name exactly as this check prints it. Only that construct is allowed, only in that changeset.

Only YAML changelogs are accepted; `sqlFile` files are scanned as SQL. Needs PyYAML.
"""

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import yaml

YAML_SUFFIXES = (".yaml", ".yml")


@dataclass(frozen=True)
class Violation:
    file: str
    changeset: str
    message: str

    def __str__(self):
        where = f"changeset {self.changeset}: " if self.changeset else ""
        return f"{self.file}: {where}{self.message}"


# ------------------------------------------------------------------------------------------------
# Forbidden constructs (S6.4-AC3). Each is (name, pattern). Types are matched against a column's
# `type`; SQL patterns against raw SQL with its comments and string literals removed.

TYPE_RULES = [
    ("JSONB", re.compile(r"\bJSONB\b", re.I)),
    ("ENUM", re.compile(r"\bENUM\b", re.I)),
    ("array", re.compile(r"\[\s*\d*\s*\]|\bARRAY\b", re.I)),
    ("TIMESTAMPTZ", re.compile(r"\bTIMESTAMPTZ\b|\bTIME(STAMP)?\s+WITH\s+TIME\s+ZONE\b|\bTIMETZ\b", re.I)),
    ("unbounded TEXT", re.compile(r"^\s*(TEXT|CLOB|VARCHAR|CHARACTER\s+VARYING|NVARCHAR)\s*$", re.I)),
    ("SERIAL", re.compile(r"\b(SMALL|BIG)?SERIAL\d?\b", re.I)),
]

SQL_RULES = [
    ("JSONB", re.compile(r"\bJSONB\b", re.I)),
    ("ENUM", re.compile(r"\bENUM\b", re.I)),
    ("array", re.compile(r"\[\s*\d*\s*\]|\bARRAY\b", re.I)),
    ("TIMESTAMPTZ", re.compile(r"\bTIMESTAMPTZ\b|\bTIME(STAMP)?\s+WITH\s+TIME\s+ZONE\b|\bTIMETZ\b", re.I)),
    ("unbounded TEXT", re.compile(r"\b(TEXT|CLOB)\b|\b(VARCHAR|CHARACTER\s+VARYING)\b(?!\s*\()", re.I)),
    ("SERIAL", re.compile(r"\b(SMALL|BIG)?SERIAL\d?\b|\bGENERATED\s+(ALWAYS|BY\s+DEFAULT)\s+AS\s+IDENTITY\b", re.I)),
    ("trigger", re.compile(r"\bCREATE\s+(OR\s+REPLACE\s+)?(CONSTRAINT\s+)?TRIGGER\b", re.I)),
    ("procedure", re.compile(r"\bCREATE\s+(OR\s+REPLACE\s+)?(FUNCTION|PROCEDURE)\b|\bDO\s*(\$|LANGUAGE\b)", re.I)),
    ("LISTEN/NOTIFY", re.compile(r"\b(LISTEN|NOTIFY|UNLISTEN)\b|\bpg_notify\b", re.I)),
    ("advisory lock", re.compile(r"\bpg_(try_)?advisory", re.I)),
    ("ON CONFLICT DO UPDATE", re.compile(r"\bON\s+CONFLICT\b[^;]*?\bDO\s+UPDATE\b", re.I | re.S)),
    ("database-side clock", re.compile(
        r"\b(now|clock_timestamp|statement_timestamp|transaction_timestamp|timeofday)\s*\(|"
        r"\b(CURRENT_TIMESTAMP|CURRENT_DATE|CURRENT_TIME|LOCALTIMESTAMP|LOCALTIME)\b", re.I)),
    ("PASSWORD", re.compile(r"\bPASSWORD\b", re.I)),
]

CLOCK = re.compile(SQL_RULES[-2][1].pattern, re.I)

FORBIDDEN_CHANGE_TYPES = {
    "createProcedure": "procedure",
    "createFunction": "procedure",
    "createPackage": "procedure",
    "createPackageBody": "procedure",
    "createTrigger": "trigger",
    "enableTrigger": "trigger",
}

EXCEPTION = re.compile(r"ADR-0023 exception:\s*([^,.;]+)", re.I)


def strip_sql(sql):
    """Removes -- and /* */ comments, and the contents of '…' literals, so words inside them are ignored.
    Dollar-quoted bodies are kept: they are code (a DO block or a function)."""
    out, i, n = [], 0, len(sql)
    while i < n:
        if sql.startswith("--", i):
            j = sql.find("\n", i)
            i = n if j < 0 else j
        elif sql.startswith("/*", i):
            j = sql.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif sql[i] == "'":
            j = i + 1
            while j < n:
                if sql[j] == "'" and j + 1 < n and sql[j + 1] == "'":
                    j += 2
                elif sql[j] == "'":
                    break
                else:
                    j += 1
            out.append("''")
            i = j + 1
        else:
            out.append(sql[i])
            i += 1
    return "".join(out)


# ------------------------------------------------------------------------------------------------
# Loading

def load_yaml(text):
    return yaml.safe_load(text) or {}


def changesets_of(doc):
    """The changeSet mappings of a parsed changelog, in order."""
    entries = doc.get("databaseChangeLog") if isinstance(doc, dict) else None
    return [e["changeSet"] for e in entries or [] if isinstance(e, dict) and "changeSet" in e]


def identity(rel, cs):
    """Liquibase identifies a changeset by file path, id and author."""
    return (rel, str(cs.get("id")), str(cs.get("author")))


def canonical(cs):
    return json.dumps(cs, sort_keys=True, default=str)


def changelog_files(root):
    return sorted(p for p in root.rglob("*") if p.is_file())


def load_base(changelog_dir, ref):
    """{relative path: text} of every YAML changelog file under changelog_dir at the git ref."""
    changelog_dir = Path(changelog_dir).resolve()
    top = Path(subprocess.run(["git", "-C", str(changelog_dir), "rev-parse", "--show-toplevel"],
                              check=True, capture_output=True, text=True).stdout.strip()).resolve()
    prefix = changelog_dir.relative_to(top).as_posix()
    listing = subprocess.run(["git", "-C", str(top), "ls-tree", "-r", "--name-only", ref, "--", prefix],
                             check=True, capture_output=True, text=True).stdout.split()
    base = {}
    for path in listing:
        if path.endswith(YAML_SUFFIXES):
            text = subprocess.run(["git", "-C", str(top), "show", f"{ref}:{path}"],
                                  check=True, capture_output=True, text=True).stdout
            base[Path(path).relative_to(prefix).as_posix()] = text
    return base


# ------------------------------------------------------------------------------------------------
# Rules

def check_applied(head, base):
    """S6.4-AC1: every changeset present at the base is still present, unchanged."""
    current = {}
    for rel, doc in head.items():
        for cs in changesets_of(doc):
            current[identity(rel, cs)] = canonical(cs)
    found = []
    for rel, text in sorted(base.items()):
        try:
            doc = load_yaml(text)
        except yaml.YAMLError:
            continue
        for cs in changesets_of(doc):
            key = identity(rel, cs)
            if key not in current:
                found.append(Violation(rel, key[1], "applied changeset was removed or moved; applied changesets "
                                                    "are never edited, a change is a new numbered file"))
            elif current[key] != canonical(cs):
                found.append(Violation(rel, key[1], "applied changeset was edited after it was applied; "
                                                    "put the change in a new numbered file (ADR-0023)"))
    return found


def remark_missing(node):
    value = node.get("remarks") if isinstance(node, dict) else None
    return value is None or not str(value).strip()


def columns_of(change):
    return [c["column"] for c in change.get("columns") or [] if isinstance(c, dict) and "column" in c]


def table_name(raw):
    """A table name out of `${x9.schema}.name`, `schema.name` or `"name"`."""
    return raw.split(".")[-1].strip('"').lower()


def check_changeset(rel, cs, root):
    cs_id = str(cs.get("id"))
    allowed = {m.strip().lower() for m in EXCEPTION.findall(str(cs.get("comment") or ""))}
    found = []

    def forbid(construct, detail):
        if construct.lower() not in allowed:
            found.append(Violation(rel, cs_id, f"{construct} is not allowed (ADR-0023): {detail}"))

    def scan_sql(sql, where):
        clean = strip_sql(sql)
        for name, pattern in SQL_RULES:
            m = pattern.search(clean)
            if m:
                forbid(name, f"{where}: '{m.group(0).strip()}'")
        return clean

    raw_sql = []
    changes = list(cs.get("changes") or [])
    rollback = cs.get("rollback")
    if isinstance(rollback, list):
        changes += rollback
    elif isinstance(rollback, dict):
        changes.append(rollback)
    elif isinstance(rollback, str):
        changes.append({"sql": {"sql": rollback}})

    for change in changes:
        if not isinstance(change, dict):
            continue
        for kind, body in change.items():
            body = body if isinstance(body, dict) else {}
            if kind in FORBIDDEN_CHANGE_TYPES:
                forbid(FORBIDDEN_CHANGE_TYPES[kind], f"change type {kind}")
            if kind == "createTable" and remark_missing(body):
                found.append(Violation(rel, cs_id, f"table {body.get('tableName')} has no COMMENT (remarks)"))
            if kind in ("createTable", "addColumn"):
                for col in columns_of(body):
                    label = f"column {body.get('tableName')}.{col.get('name')}"
                    if remark_missing(col):
                        found.append(Violation(rel, cs_id, f"{label} has no COMMENT (remarks)"))
                    check_type(col.get("type"), label, forbid)
                    if col.get("autoIncrement"):
                        forbid("SERIAL", f"{label}: autoIncrement")
                    for key in ("defaultValueComputed", "valueComputed", "defaultValueDate"):
                        if key in col and CLOCK.search(str(col[key])):
                            forbid("database-side clock", f"{label}: {key} {col[key]}")
            if kind == "modifyDataType":
                check_type(body.get("newDataType"), f"column {body.get('tableName')}.{body.get('columnName')}", forbid)
            if kind == "addDefaultValue" and CLOCK.search(str(body.get("defaultValueComputed", ""))):
                forbid("database-side clock", f"addDefaultValue {body.get('defaultValueComputed')}")
            if kind == "addAutoIncrement":
                forbid("SERIAL", "change type addAutoIncrement")
            if kind == "sql":
                raw_sql.append(scan_sql(str(body.get("sql", "")), "sql"))
            if kind == "sqlFile":
                path = (root / Path(rel).parent / body["path"]) if body.get("relativeToChangelogFile") \
                    else (root / body.get("path", ""))
                if path.is_file():
                    raw_sql.append(scan_sql(path.read_text(), f"sqlFile {body.get('path')}"))
                else:
                    found.append(Violation(rel, cs_id, f"sqlFile {body.get('path')} not found, so it cannot be checked"))

    found += check_raw_sql_comments(rel, cs_id, "\n;\n".join(raw_sql))
    return found


def check_type(type_, label, forbid):
    if type_ is None:
        return
    for name, pattern in TYPE_RULES:
        if pattern.search(str(type_)):
            forbid(name, f"{label} has type {type_}")


IDENT = r'(?:"?[\w${}.]+"?)'
CREATE_TABLE = re.compile(rf"\bCREATE\s+(?:UNLOGGED\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?({IDENT})\s*\(([^;]*)", re.I)
ADD_COLUMN = re.compile(rf"\bALTER\s+TABLE\s+(?:ONLY\s+)?(?:IF\s+EXISTS\s+)?({IDENT})\s+ADD\s+(?:COLUMN\s+)?"
                        rf"(?!CONSTRAINT\b|PRIMARY\b|UNIQUE\b|FOREIGN\b|CHECK\b)({IDENT})", re.I)
COMMENT_TABLE = re.compile(rf"\bCOMMENT\s+ON\s+TABLE\s+({IDENT})\s+IS\s+''", re.I)
COMMENT_COLUMN = re.compile(rf"\bCOMMENT\s+ON\s+COLUMN\s+({IDENT})\s+IS\s+''", re.I)


def check_raw_sql_comments(rel, cs_id, clean):
    """S6.4-AC2 for raw SQL: a table or column it creates needs its COMMENT ON in the same changeset.
    Literals were blanked by strip_sql, so a COMMENT ON … IS '…' reads as IS ''; an empty literal in
    the source would read the same, which is why only presence is checked here and the catalog check
    in CI (obj_description / col_description) has the last word."""
    tables = {table_name(m) for m in COMMENT_TABLE.findall(clean)}
    columns = {".".join(m.split(".")[-2:]).replace('"', "").lower() for m in COMMENT_COLUMN.findall(clean)}
    found = []
    for m in CREATE_TABLE.finditer(clean):
        t = table_name(m.group(1))
        if t not in tables:
            found.append(Violation(rel, cs_id, f"table {t} has no COMMENT (COMMENT ON TABLE in the same changeset)"))
        for part in split_top_level(m.group(2)):
            word = part.strip().split()
            if word and word[0].upper() not in ("CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "EXCLUDE", "LIKE"):
                c = f"{t}.{word[0].strip(chr(34)).lower()}"
                if c not in columns:
                    found.append(Violation(rel, cs_id, f"column {c} has no COMMENT (COMMENT ON COLUMN in the same changeset)"))
    for m in ADD_COLUMN.finditer(clean):
        c = f"{table_name(m.group(1))}.{m.group(2).strip(chr(34)).lower()}"
        if c not in columns:
            found.append(Violation(rel, cs_id, f"column {c} has no COMMENT (COMMENT ON COLUMN in the same changeset)"))
    return found


def split_top_level(body):
    """The comma-separated items of a CREATE TABLE body, up to its closing parenthesis."""
    parts, depth, cur = [], 0, []
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            if depth == 0:
                break
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))
    return parts


# ------------------------------------------------------------------------------------------------

def check(changelog_dir, base=None):
    """All violations under changelog_dir. base: {relative path: text} of the applied history, or None."""
    root = Path(changelog_dir)
    head, found = {}, []
    for path in changelog_files(root):
        rel = path.relative_to(root).as_posix()
        if path.suffix == ".sql":
            continue  # scanned when a changeset includes it with sqlFile
        if path.suffix not in YAML_SUFFIXES:
            found.append(Violation(rel, "", "only YAML changelogs are accepted here (and .sql for sqlFile)"))
            continue
        try:
            head[rel] = load_yaml(path.read_text())
        except yaml.YAMLError as e:
            found.append(Violation(rel, "", f"is not valid YAML: {e}"))
    for rel, doc in head.items():
        for cs in changesets_of(doc):
            found += check_changeset(rel, cs, root)
    if base is not None:
        found += check_applied(head, base)
    return found


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--changelog-dir", default=str(Path(__file__).resolve().parents[1] / "changelog"))
    parser.add_argument("--base", help="git ref holding the applied history (CI: the pull request's base)")
    args = parser.parse_args(argv)
    base = load_base(args.changelog_dir, args.base) if args.base else None
    found = check(args.changelog_dir, base)
    for v in found:
        print(f"FAIL  {v}")
    scope = f"against {args.base}" if args.base else "(no --base: edits to applied changesets not checked)"
    print(f"{'FAILED' if found else 'OK'}: {len(found)} violation(s) in {args.changelog_dir} {scope}")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
