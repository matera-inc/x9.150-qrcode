#!/usr/bin/env python3
# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""The differential run: what Java answered against what Go answered (Story 11.1, ADR-0028).

    python3 others/blackbox/differential.py JAVA_RECORDING GO_RECORDING \\
        [--json differences.json] [--top 25] [--tolerated FILE | --no-tolerated]

Each argument is a directory the suite recorded into (``X9_RECORD_DIR``, ``x9client.recording``).
Standard library only.

**Pairing (S11.1-AC2, AC3).** Exchanges are paired by bucket (the test node id, or a shared
fixture's ``<session>::…``) and, inside one, by position. Positions are aligned on the request (the
method and the masked path), so an exchange one build made and the other did not (a retry, a branch
on the build) is reported by its index and the rest still pair. A run of identical polls of the
event stream counts as one exchange, its last, holding every event the run read (each once), because
how often a poll is repeated, and which poll an event lands in, are a matter of timing. A test that only one build recorded is reported by name, with why (a ``go_only`` or
``java_only`` marker, a skip, or nothing recorded).

**Normalising (S11.1-AC2).** Before comparing, both sides go through ``normalise``:

- JSON is parsed and compared as values, so whitespace and key order never count. A number keeps its
  value (``10`` equals ``10.0``) but not its type against a string or a boolean.
- A compact JWS, as a whole body or as a string anywhere inside one, is compared as its decoded
  header and its decoded payload; its signature is masked, and so are the header's per-run members
  (``iat``, ``correlationId``) and its certificates (``x5c``).
- Server-minted values are masked wherever they appear in a string, a path or a header
  (``<id>``): the QR Code and location ids of 32 hexadecimal characters (inside an EMV string too,
  where nothing separates the id from the next tag), ULIDs and UUIDs. So are date-times
  (``<time>``, keeping whether the offset is ``Z`` or numeric, which is a contract difference),
  dates (``<date>``), epoch milliseconds inside a text (``<millis>``) and an event-stream cursor in a path (``<cursor>``).
- Members that are per run by their name are masked whatever they hold: ``correlationId``,
  ``eventId``, ``id`` (of a QR Code or an event), ``nextCursor``, ``seq``, ``iat``, ``x5c``,
  ``qrcodeId`` and a certificate thumbprint (``x5t``, ``x5t#S256``: the test payee's certificate
  is issued per run). ``qrCodeB64`` is decoded and its EMV string compared, masked.
- Two arrays are compared as sets: a JWS header's ``crit`` (RFC 7515 defines no order) and a
  problem's ``violations`` (Java reports them in an order that changes from run to run). Their
  content still counts, one by one.
- An EMV string's CRC (``6304xxxx``) is masked, since the location id it covers is per run.
- The event stream is read the way the suite reads it, for this test only: a page keeps only the
  events about the QR Codes this test created, so events of other tests, which arrive at their own
  pace, never count; they are grouped by QR Code in the order the test made them, each QR Code's
  events kept in stream order (the order the contract promises is per QR Code).

**Comparing.** Status, ``ETag``, ``Content-Type`` (case and spaces ignored), ``Location`` and the
normalised body must match. A body difference is reported per JSON member (``body.violations[0]
.message``), so an added or dropped member, a changed text or a changed type each say where.

**The tolerated list (Story 11.2, S11.2-AC1, AC2).** ``TOLERATED-DIFFERENCES.md``, next to this
file, lists every difference ADR-0028 accepts, each with its reason, its test keys and one fenced
``json`` block, its matcher (``read_tolerated``):

- ``differences``: selectors, each matching report rows by ``test`` (a regular expression searched in
  the node id; required), and optionally ``request`` and ``field`` (regular expressions the whole
  value must match), ``kind``, ``java`` and ``go`` (the normalised values, exactly; ``"<absent>"``
  for a member one side does not have), ``javaPattern`` and ``goPattern`` (regular expressions over
  a value as text: a string as it is, anything else as JSON), and ``present: true`` (neither side
  absent nor null);
- ``oneBuild``: selectors matching a test only one build recorded, by ``test``, ``recordedBy``
  (``java`` or ``go``) and ``why`` (``go_only``, ``java_only``, ``skipped``...);
- ``proof``: for an entry the recordings cannot show (``go-test``: proved by a Go unit test the
  suite cannot drive; ``normalised``: removed by the normalising above), no selector;
- ``status``: ``tolerated``, or ``pending``: a difference the owner has not ruled on, tolerated so
  that the run is not red on it, and listed loudly in every report until it is decided;
- ``mayNotOccur: true`` on a selector whose difference depends on the run (which racer wins).

A row is tolerated when any selector of any entry matches it. The run then fails on a difference no
entry matches, on a test recorded by one build only that no entry matches, and on a **stale**
selector: one that matched nothing in this run (unless ``mayNotOccur``), since a tolerated
difference that has gone away must leave the list.

Exit status: 0 when every difference is tolerated and no selector is stale, 1 otherwise, 2 on a
usage error or a malformed tolerated list. ``--no-tolerated`` diffs without the list (every
difference fails).
"""
import argparse
import base64
import collections
import difflib
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TOLERATED_FILE = os.path.join(HERE, "TOLERATED-DIFFERENCES.md")

# ------------------------------------------------------------------------------------------------
# Reading a recording

INDEX_PREFIX = "_tests."


def load(directory):
    """``(exchanges by bucket, test index by node id)`` from one recording directory."""
    if not os.path.isdir(directory):
        raise SystemExit(f"not a recording directory: {directory}")
    buckets, tests = {}, {}
    for path in sorted(glob.glob(os.path.join(directory, "*.jsonl"))):
        with open(path, encoding="utf-8") as handle:
            lines = [json.loads(line) for line in handle if line.strip()]
        if os.path.basename(path).startswith(INDEX_PREFIX):
            for line in lines:
                tests[line["test"]] = line
            continue
        for line in lines:
            buckets.setdefault(line["test"], []).append(line)
    for exchanges in buckets.values():
        exchanges.sort(key=lambda e: e["index"])
    return buckets, tests


# ------------------------------------------------------------------------------------------------
# Normalising

ULID = r"[0-9A-HJKMNP-TV-Z]{26}"
UUID = r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
# A QR Code id and a location id: 32 hexadecimal characters (a ULID's 128 bits, as hex).
HEX_ID = r"[0-9A-Fa-f]{32}"
_ID = re.compile(rf"(?<![0-9A-Za-z])(?:{UUID}|{HEX_ID}|{ULID})(?![0-9A-Za-z])")
# Inside an EMV string the location id runs straight into the next tag: no boundary after it.
_LOC = re.compile(r"(?<=/loc/)[0-9A-Za-z]{32}")
_PATH_LOC = re.compile(r"(?<=/)[0-9A-Za-z]{32}(?=/|\?|$)")
_TIME = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?")
_DATE = re.compile(r"(?<![0-9-])\d{4}-\d{2}-\d{2}(?![0-9T:])")
# An instant in epoch milliseconds written into a text (a signature refusal names its iat): 13
# digits, 2017 to 2033. Only text is touched; a number member keeps its value.
_MILLIS = re.compile(r"(?<![0-9])1[5-9][0-9]{11}(?![0-9])")
# The CRC closes an EMV string: at its end, or where a text quoting it goes on.
_CRC = re.compile(r"6304[0-9A-Fa-f]{4}(?![0-9A-Za-z])")
_CURSOR = re.compile(r"(?<=[?&]after=)[^&]*")
_JWS = re.compile(r"^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*\.[A-Za-z0-9_-]*$")

# Members whose value is per run, by name, wherever they appear.
MASKED_MEMBERS = {
    "correlationId": "<correlationId>",
    "eventId": "<id>",
    "id": "<id>",
    "qrcodeId": "<id>",
    "qrCodeId": "<id>",
    "nextCursor": "<cursor>",
    "cursor": "<cursor>",
    "lastSeq": "<cursor>",
    "seq": "<cursor>",
    "iat": "<iat>",
    "x5c": "<certificates>",
    # The thumbprint of a certificate the suite issues at run time (the test payee's).
    "x5t#S256": "<thumbprint>",
    "x5t": "<thumbprint>",
}
# Members holding base64 of a value that is itself compared, decoded and masked.
BASE64_MEMBERS = ("qrCodeB64",)
# Members of a JWS header that are per run.
JWS_HEADER_PER_RUN = ("iat", "correlationId", "x5c")
# Arrays whose order carries no meaning, compared as sorted: a JWS header's ``crit`` (a set of
# names, RFC 7515 §4.1.11) and a problem's ``violations``, which Java's Bean Validation reports in
# an order that changes from run to run (a hash set), so order there is not behaviour.
UNORDERED_MEMBERS = ("crit", "violations")


def _sort_key(item):
    return json.dumps(item, sort_keys=True, default=str)


def _b64json(segment):
    try:
        return json.loads(base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4)))
    except (ValueError, UnicodeDecodeError):
        return None


def decode_jws(text):
    """``{"header": ..., "payload": ...}`` of a compact JWS, or None when ``text`` is not one."""
    if not isinstance(text, str) or not _JWS.match(text.strip()):
        return None
    head, payload, _ = text.strip().split(".")
    header = _b64json(head)
    if not isinstance(header, dict) or "alg" not in header:
        return None
    decoded = _b64json(payload)
    if decoded is None:
        try:
            decoded = base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)).decode()
        except (ValueError, UnicodeDecodeError):
            decoded = "<undecodable payload>"
    return {"header": header, "payload": decoded}


def _unbase64(text):
    try:
        return base64.b64decode(text, validate=True).decode()
    except (ValueError, UnicodeDecodeError):
        return text


def _time_mask(match):
    offset = match.group(1)
    if offset is None:
        return "<time>"
    return "<time Z>" if offset == "Z" else "<time ±hh:mm>"


def mask_text(text):
    """Server-minted ids, locations, times and dates in a string, masked."""
    if "000201" not in text and len(text) >= 40:
        # base64 of an EMV string quoted inside a text (a PATCH refusal names the stored value)
        text = _BASE64_RUN.sub(_unbase64_emv, text)
    text = _LOC.sub("<id>", text)
    text = _ID.sub("<id>", text)
    text = _TIME.sub(_time_mask, text)
    text = _DATE.sub("<date>", text)
    text = _MILLIS.sub("<millis>", text)
    if "000201" in text:
        text = _CRC.sub("6304<crc>", text)
    return text


_BASE64_RUN = re.compile(r"[A-Za-z0-9+/]{40,}={0,2}")


def _unbase64_emv(match):
    decoded = _unbase64(match.group(0))
    return f"<base64 of {decoded}>" if decoded.startswith("000201") else match.group(0)


def mask_path(path):
    """A request path, ids and cursors masked: the key exchanges are paired on."""
    path = re.sub(r"^https?://[^/]+", "", path or "")
    path = _CURSOR.sub("<cursor>", path)
    path = _PATH_LOC.sub("<id>", path)
    return mask_text(path)


def normalise(value):
    """A JSON value with everything per run masked (see the module docstring)."""
    if isinstance(value, dict):
        out = {}
        for key, member in value.items():
            if key in MASKED_MEMBERS and member is not None:
                out[key] = MASKED_MEMBERS[key]
            elif key in BASE64_MEMBERS and isinstance(member, str):
                out[key] = {"<base64>": normalise(_unbase64(member))}
            elif key in UNORDERED_MEMBERS and isinstance(member, list):
                out[key] = sorted((normalise(item) for item in member), key=_sort_key)
            else:
                out[key] = normalise(member)
        return out
    if isinstance(value, list):
        return [normalise(item) for item in value]
    if isinstance(value, str):
        jws = decode_jws(value)
        if jws is not None:
            return normalise_jws(jws)
        return mask_text(value)
    return value


def normalise_jws(jws):
    header = {k: v for k, v in jws["header"].items() if k not in JWS_HEADER_PER_RUN}
    for key in JWS_HEADER_PER_RUN:
        if key in jws["header"]:
            header[key] = MASKED_MEMBERS.get(key, "<per run>")
    return {"<jws>": {"header": normalise(header), "payload": normalise(jws["payload"]),
                      "signature": "<signature>"}}


def parse_body(text):
    """The body as received: JSON parsed, a JWS decoded, anything else as (masked) text."""
    if text is None or text == "":
        return None
    if isinstance(text, (dict, list)):
        return text
    try:
        return json.loads(text)
    except ValueError:
        return text


def normalise_content_type(value):
    return re.sub(r"\s+", "", value or "").lower() or None


def normalise_etag(value):
    return mask_text(value) if value else None


def normalise_location(value):
    return mask_path(value) if value else None


# ------------------------------------------------------------------------------------------------
# One test's exchanges: the event stream filtered, polls collapsed

EVENTS_PATH = "/pub/api/v1/events"


def _minted_qr_ids(exchanges):
    """Every QR Code id this test created (a created QR Code's ``id``) or named in a path, each
    with the position it was first seen at: ``{id: rank}``."""
    ids = {}
    for exchange in exchanges:
        if exchange["request"]["method"] == "POST":
            body = parse_body(exchange["response"].get("body"))
            if isinstance(body, dict) and isinstance(body.get("id"), str):
                ids.setdefault(body["id"], len(ids))
        for found in re.findall(rf"{HEX_ID}|{ULID}", exchange["request"].get("rawPath") or ""):
            ids.setdefault(found, len(ids))
    return ids


def _event_qr(event):
    for key in ("qrcodeId", "qrCodeId", "paymentRequestId"):
        if isinstance(event.get(key), str):
            return event[key]
    data = event.get("data")
    if isinstance(data, dict):
        return _event_qr(data)
    return None


def prepare(exchanges):
    """The exchanges of one bucket as they are compared: polls collapsed, event pages filtered."""
    ids = _minted_qr_ids(exchanges)
    out = []
    for exchange in exchanges:
        key = (exchange["request"]["method"], mask_path(exchange["request"].get("rawPath")
                                                        or exchange["request"]["path"]))
        body = parse_body(exchange["response"].get("body"))
        poll = key[1].startswith(EVENTS_PATH)
        if poll and isinstance(body, dict) and isinstance(body.get("events"), list):
            # Only this test's QR Codes; grouped by QR Code in the order the test made them, each
            # one's events in stream order. Order is a contract per QR Code; across QR Codes it is
            # whichever transaction committed first, which is timing.
            mine = [e for e in body["events"] if isinstance(e, dict) and _event_qr(e) in ids]
            body = dict(body, events=sorted(mine, key=lambda e: ids[_event_qr(e)]))
        prepared = {"index": exchange["index"], "key": key, "poll": poll,
                    "status": exchange["response"]["status"],
                    "headers": exchange["response"].get("headers") or {},
                    "body": body}
        if poll and out and out[-1]["poll"] and out[-1]["key"][0] == key[0] \
                and out[-1]["key"][1].split("?")[0] == key[1].split("?")[0]:
            # A run of polls is one exchange: its last, holding every event the run read. A reader
            # that moves its cursor between polls sees each event once, in whichever poll it
            # happened to commit before, and that is timing: one build's run may read two events
            # in one poll and the other in two.
            earlier, later = out[-1]["body"], prepared["body"]
            if isinstance(earlier, dict) and isinstance(later, dict) \
                    and isinstance(earlier.get("events"), list) and isinstance(later.get("events"), list):
                seen = {e.get("eventId") for e in later["events"]}
                merged = [e for e in earlier["events"] if e.get("eventId") not in seen]
                merged += later["events"]
                prepared["body"] = dict(later, events=sorted(merged, key=lambda e: ids[_event_qr(e)]))
            out[-1] = prepared
            continue
        out.append(prepared)
    return out


# ------------------------------------------------------------------------------------------------
# Comparing

def _same(a, b):
    if isinstance(a, bool) or isinstance(b, bool):
        return type(a) is type(b) and a == b
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return a == b
    return type(a) is type(b) and a == b


def diff_values(java, go, path="body"):
    """``[(field, java value, go value)]``: every place two normalised values differ."""
    if isinstance(java, dict) and isinstance(go, dict):
        out = []
        for key in list(java) + [k for k in go if k not in java]:
            field = f"{path}.{key}"
            if key not in go:
                out.append((field, java[key], "<absent>"))
            elif key not in java:
                out.append((field, "<absent>", go[key]))
            else:
                out.extend(diff_values(java[key], go[key], field))
        return out
    if isinstance(java, list) and isinstance(go, list):
        out = []
        for i in range(max(len(java), len(go))):
            field = f"{path}[{i}]"
            if i >= len(go):
                out.append((field, java[i], "<absent>"))
            elif i >= len(java):
                out.append((field, "<absent>", go[i]))
            else:
                out.extend(diff_values(java[i], go[i], field))
        return out
    if (isinstance(java, (dict, list)) or isinstance(go, (dict, list))) or not _same(java, go):
        return [] if java == go and type(java) is type(go) else [(path, java, go)]
    return []


def compare_exchange(java, go):
    """``[(field, java value, go value)]`` for one pair of prepared exchanges."""
    out = []
    if java["status"] != go["status"]:
        out.append(("status", java["status"], go["status"]))
    for header, norm in (("ETag", normalise_etag), ("Content-Type", normalise_content_type),
                         ("Location", normalise_location)):
        j = norm(_header(java["headers"], header))
        g = norm(_header(go["headers"], header))
        if j != g:
            out.append((f"header:{header}", j, g))
    out.extend(diff_values(normalise(java["body"]), normalise(go["body"])))
    return out


def _header(headers, name):
    for key, value in headers.items():
        if key.lower() == name.lower():
            return value
    return None


def compare_bucket(test, java_exchanges, go_exchanges):
    """Every difference of one bucket, as report rows."""
    java, go = prepare(java_exchanges), prepare(go_exchanges)
    rows = []
    if len(java) != len(go):
        rows.append(_row("exchange-count", test, None, None, "exchanges", len(java), len(go)))
    matcher = difflib.SequenceMatcher(a=[e["key"] for e in java], b=[e["key"] for e in go],
                                      autojunk=False)
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal":
            for j_ex, g_ex in zip(java[i1:i2], go[j1:j2]):
                for field, jv, gv in compare_exchange(j_ex, g_ex):
                    rows.append(_row(_kind(field), test, j_ex, g_ex, field, jv, gv))
            continue
        if tag == "replace" and i2 - i1 == j2 - j1:
            # Same number of exchanges, different requests: still compared one by one, and the
            # request itself reported, since a test that asks differently per build is news.
            for j_ex, g_ex in zip(java[i1:i2], go[j1:j2]):
                rows.append(_row("request", test, j_ex, g_ex, "request",
                                 " ".join(j_ex["key"]), " ".join(g_ex["key"])))
                for field, jv, gv in compare_exchange(j_ex, g_ex):
                    rows.append(_row(_kind(field), test, j_ex, g_ex, field, jv, gv))
            continue
        for j_ex in java[i1:i2]:
            rows.append(_row("java-only-exchange", test, j_ex, None, "request",
                             " ".join(j_ex["key"]), "<absent>"))
        for g_ex in go[j1:j2]:
            rows.append(_row("go-only-exchange", test, None, g_ex, "request", "<absent>",
                             " ".join(g_ex["key"])))
    return rows


def _kind(field):
    if field == "status":
        return "status"
    if field.startswith("header:"):
        return field
    return "body"


def _row(kind, test, java, go, field, java_value, go_value):
    exchange = java or go
    return {
        "kind": kind,
        "test": test,
        "javaIndex": java["index"] if java else None,
        "goIndex": go["index"] if go else None,
        "request": " ".join(exchange["key"]) if exchange else None,
        "field": field,
        "java": java_value,
        "go": go_value,
    }


def _why_missing(entry, build):
    if entry is None:
        return "nothing recorded"
    markers = entry.get("markers") or []
    if "go_only" in markers and build == "java":
        return "go_only"
    if "java_only" in markers and build == "go":
        return "java_only"
    return entry.get("outcome") or "nothing recorded"


def compare(java_dir, go_dir):
    """The whole comparison: ``{"summary": ..., "tests": ..., "differences": [rows]}``."""
    java_buckets, java_tests = load(java_dir)
    go_buckets, go_tests = load(go_dir)
    rows, only = [], []
    for test in sorted(set(java_buckets) | set(go_buckets)):
        if test not in go_buckets:
            only.append({"test": test, "recordedBy": "java",
                         "why": _why_missing(go_tests.get(test), "go"),
                         "exchanges": len(java_buckets[test])})
            continue
        if test not in java_buckets:
            only.append({"test": test, "recordedBy": "go",
                         "why": _why_missing(java_tests.get(test), "java"),
                         "exchanges": len(go_buckets[test])})
            continue
        rows.extend(compare_bucket(test, java_buckets[test], go_buckets[test]))
    outcomes = []
    for test in sorted(set(java_tests) | set(go_tests)):
        j = (java_tests.get(test) or {}).get("outcome", "not run")
        g = (go_tests.get(test) or {}).get("outcome", "not run")
        if j != g:
            outcomes.append({"test": test, "java": j, "go": g})
    by_kind = collections.Counter(row["kind"] for row in rows)
    for entry in only:
        by_kind[f"only-{entry['recordedBy']}:{entry['why']}"] += 1
    summary = {
        "buckets": {"java": len(java_buckets), "go": len(go_buckets),
                    "both": len(set(java_buckets) & set(go_buckets))},
        "testsWithDifferences": len({row["test"] for row in rows}),
        "differences": len(rows),
        "byKind": dict(sorted(by_kind.items())),
        "outcomesDiffer": len(outcomes),
    }
    return {"summary": summary, "onlyOneBuild": only, "outcomes": outcomes, "differences": rows}


# ------------------------------------------------------------------------------------------------
# The tolerated list (Story 11.2)

_ENTRY_HEADING = re.compile(r"^## (\d+)\. (.+)$", re.M)
_BLOCK = re.compile(r"^```json\n(.*?)^```", re.M | re.S)
_KEYS_LINE = re.compile(r"^\*\*Keys:\*\*(.*)$", re.M)
_KEY = re.compile(r"X9-[A-Z0-9]+-\d{3}")
STATUSES = ("tolerated", "pending")
PROOFS = ("go-test", "normalised")
DIFFERENCE_KEYS = {"test", "request", "field", "kind", "java", "go", "javaPattern", "goPattern",
                   "present", "mayNotOccur", "note"}
ONE_BUILD_KEYS = {"test", "recordedBy", "why", "mayNotOccur", "note"}


class ToleratedListError(ValueError):
    """The tolerated list cannot be read: a usage error, exit 2."""


def read_tolerated(path):
    """The entries of a tolerated list, each ``{"entry", "title", "keys", "status", "differences",
    "oneBuild", "proof"}``. Raises ToleratedListError, naming the entry, on anything malformed."""
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    headings = list(_ENTRY_HEADING.finditer(text))
    entries, seen = [], set()
    for i, heading in enumerate(headings):
        number, title = int(heading.group(1)), heading.group(2).strip()
        section = text[heading.end():headings[i + 1].start() if i + 1 < len(headings) else len(text)]
        where = f"{os.path.basename(path)}, entry {number}"
        if number in seen:
            raise ToleratedListError(f"{where}: the number is used twice")
        seen.add(number)
        blocks = _BLOCK.findall(section)
        if len(blocks) != 1:
            raise ToleratedListError(f"{where}: needs exactly one ```json matcher block, has "
                                     f"{len(blocks)}")
        try:
            spec = json.loads(blocks[0])
        except ValueError as e:
            raise ToleratedListError(f"{where}: the matcher is not JSON: {e}") from e
        keys_line = _KEYS_LINE.search(section)
        keys = _KEY.findall(keys_line.group(1)) if keys_line else []
        if not keys:
            raise ToleratedListError(f"{where}: names no test key (a **Keys:** line)")
        entries.append(_entry(where, number, title, keys, spec))
    if not entries:
        raise ToleratedListError(f"{path}: no entry (## N. Title, then a ```json block)")
    return entries


def _entry(where, number, title, keys, spec):
    if not isinstance(spec, dict):
        raise ToleratedListError(f"{where}: the matcher is not a JSON object")
    unknown = set(spec) - {"entry", "status", "differences", "oneBuild", "proof"}
    if unknown:
        raise ToleratedListError(f"{where}: unknown member(s) {sorted(unknown)}")
    if spec.get("entry") != number:
        raise ToleratedListError(f"{where}: the block says entry {spec.get('entry')}")
    status = spec.get("status", "tolerated")
    if status not in STATUSES:
        raise ToleratedListError(f"{where}: status must be one of {STATUSES}")
    differences = spec.get("differences") or []
    one_build = spec.get("oneBuild") or []
    proof = spec.get("proof")
    if proof is not None and proof not in PROOFS:
        raise ToleratedListError(f"{where}: proof must be one of {PROOFS}")
    if bool(proof) == bool(differences or one_build):
        raise ToleratedListError(f"{where}: give selectors (differences, oneBuild) or a proof, "
                                 "not both and not neither")
    for selectors, allowed in ((differences, DIFFERENCE_KEYS), (one_build, ONE_BUILD_KEYS)):
        for selector in selectors:
            if not isinstance(selector, dict) or "test" not in selector:
                raise ToleratedListError(f"{where}: every selector names its test (a regex)")
            extra = set(selector) - allowed
            if extra:
                raise ToleratedListError(f"{where}: unknown selector member(s) {sorted(extra)}")
            for name in ("test", "request", "field", "javaPattern", "goPattern"):
                if name in selector:
                    try:
                        re.compile(selector[name])
                    except re.error as e:
                        raise ToleratedListError(f"{where}: {name} is not a regex: {e}") from e
    return {"entry": number, "title": title, "keys": keys, "status": status,
            "differences": differences, "oneBuild": one_build, "proof": proof}


def _as_text(value):
    return value if isinstance(value, str) else json.dumps(value, sort_keys=True)


def _absent(value):
    return value is None or value == "<absent>"


def _selects_row(selector, row):
    if not re.search(selector["test"], row["test"]):
        return False
    if "kind" in selector and selector["kind"] != row["kind"]:
        return False
    for name in ("request", "field"):
        if name in selector and not re.fullmatch(selector[name], row[name] or ""):
            return False
    for side in ("java", "go"):
        if side in selector and not _same_value(selector[side], row[side]):
            return False
        pattern = selector.get(f"{side}Pattern")
        if pattern is not None and not re.fullmatch(pattern, _as_text(row[side]), re.S):
            return False
    if selector.get("present") and (_absent(row["java"]) or _absent(row["go"])):
        return False
    return True


def _same_value(expected, actual):
    return expected == actual and type(expected) is type(actual) or \
        (isinstance(expected, (int, float)) and isinstance(actual, (int, float))
         and not isinstance(expected, bool) and not isinstance(actual, bool) and expected == actual)


def _selects_one_build(selector, only):
    return bool(re.search(selector["test"], only["test"])) \
        and selector.get("recordedBy", only["recordedBy"]) == only["recordedBy"] \
        and selector.get("why", only["why"]) == only["why"]


def apply_tolerated(result, entries):
    """Marks each difference and each one-build test with the entry that tolerates it (``tolerated``:
    the entry's number, else None), and adds ``result["tolerated"]``: per entry, how much each of its
    selectors matched, and what is left to fail on (untolerated rows, stale selectors)."""
    counts = {e["entry"]: {"differences": [0] * len(e["differences"]),
                           "oneBuild": [0] * len(e["oneBuild"])} for e in entries}
    for row in result["differences"]:
        row["tolerated"] = _first_match(entries, "differences", _selects_row, row, counts)
    for only in result["onlyOneBuild"]:
        only["tolerated"] = _first_match(entries, "oneBuild", _selects_one_build, only, counts)
    report = []
    for e in entries:
        stale = [f"{group}[{i}]" for group in ("differences", "oneBuild")
                 for i, n in enumerate(counts[e["entry"]][group])
                 if n == 0 and not e[group][i].get("mayNotOccur")]
        report.append({"entry": e["entry"], "title": e["title"], "status": e["status"],
                       "keys": e["keys"], "proof": e["proof"],
                       "matched": sum(counts[e["entry"]]["differences"])
                       + sum(counts[e["entry"]]["oneBuild"]),
                       "selectors": counts[e["entry"]], "stale": stale})
    result["tolerated"] = {
        "entries": report,
        "untoleratedDifferences": sum(1 for r in result["differences"] if r["tolerated"] is None),
        "untoleratedOneBuild": sum(1 for o in result["onlyOneBuild"] if o["tolerated"] is None),
        "staleEntries": [r["entry"] for r in report if r["stale"]],
        "pendingMatched": [r["entry"] for r in report if r["status"] == "pending" and r["matched"]],
    }
    return result


def _first_match(entries, group, selects, item, counts):
    for e in entries:
        for i, selector in enumerate(e[group]):
            if selects(selector, item):
                counts[e["entry"]][group][i] += 1
                return e["entry"]
    return None


def failed(result):
    """Whether the run fails: an untolerated difference or one-build test, or a stale selector."""
    tolerated = result.get("tolerated")
    if tolerated is None:
        return bool(result["differences"] or result["onlyOneBuild"])
    return bool(tolerated["untoleratedDifferences"] or tolerated["untoleratedOneBuild"]
                or tolerated["staleEntries"])


# ------------------------------------------------------------------------------------------------
# Reporting

def _short(value, width=90):
    text = value if isinstance(value, str) else json.dumps(value, sort_keys=True)
    return text if len(text) <= width else text[:width - 1] + "…"


def _generalise(field):
    return re.sub(r"\[\d+\]", "[]", field)


def recurring(rows, top):
    """The most frequent differences, by kind, request, field and both values."""
    groups = collections.Counter()
    tests = collections.defaultdict(set)
    for row in rows:
        key = (row["kind"], row["request"] or "", _generalise(row["field"]),
               _short(row["java"]), _short(row["go"]))
        groups[key] += 1
        tests[key].add(row["test"])
    return [(count, key, len(tests[key])) for key, count in groups.most_common(top)]


def tolerated_report(result):
    """The tolerated list's part of the report: per entry, what it matched; pending entries loud."""
    tolerated = result.get("tolerated")
    if tolerated is None:
        return ["", "Tolerated list: not applied (--no-tolerated); every difference fails."]
    lines = ["", "Tolerated differences (entry, matched, title):"]
    for e in tolerated["entries"]:
        mark = "PENDING " if e["status"] == "pending" else ""
        how = f"proof: {e['proof']}" if e["proof"] else f"{e['matched']:6d}"
        stale = f"  STALE: {', '.join(e['stale'])} matched nothing" if e["stale"] else ""
        lines.append(f"  {e['entry']:3d}  {how:>6s}  {mark}{e['title']}{stale}")
    pending = [e for e in tolerated["entries"] if e["status"] == "pending"]
    if pending:
        lines += ["", "!" * 100,
                  f"PENDING, NEEDS THE OWNER: {len(pending)} difference(s) tolerated only until the "
                  "owner rules on them:"]
        lines += [f"  entry {e['entry']}: {e['title']} ({e['matched']} matched; keys "
                  f"{', '.join(e['keys'])})" for e in pending]
        lines += ["!" * 100]
    untolerated = [r for r in result["differences"] if r["tolerated"] is None]
    lone = [o for o in result["onlyOneBuild"] if o["tolerated"] is None]
    lines += ["", f"Not tolerated: {len(untolerated)} difference(s) in "
                  f"{len({r['test'] for r in untolerated})} test(s), {len(lone)} test(s) recorded "
                  f"by one build only; stale entries: {tolerated['staleEntries'] or 'none'}"]
    return lines


def text_report(result, top=25):
    s = result["summary"]
    lines = [
        "Differential run: Java against Go",
        f"buckets recorded: java {s['buckets']['java']}, go {s['buckets']['go']}, "
        f"both {s['buckets']['both']}",
        f"differences: {s['differences']} in {s['testsWithDifferences']} tests; "
        f"outcomes differ in {s['outcomesDiffer']} tests",
        "",
        "By kind:",
    ]
    lines += [f"  {count:6d}  {kind}" for kind, count in s["byKind"].items()] or ["  none"]
    if result["onlyOneBuild"]:
        lines += ["", "Recorded by one build only:"]
        lines += [f"  {e['recordedBy']:4s}  {e['test']}  ({e['why']}, {e['exchanges']} exchanges)"
                  + (f"  [entry {e['tolerated']}]" if e.get("tolerated") else "")
                  for e in result["onlyOneBuild"]]
    if result["outcomes"]:
        lines += ["", "Outcomes that differ:"]
        lines += [f"  java {o['java']:8s} go {o['go']:8s} {o['test']}" for o in result["outcomes"]]
    lines += tolerated_report(result)
    rows = [r for r in result["differences"] if r.get("tolerated") is None]
    if rows:
        lines += ["", f"Top {top} recurring differences no entry tolerates "
                      "(count, tests, kind, request, field):"]
        for count, (kind, request, field, java, go), ntests in recurring(rows, top):
            lines.append(f"  {count:5d} in {ntests:4d} tests  {kind}  {request}  {field}")
            lines.append(f"        java: {java}")
            lines.append(f"        go:   {go}")
        lines += ["", "Every difference no entry tolerates:"]
        for row in rows:
            lines.append(f"  {row['test']} #{row['javaIndex']}/{row['goIndex']} {row['kind']} "
                         f"{row['request']} {row['field']}: java {_short(row['java'], 120)} | "
                         f"go {_short(row['go'], 120)}")
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("java", help="the Java build's recording directory")
    parser.add_argument("go", help="the Go build's recording directory")
    parser.add_argument("--json", help="also write the differences as JSON to this file")
    parser.add_argument("--top", type=int, default=25, help="how many recurring differences")
    parser.add_argument("--summary-only", action="store_true",
                        help="print the summary and the recurring differences, not every row")
    parser.add_argument("--tolerated", default=TOLERATED_FILE,
                        help="the tolerated list (default: TOLERATED-DIFFERENCES.md beside this file)")
    parser.add_argument("--no-tolerated", action="store_true",
                        help="apply no tolerated list: every difference fails")
    args = parser.parse_args(argv)
    try:
        entries = None if args.no_tolerated else read_tolerated(args.tolerated)
    except (OSError, ToleratedListError) as e:
        print(f"the tolerated list cannot be read: {e}", file=sys.stderr)
        return 2
    result = compare(args.java, args.go)
    if entries is not None:
        apply_tolerated(result, entries)
    report = text_report(result, args.top)
    if args.summary_only:
        report = report.split("\nEvery difference")[0]
    print(report)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(result, handle, indent=2, ensure_ascii=False, default=str)
    return 1 if failed(result) else 0


if __name__ == "__main__":
    sys.exit(main())
