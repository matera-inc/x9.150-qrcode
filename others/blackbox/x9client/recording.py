# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""What the suite asked and what the service answered, written down per test (Story 11.1).

Off unless ``X9_RECORD_DIR`` names a directory (S11.1-AC1). When it is set, every exchange the
suite's HTTP client (``x9client.api``) makes is appended, in order, to one JSON-lines file per
*bucket*: the test node id that is running, or ``<session>::<fixture>`` while a session-scoped
fixture is being built (the trust probe, the JWK set), so that which test happens to build a shared
fixture first never shifts the exchanges of a test. ``others/blackbox/recorder.py`` tells this
module which bucket is current; ``others/blackbox/differential.py`` compares two recordings.

One line per exchange::

    {"test": "tests/test_qr_code.py::test_x", "index": 0, "phase": "call",
     "request": {"method": "POST", "path": "/api/v1/payment-request/<id>", "rawPath": "...",
                 "headers": {"Content-Type": "application/json"}, "body": {...}},
     "response": {"status": 201, "headers": {"ETag": "...", "Content-Type": "...",
                  "Location": "..."}, "body": "<the text as received>"}}

``path`` has the server-minted ids masked (``mask_path``); ``rawPath`` keeps them, so a reader can
follow one id across exchanges. A request body is kept parsed when it is JSON, else as text; the
answer's body is kept as the text received, because the diff decides how to read it. Nothing secret
passes through the client (the suite's payers sign locally; only their public certificates travel),
and a PEM private key, should one ever appear in a body, is replaced before it is written.

Every process also writes ``_tests.<worker>.jsonl``: each test's node id, outcome and build
markers, so the diff can name a test that only one build ran. Under pytest-xdist each worker has its
own index file and its own session buckets (``PYTEST_XDIST_WORKER``); a test runs in one worker, so
its file has one writer.
"""
import hashlib
import json
import os
import re
import threading

ENV = "X9_RECORD_DIR"

# The headers the differential run compares (ADR-0028) and the request headers that change an answer.
REQUEST_HEADERS = ("If-Match", "Content-Type", "Accept")
RESPONSE_HEADERS = ("ETag", "Content-Type", "Location")

# Server-minted ids as they appear in a path: the 32-character QR Code and location ids (a ULID's
# 128 bits as hexadecimal), a ULID in its own 26-character form, and a UUID. Masking is by shape, so a path the suite made up of the same shape is
# masked as well; both builds see the same made-up value, so nothing is lost.
_ULID = r"[0-9A-HJKMNP-TV-Z]{26}"
_UUID = r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
_LOC = r"[0-9A-Za-z]{32}"
_SEGMENT = re.compile(rf"(?<=/)(?:{_UUID}|{_ULID}|{_LOC})(?=/|\?|$)")
_QUERY_VALUE = re.compile(rf"(?<=[=])(?:{_UUID}|{_ULID}|{_LOC})(?=&|$)")
_PRIVATE_KEY = re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----",
                          re.S)

_lock = threading.Lock()
_state = {"bucket": None, "opened": set(), "counters": {}}


def directory():
    """The recording directory, or None when recording is off."""
    value = os.environ.get(ENV, "").strip()
    return value or None


def enabled():
    return directory() is not None


def worker():
    return os.environ.get("PYTEST_XDIST_WORKER", "main")


def mask_path(path):
    """``path`` with server-minted ids replaced by ``<id>`` (in segments and in query values)."""
    return _QUERY_VALUE.sub("<id>", _SEGMENT.sub("<id>", path))


def redact(text):
    """``text`` with any PEM private key replaced; the suite never sends one, this makes sure."""
    if isinstance(text, str) and "PRIVATE KEY-----" in text:
        return _PRIVATE_KEY.sub("<private key redacted>", text)
    return text


def file_name(bucket):
    """A file name for a bucket: readable, safe on every file system, and unique by its hash."""
    readable = re.sub(r"[^A-Za-z0-9._-]+", "_", bucket).strip("_")[:120]
    return f"{readable}-{hashlib.sha1(bucket.encode()).hexdigest()[:12]}.jsonl"


def set_bucket(bucket):
    """Make ``bucket`` current; returns the previous one, for the caller to put back."""
    with _lock:
        previous, _state["bucket"] = _state["bucket"], bucket
    return previous


def current_bucket():
    return _state["bucket"] or f"<outside any test>::{worker()}"


def _phase():
    # "tests/x.py::test_y (call)": pytest's own record of the phase it is in.
    current = os.environ.get("PYTEST_CURRENT_TEST", "")
    return current.rsplit("(", 1)[-1].rstrip(")") if current.endswith(")") else None


def _append(name, line, truncate_first):
    with open(os.path.join(directory(), name), "w" if truncate_first else "a",
              encoding="utf-8") as handle:
        handle.write(line + "\n")


def record(method, url, request_headers, request_data, status, response_headers, text):
    """Append one exchange to the current bucket. Never raises: recording must not fail a test."""
    if not enabled():
        return
    try:
        os.makedirs(directory(), exist_ok=True)
        path = re.sub(r"^https?://[^/]+", "", url)
        if isinstance(request_data, bytes):
            request_data = request_data.decode(errors="replace")
        try:
            request_body = json.loads(request_data) if request_data else None
        except ValueError:
            request_body = redact(request_data)
        wanted_request = {h.lower(): h for h in REQUEST_HEADERS}
        wanted_response = {h.lower(): h for h in RESPONSE_HEADERS}
        with _lock:
            bucket = current_bucket()
            index = _state["counters"].get(bucket, 0)
            _state["counters"][bucket] = index + 1
            first = bucket not in _state["opened"]
            _state["opened"].add(bucket)
            line = json.dumps({
                "test": bucket,
                "index": index,
                "phase": _phase(),
                "request": {
                    "method": method,
                    "path": mask_path(path),
                    "rawPath": path,
                    "headers": {wanted_request[k.lower()]: v for k, v in request_headers.items()
                                if k.lower() in wanted_request},
                    "body": request_body,
                },
                "response": {
                    "status": status,
                    "headers": {wanted_response[k.lower()]: v
                                for k, v in (response_headers or {}).items()
                                if k.lower() in wanted_response},
                    "body": redact(text),
                },
            }, ensure_ascii=False)
            _append(file_name(bucket), line, first)
    except Exception:                                   # noqa: BLE001 - never fail the test
        pass


def record_outcome(nodeid, outcome, markers):
    """One line in this process's test index: what became of the test, and its build markers."""
    if not enabled():
        return
    try:
        os.makedirs(directory(), exist_ok=True)
        with _lock:
            name = f"_tests.{worker()}.jsonl"
            first = name not in _state["opened"]
            _state["opened"].add(name)
            _append(name, json.dumps({"test": nodeid, "outcome": outcome, "markers": markers,
                                      "file": file_name(nodeid)}), first)
    except Exception:                                   # noqa: BLE001 - never fail the test
        pass
