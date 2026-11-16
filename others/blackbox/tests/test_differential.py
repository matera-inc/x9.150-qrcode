# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""The recorder and the diff of the differential run check themselves (Story 11.1). Offline.

A diff that masks too much passes two builds that answer differently; one that masks too little
drowns the real differences in per-run noise. So each masking rule is proved on its own, and so is
each kind of real difference it must still catch.
"""
import base64
import glob
import http.server
import json
import os
import threading

import pytest

import differential
from x9client import api as api_module
from x9client import recording

QR = "01A1E2396E7B1DF0D89E4D5457E9ABBB"
QR2 = "01A1E2397BD50D824C6760B9C6C69C13"


# ------------------------------------------------------------------------------------------ helpers

def _b64(obj):
    raw = json.dumps(obj).encode() if not isinstance(obj, bytes) else obj
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def jws(header, payload, signature="c2lnbmF0dXJl"):
    return f"{_b64(header)}.{_b64(payload)}.{signature}"


def exchange(test, index, method="GET", path="/api/v1/payment-request/x", status=200, body=None,
             headers=None, raw_path=None):
    text = body if isinstance(body, str) or body is None else json.dumps(body)
    return {"test": test, "index": index, "phase": "call",
            "request": {"method": method, "path": recording.mask_path(raw_path or path),
                        "rawPath": raw_path or path, "headers": {}, "body": None},
            "response": {"status": status, "headers": headers or {}, "body": text}}


def write_recording(directory, exchanges, tests=()):
    os.makedirs(directory, exist_ok=True)
    by_test = {}
    for e in exchanges:
        by_test.setdefault(e["test"], []).append(e)
    for test, lines in by_test.items():
        with open(os.path.join(directory, recording.file_name(test)), "w") as handle:
            handle.writelines(json.dumps(line) + "\n" for line in lines)
    with open(os.path.join(directory, "_tests.main.jsonl"), "w") as handle:
        handle.writelines(json.dumps(t) + "\n" for t in tests)
    return str(directory)


def diff(tmp_path, java, go, java_tests=(), go_tests=()):
    return differential.compare(write_recording(tmp_path / "java", java, java_tests),
                                write_recording(tmp_path / "go", go, go_tests))


def fields(result):
    return [(row["kind"], row["field"], row["java"], row["go"]) for row in result["differences"]]


T = "tests/test_x.py::test_y"


# ------------------------------------------------------------------------------------------ recorder

class _Echo(http.server.BaseHTTPRequestHandler):

    def _answer(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        out = json.dumps({"id": QR, "echo": body.decode() or None}).encode()
        self.send_response(201 if self.command == "POST" else 200)
        self.send_header("Content-Type", "application/json")
        self.send_header("ETag", '"0-ACTIVE"')
        self.send_header("Location", f"/api/v1/payment-request/{QR}")
        self.send_header("X-Other", "not recorded")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    do_GET = do_POST = do_PATCH = _answer

    def log_message(self, *args):
        pass


@pytest.fixture
def echo_server():
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _Echo)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()


def test_the_recorder_writes_every_exchange_in_order_and_nothing_when_unset(
        tmp_path, monkeypatch, echo_server):
    """X9-SUITE-516 — with X9_RECORD_DIR set the suite's client writes each exchange of a test in
    order (method, masked path, the request headers that matter, request body, status, ETag,
    Content-Type, Location, body) to that test's file; unset, it writes nothing and answers alike.

    S11.1-AC1."""
    client = api_module.Api(echo_server)
    monkeypatch.delenv(recording.ENV, raising=False)
    unrecorded = client.post("/api/v1/payment-request", body={"a": 1})
    assert not any(tmp_path.iterdir())

    monkeypatch.setenv(recording.ENV, str(tmp_path))
    previous = recording.set_bucket("tests/test_a.py::test_b")
    try:
        recorded = client.post("/api/v1/payment-request", body={"a": 1})
        client.call("PATCH", f"/api/v1/payment-request/{QR}", body={"b": 2}, if_match='"0-ACTIVE"')
        client.post_jose("/pub/api/v1/payment-notification",
                         "x -----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY----- y")
    finally:
        recording.set_bucket(previous)

    assert (recorded.status, recorded.body) == (unrecorded.status, unrecorded.body)
    lines = [json.loads(line) for line in
             (tmp_path / recording.file_name("tests/test_a.py::test_b")).read_text().splitlines()]
    assert [line["index"] for line in lines] == [0, 1, 2]
    first, second, third = lines
    assert first["test"] == "tests/test_a.py::test_b"
    assert first["request"]["method"] == "POST" and first["request"]["body"] == {"a": 1}
    assert first["response"]["status"] == 201
    assert first["response"]["headers"] == {"Content-Type": "application/json", "ETag": '"0-ACTIVE"',
                                            "Location": f"/api/v1/payment-request/{QR}"}
    assert json.loads(first["response"]["body"])["id"] == QR
    assert second["request"]["path"] == "/api/v1/payment-request/<id>"
    assert second["request"]["rawPath"] == f"/api/v1/payment-request/{QR}"
    assert second["request"]["headers"] == {"Content-Type": "application/json",
                                            "If-Match": '"0-ACTIVE"'}
    assert "PRIVATE KEY" not in (tmp_path / recording.file_name("tests/test_a.py::test_b")).read_text()
    assert third["request"]["headers"]["Content-Type"] == "application/jose"


def test_the_recorder_masks_minted_ids_in_a_path():
    """X9-SUITE-517 — a path keeps its shape with every server-minted id masked: the 32-character
    QR Code and location ids, a ULID, a UUID, in a segment or a query value.

    S11.1-AC1, S11.1-AC2."""
    assert recording.mask_path(f"/api/v1/payment-request/{QR}/status-update") == \
        "/api/v1/payment-request/<id>/status-update"
    assert recording.mask_path(f"/pub/api/v1/loc/{QR}") == "/pub/api/v1/loc/<id>"
    assert recording.mask_path("/pub/api/v1/events?after=01M7H3J87Y9SCKZ341QNC5Q5X9&limit=5") == \
        "/pub/api/v1/events?after=<id>&limit=5"
    assert recording.mask_path("/x/2819a833-f3ab-49f3-9332-3ef3e64d5314") == "/x/<id>"
    assert recording.mask_path("/api/v1/payment-request") == "/api/v1/payment-request"
    # The diff also masks a cursor that is not an id (Go's is a decimal sequence number).
    assert differential.mask_path("/pub/api/v1/events?after=42&limit=500") == \
        "/pub/api/v1/events?after=<cursor>&limit=500"


# ------------------------------------------------------------------------------------------ masking

def test_json_is_compared_as_values_not_text(tmp_path):
    """X9-SUITE-518 — JSON is re-serialised: whitespace and key order never count, and minted ids
    are masked wherever they appear (by shape in any string, by name for correlationId, eventId,
    id, qrCodeId and the cursors); violations are compared as a set, their content still counted.

    S11.1-AC2."""
    java = exchange(T, 0, body=f'{{\n  "id" : "{QR}",\n  "status" : "ACTIVE",\n'
                               f'  "detail" : "QR Code {QR} is closed",\n'
                               f'  "correlationId" : "anything",\n  "nextCursor" : "01M7H3J87Y9SCKZ341QNC5Q5X9"\n}}')
    go = exchange(T, 0, body=json.dumps({"nextCursor": "42", "correlationId": "else",
                                         "detail": f"QR Code {QR2} is closed",
                                         "status": "ACTIVE", "id": QR2}))
    assert diff(tmp_path / "a", [java], [go])["differences"] == []

    # Violations are a set: their order changes from run to run in Java; their content counts.
    one, two = {"field": "a", "message": "x"}, {"field": "b", "message": "y"}
    assert diff(tmp_path / "b", [exchange(T, 0, body={"violations": [one, two]})],
                [exchange(T, 0, body={"violations": [two, one]})])["differences"] == []
    changed = diff(tmp_path / "c", [exchange(T, 0, body={"violations": [one, two]})],
                   [exchange(T, 0, body={"violations": [one]})])
    assert fields(changed) == [("body", "body.violations[1]", two, "<absent>")]


def test_times_and_dates_are_masked_but_their_offset_style_is_not(tmp_path):
    """X9-SUITE-519 — date-times, dates and epoch milliseconds inside a text are masked, since
    every run has its own clock (a number member keeps its value); whether an offset is written Z
    or numeric is kept, since that is a contract difference.

    S11.1-AC2."""
    same = diff(tmp_path / "a",
                [exchange(T, 0, body={"createdAt": "2026-11-16T02:39:37.083Z", "d": "2026-11-16",
                                      "detail": "expired at 1790813101072", "amount": 1790813101072})],
                [exchange(T, 0, body={"createdAt": "2026-11-17T09:00:00Z", "d": "2026-12-01",
                                      "detail": "expired at 1794796413114", "amount": 1790813101072})])
    assert same["differences"] == []

    offset = diff(tmp_path / "b",
                  [exchange(T, 0, body={"createdAt": "2026-11-16T02:39:37.083Z"})],
                  [exchange(T, 0, body={"createdAt": "2026-11-16T02:39:37.083+00:00"})])
    assert fields(offset) == [("body", "body.createdAt", "<time Z>", "<time ±hh:mm>")]


def test_a_signed_body_is_compared_on_its_decoded_header_and_payload(tmp_path):
    """X9-SUITE-520 — a compact JWS is compared as its decoded header, minus iat, correlationId,
    x5c and the certificate thumbprint, with crit as a set, and its decoded payload; the signature
    is masked. A different payload or a different header
    member is still caught.

    S11.1-AC2."""
    def signed(payload, *, iat, cid, typ="JOSE", sig="AAAA"):
        crit = ["correlationId", "iat"] if iat % 2 else ["iat", "correlationId"]   # a set
        return jws({"alg": "PS512", "typ": typ, "iat": iat, "correlationId": cid,
                    "x5c": [f"cert-{iat}"], "x5t#S256": f"thumb-{iat}", "crit": crit},
                   payload, sig)

    same = diff(tmp_path / "a",
                [exchange(T, 0, body=signed({"statusCode": 200}, iat=1, cid="a", sig="AAAA"))],
                [exchange(T, 0, body=signed({"statusCode": 200}, iat=2, cid="b", sig="BBBB"))])
    assert same["differences"] == []

    payload = diff(tmp_path / "b",
                   [exchange(T, 0, body=signed({"statusCode": 200}, iat=1, cid="a"))],
                   [exchange(T, 0, body=signed({"statusCode": 400}, iat=1, cid="a"))])
    assert fields(payload) == [("body", "body.<jws>.payload.statusCode", 200, 400)]

    header = diff(tmp_path / "c",
                  [exchange(T, 0, body=signed({"statusCode": 200}, iat=1, cid="a"))],
                  [exchange(T, 0, body=signed({"statusCode": 200}, iat=1, cid="a", typ="JWT"))])
    assert fields(header) == [("body", "body.<jws>.header.typ", "JOSE", "JWT")]


def test_an_emv_string_is_compared_without_its_location_and_crc(tmp_path):
    """X9-SUITE-521 — an EMV string's location id and CRC are masked, in the string and inside
    qrCodeB64, which is decoded; any other change to the EMV string is still caught.

    S11.1-AC2."""
    def emv(loc, crc, city="Los Angeles"):
        return (f"00020101021226800006org.x90166x9-150.example.com/pub/api/v1/loc/{loc}"
                f"520449005303840540105802US5911Blackbox Co60{len(city):02d}{city}6304{crc}")

    def body(loc, crc, city="Los Angeles"):
        text = emv(loc, crc, city)
        return {"qrCode": text, "qrCodeB64": base64.b64encode(text.encode()).decode(),
                "location": {"endpoint": f"https://x9-150.example.com/pub/api/v1/loc/{loc}"}}

    same = diff(tmp_path / "a", [exchange(T, 0, body=body(QR, "1CB6"))],
                [exchange(T, 0, body=body(QR2, "F7C7"))])
    assert same["differences"] == []

    city = diff(tmp_path / "b", [exchange(T, 0, body=body(QR, "1CB6"))],
                [exchange(T, 0, body=body(QR2, "F7C7", city="Los Angelos"))])
    assert {f for _, f, _, _ in fields(city)} == {"body.qrCode", "body.qrCodeB64.<base64>"}

    # Quoted inside a refusal's text, as an EMV string or as its base64, the same holds.
    def refusal(loc, crc):
        text = emv(loc, crc)
        return {"violations": [f"qrCode: cannot be changed (stored {text})",
                               f"qrCodeB64: cannot be changed (stored "
                               f"{base64.b64encode(text.encode()).decode()})"]}

    quoted = diff(tmp_path / "c", [exchange(T, 0, body=refusal(QR, "1CB6"))],
                  [exchange(T, 0, body=refusal(QR2, "F7C7"))])
    assert quoted["differences"] == []


# ------------------------------------------------------------------------------------------ catching

def test_a_real_difference_is_caught_and_says_where(tmp_path):
    """X9-SUITE-522 — what normalising must never hide: a changed status, a changed violation text,
    a member added, a member dropped, a number sent as a string, a different ETag, Content-Type or
    Location; each reported with its test, exchange index, field and both values.

    S11.1-AC2, S11.1-AC4."""
    java = exchange(T, 3, status=400, headers={"Content-Type": "application/problem+json",
                                               "ETag": '"1-ACTIVE"', "Location": f"/x/{QR}"},
                    body={"status": 400, "amount": 1000, "kept": True,
                          "violations": [{"field": "payment.amount", "message": "must be 1000"}]})
    go = exchange(T, 3, status=422, headers={"Content-Type": "application/json",
                                             "ETag": '"2-ACTIVE"', "Location": f"/y/{QR2}"},
                  body={"status": 400, "amount": "1000", "added": 1,
                        "violations": [{"field": "payment.amount", "message": "must equal 1000"}]})

    result = diff(tmp_path, [java], [go])

    assert fields(result) == [
        ("status", "status", 400, 422),
        ("header:ETag", "header:ETag", '"1-ACTIVE"', '"2-ACTIVE"'),
        ("header:Content-Type", "header:Content-Type", "application/problem+json",
         "application/json"),
        ("header:Location", "header:Location", "/x/<id>", "/y/<id>"),
        ("body", "body.amount", 1000, "1000"),
        ("body", "body.kept", True, "<absent>"),
        ("body", "body.violations[0].message", "must be 1000", "must equal 1000"),
        ("body", "body.added", "<absent>", 1),
    ]
    assert {(r["test"], r["javaIndex"], r["goIndex"]) for r in result["differences"]} == {(T, 3, 3)}
    # Equal numbers in two notations are one value; a boolean is never a number.
    assert differential.diff_values({"a": 10, "b": 1}, {"a": 10.0, "b": True}) == \
        [("body.b", 1, True)]


# ------------------------------------------------------------------------------------------ pairing

def test_tests_and_exchanges_present_on_one_side_are_named_and_the_rest_stays_aligned(tmp_path):
    """X9-SUITE-523 — a test only one build recorded is reported by name and why (its go_only or
    java_only marker, else its outcome); a test with an extra exchange reports that exchange and its
    count, and every later exchange still pairs with its counterpart; a run of polls of the event
    stream counts once, and an event page keeps only the events of this test's QR Codes.

    S11.1-AC2, S11.1-AC3."""
    only_go = "tests/test_g.py::test_go_only"
    only_java = "tests/test_j.py::test_skipped_on_go"
    create = lambda t, i, qr: exchange(t, i, "POST", "/api/v1/payment-request", 201,  # noqa: E731
                                       body={"id": qr})
    get = lambda t, i, qr, status: exchange(t, i, raw_path=f"/api/v1/payment-request/{qr}",  # noqa: E731
                                            body={"status": status})

    def page(t, i, events, after):
        return exchange(t, i, raw_path=f"/pub/api/v1/events?after={after}&limit=500&wait=0",
                        body={"events": events, "nextCursor": after})

    java = [create(T, 0, QR), page(T, 1, [], "A"), page(T, 2, [], "A"),
            page(T, 3, [{"qrCodeId": QR, "type": "payment.initiated"},
                        {"qrCodeId": "F" * 32, "type": "payment.cancelled"}], "B"),
            get(T, 4, QR, "PAYMENT_INITIATED"), get(T, 5, QR, "PAID"),
            exchange(only_java, 0)]
    go = [create(T, 0, QR2), page(T, 1, [{"qrCodeId": QR2, "type": "payment.initiated"}], "7"),
          get(T, 2, QR2, "PAYMENT_INITIATED"),
          exchange(T, 3, "PUT", f"/api/v1/payment-request/{QR2}/status-update"),   # extra
          get(T, 4, QR2, "CANCELLED"),
          exchange(only_go, 0), exchange(only_go, 1)]

    result = diff(tmp_path, java, go,
                  java_tests=[{"test": only_go, "outcome": "skipped", "markers": ["go_only"]}],
                  go_tests=[{"test": only_java, "outcome": "skipped", "markers": []}])

    assert result["onlyOneBuild"] == [
        {"test": only_go, "recordedBy": "go", "why": "go_only", "exchanges": 2},
        {"test": only_java, "recordedBy": "java", "why": "skipped", "exchanges": 1},
    ]
    rows = [(r["kind"], r["javaIndex"], r["goIndex"], r["field"], r["java"], r["go"])
            for r in result["differences"]]
    assert rows == [
        ("exchange-count", None, None, "exchanges", 4, 5),
        ("go-only-exchange", None, 3, "request", "<absent>",
         "PUT /api/v1/payment-request/<id>/status-update"),
        ("body", 5, 4, "body.status", "PAID", "CANCELLED"),
    ]

    # Two QR Codes: across them the stream's order is timing; for each one it is the contract.
    a, b = "A" * 32, "B" * 32
    ev = lambda qr, kind: {"qrCodeId": qr, "type": kind}  # noqa: E731
    t2 = "tests/test_x.py::test_two"
    java2 = [create(t2, 0, a), create(t2, 1, b),
             page(t2, 2, [ev(a, "x"), ev(b, "x"), ev(a, "y"), ev(b, "y")], "1")]
    across = [create(t2, 0, a), create(t2, 1, b),
              page(t2, 2, [ev(b, "x"), ev(a, "x"), ev(b, "y"), ev(a, "y")], "1")]
    within = [create(t2, 0, a), create(t2, 1, b),
              page(t2, 2, [ev(a, "y"), ev(b, "x"), ev(a, "x"), ev(b, "y")], "1")]
    assert diff(tmp_path / "across", java2, across)["differences"] == []
    assert [r["field"] for r in diff(tmp_path / "within", java2, within)["differences"]] == \
        ["body.events[0].type", "body.events[1].type"]


def test_the_command_line_writes_a_json_report_and_exits_on_what_it_found(tmp_path, capsys):
    """X9-SUITE-524 — the command line prints a readable report (counts by kind, the recurring
    differences) and writes the JSON report; it exits 0 when the recordings agree and 1 when they
    do not.

    S11.1-AC2."""
    agree_java = write_recording(tmp_path / "aj", [exchange(T, 0, body={"a": 1})])
    agree_go = write_recording(tmp_path / "ag", [exchange(T, 0, body={"a": 1})])
    assert differential.main([agree_java, agree_go, "--no-tolerated"]) == 0

    java = write_recording(tmp_path / "j", [exchange(T, 0, status=200)])
    go = write_recording(tmp_path / "g", [exchange(T, 0, status=500)])
    report = tmp_path / "differences.json"
    assert differential.main([java, go, "--json", str(report), "--no-tolerated"]) == 1

    printed = capsys.readouterr().out
    assert "By kind:" in printed and "status" in printed and T in printed
    written = json.loads(report.read_text())
    assert written["summary"]["byKind"] == {"status": 1}
    assert written["differences"][0] | {} == {
        "kind": "status", "test": T, "javaIndex": 0, "goIndex": 0,
        "request": "GET /api/v1/payment-request/x", "field": "status", "java": 200, "go": 500}


# ------------------------------------------------------------------------------------- tolerated list

def tolerated(tmp_path, *entries):
    """A tolerated list of ``(number, title, keys, matcher)`` entries, written as the real one is."""
    text = "# Tolerated\n\nPreamble text.\n"
    for number, title, keys, matcher in entries:
        text += (f"\n## {number}. {title}\n\n**Reason:** because.\n\n**Keys:** "
                 + ", ".join(f"`{k}`" for k in keys)
                 + f"\n\n```json\n{json.dumps(dict(matcher, entry=number))}\n```\n")
    path = tmp_path / "TOLERATED-DIFFERENCES.md"
    path.write_text(text)
    return str(path)


ETAG = {"differences": [{"test": ".", "kind": "header:ETag", "request": "POST /api/v1/payment-request",
                         "java": None, "goPattern": '"0-ACTIVE"'}]}


def test_the_tolerated_list_is_read_and_a_malformed_one_is_refused_by_entry(tmp_path):
    """X9-SUITE-525 — the tolerated list is read entry by entry (number, title, keys, status,
    selectors, proof), and one that cannot be applied as written is refused, naming the entry: no
    key, no matcher or two, a matcher that is not JSON, a wrong entry number, a selector without its
    test, an unknown member, a regex that does not compile, or both selectors and a proof.

    S11.2-AC1."""
    path = tolerated(tmp_path, (9, "ETag", ["X9-PATCH-521"], ETAG),
                     (13, "Numbers", ["X9-SIG-534"], {"proof": "go-test"}),
                     (27, "Cursor", ["X9-EVT-056"], {"status": "pending", "differences": [{"test": "x"}]}))
    entries = differential.read_tolerated(path)
    assert [(e["entry"], e["title"], e["keys"], e["status"], e["proof"]) for e in entries] == [
        (9, "ETag", ["X9-PATCH-521"], "tolerated", None),
        (13, "Numbers", ["X9-SIG-534"], "tolerated", "go-test"),
        (27, "Cursor", ["X9-EVT-056"], "pending", None)]

    good = "## 1. A\n\n**Keys:** `X9-SUITE-518`\n\n```json\n{BODY}\n```\n"
    for body, complaint in (
            ('{"entry": 1, "proof": "go-test"}\n```\n```json\n{"entry": 1, "proof": "go-test"}',
             "exactly one"),
            ('{"entry": 1, proof}', "not JSON"),
            ('{"entry": 2, "proof": "go-test"}', "says entry 2"),
            ('{"entry": 1, "differences": [{"field": "x"}]}', "names its test"),
            ('{"entry": 1, "differences": [{"test": "x", "feild": "y"}]}', "unknown selector"),
            ('{"entry": 1, "matchers": []}', "unknown member"),
            ('{"entry": 1, "differences": [{"test": "("}]}', "not a regex"),
            ('{"entry": 1, "proof": "go-test", "oneBuild": [{"test": "x"}]}', "not both"),
            ('{"entry": 1}', "not both and not neither"),
            ('{"entry": 1, "status": "maybe", "proof": "go-test"}', "status")):
        bad = tmp_path / "bad.md"
        bad.write_text(good.replace("{BODY}", body))
        with pytest.raises(differential.ToleratedListError, match=complaint):
            differential.read_tolerated(str(bad))
    keyless = tmp_path / "keyless.md"
    keyless.write_text('## 1. A\n\n```json\n{"entry": 1, "proof": "go-test"}\n```\n')
    with pytest.raises(differential.ToleratedListError, match="names no test key"):
        differential.read_tolerated(str(keyless))


def test_a_tolerated_difference_passes_and_any_other_still_fails(tmp_path):
    """X9-SUITE-526 — a difference an entry's selector matches (by test, request, field, kind, the
    exact values, a pattern, or both sides present) is tolerated and named by its entry; one no
    selector matches still fails the run, and so does a test recorded by one build only that no
    oneBuild selector names.

    S11.2-AC2: "fails on any difference that no tolerated entry matches"."""
    path = tolerated(
        tmp_path, (9, "ETag", ["X9-PATCH-521"], ETAG),
        (24, "Wording", ["X9-SIG-501"], {"differences": [
            {"test": "test_y$", "field": r"body\.signatureDetail", "present": True}]}),
        (2, "Kept", ["X9-TIME-014"], {"oneBuild": [{"test": "test_go_only$", "recordedBy": "go",
                                                    "why": "go_only"}]}))
    created = {"Content-Type": "application/json"}
    java = [exchange(T, 0, "POST", "/api/v1/payment-request", 201, {"a": 1}, created),
            exchange(T, 1, body={"signatureDetail": "JWS signature verification failed."}),
            exchange(T, 2, body={"signatureDetail": None})]
    go = [exchange(T, 0, "POST", "/api/v1/payment-request", 201, {"a": 1},
                   dict(created, ETag='"0-ACTIVE"')),
          exchange(T, 1, body={"signatureDetail": "the signature does not verify"}),
          exchange(T, 2, body={})]
    lone = "tests/test_x.py::test_go_only"
    lone_tests = [{"test": lone, "outcome": "skipped", "markers": ["go_only"]}]
    result = diff(tmp_path, java, go + [exchange(lone, 0)], java_tests=lone_tests)
    differential.apply_tolerated(result, differential.read_tolerated(path))

    assert [(r["javaIndex"], r["field"], r["tolerated"]) for r in result["differences"]] == [
        (0, "header:ETag", 9), (1, "body.signatureDetail", 24), (2, "body.signatureDetail", None)], \
        "the wording is tolerated; a null against an absent member is not: present is required"
    assert result["onlyOneBuild"][0]["tolerated"] == 2
    assert result["tolerated"]["untoleratedDifferences"] == 1
    assert differential.failed(result)

    go[2] = exchange(T, 2, body={"signatureDetail": None})
    result = diff(tmp_path / "again", java, go + [exchange(lone, 0)], java_tests=lone_tests)
    differential.apply_tolerated(result, differential.read_tolerated(path))
    assert not differential.failed(result), result["tolerated"]

    result = diff(tmp_path / "lone", java, go + [exchange("tests/test_x.py::test_other", 0)])
    differential.apply_tolerated(result, differential.read_tolerated(path))
    assert result["tolerated"]["untoleratedOneBuild"] == 1 and differential.failed(result)


def test_an_entry_that_matched_nothing_is_stale_and_fails(tmp_path):
    """X9-SUITE-527 — a selector that matched nothing in the run is stale and fails it, so a
    difference that has gone away leaves the list; a selector marked mayNotOccur (which racer wins
    is per run) and an entry proved by a Go test or by the normaliser are never stale.

    S11.2-AC2: "and on any tolerated entry that matched nothing (stale)"."""
    path = tolerated(
        tmp_path, (9, "ETag", ["X9-PATCH-521"], ETAG),
        (26, "Racer", ["X9-HOLD-406"], {"differences": [
            {"test": "racer", "field": "body.payerInfo", "mayNotOccur": True}]}),
        (13, "Numbers", ["X9-SIG-534"], {"proof": "go-test"}),
        (1, "Whitespace", ["X9-SUITE-518"], {"proof": "normalised"}))
    same = [exchange(T, 0, body={"a": 1})]
    result = differential.apply_tolerated(diff(tmp_path, same, same),
                                          differential.read_tolerated(path))
    assert result["tolerated"]["staleEntries"] == [9]
    assert [e["stale"] for e in result["tolerated"]["entries"]] == [["differences[0]"], [], [], []]
    assert differential.failed(result)


def test_a_pending_entry_is_tolerated_and_reported_loudly(tmp_path, capsys):
    """X9-SUITE-528 — an entry the owner has not ruled on (status pending) tolerates its difference,
    and every report says so in capitals with its keys; the command line exits 0 when every
    difference is tolerated, 1 on an untolerated or stale one, and 2 on a list it cannot read.

    S11.2-AC2, AC3 (none left failing; what is not decided is said, not hidden)."""
    path = tolerated(tmp_path, (27, "Cursor past the log", ["X9-EVT-056"], {
        "status": "pending", "differences": [{"test": ".", "field": "body.nextCursor"}]}))
    java = write_recording(tmp_path / "j", [exchange(T, 0, body={"hasMore": False})])
    go = write_recording(tmp_path / "g", [exchange(T, 0, body={"hasMore": False, "nextCursor": "7"})])
    assert differential.main([java, go, "--tolerated", path]) == 0
    printed = capsys.readouterr().out
    assert "PENDING, NEEDS THE OWNER" in printed and "entry 27: Cursor past the log" in printed
    assert "X9-EVT-056" in printed

    assert differential.main([java, go, "--no-tolerated"]) == 1
    capsys.readouterr()
    same = write_recording(tmp_path / "s", [exchange(T, 0, body={"hasMore": False})])
    assert differential.main([java, same, "--tolerated", path]) == 1, "stale: nothing to tolerate"
    broken = tmp_path / "broken.md"
    broken.write_text("nothing here")
    assert differential.main([java, go, "--tolerated", str(broken)]) == 2


def test_the_repositorys_tolerated_list_takes_over_every_row_of_adr_0028():
    """X9-SUITE-529 — the repository's TOLERATED-DIFFERENCES.md reads, holds an entry for each of
    ADR-0028's differences (1 to 22, 8 retired), each naming at least one test key, and ADR-0028
    points to it instead of keeping its own table.

    S11.2-AC1."""
    entries = differential.read_tolerated(differential.TOLERATED_FILE)
    numbers = {e["entry"] for e in entries}
    assert set(range(1, 23)) - {8} <= numbers, sorted(set(range(1, 23)) - {8} - numbers)
    assert 8 not in numbers
    assert all(e["keys"] for e in entries)
    found = glob.glob(os.path.join(differential.HERE, "..", "..", "docs", "adr", "0028-*.md"))
    if found:                                   # the suite's container stages only ADR-0035
        text = open(found[0], encoding="utf-8").read()
        assert "TOLERATED-DIFFERENCES.md" in text
        assert "| 16 | GET `paymentNotification.data`" not in text, "the table moved to the list"
