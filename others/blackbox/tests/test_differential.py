# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""The recorder and the diff of the differential run check themselves (Story 11.1). Offline.

A diff that masks too much passes two builds that answer differently; one that masks too little
drowns the real differences in per-run noise. So each masking rule is proved on its own, and so is
each kind of real difference it must still catch.
"""
import base64
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
    id, qrCodeId and the cursors).

    S11.1-AC2."""
    java = exchange(T, 0, body=f'{{\n  "id" : "{QR}",\n  "status" : "ACTIVE",\n'
                               f'  "detail" : "QR Code {QR} is closed",\n'
                               f'  "correlationId" : "anything",\n  "nextCursor" : "01M7H3J87Y9SCKZ341QNC5Q5X9"\n}}')
    go = exchange(T, 0, body=json.dumps({"nextCursor": "42", "correlationId": "else",
                                         "detail": f"QR Code {QR2} is closed",
                                         "status": "ACTIVE", "id": QR2}))
    assert diff(tmp_path, [java], [go])["differences"] == []


def test_times_and_dates_are_masked_but_their_offset_style_is_not(tmp_path):
    """X9-SUITE-519 — date-times and dates are masked, since every run has its own clock; whether
    an offset is written Z or numeric is kept, since that is a contract difference.

    S11.1-AC2."""
    same = diff(tmp_path / "a",
                [exchange(T, 0, body={"createdAt": "2026-11-16T02:39:37.083Z", "d": "2026-11-16"})],
                [exchange(T, 0, body={"createdAt": "2026-11-17T09:00:00Z", "d": "2026-12-01"})])
    assert same["differences"] == []

    offset = diff(tmp_path / "b",
                  [exchange(T, 0, body={"createdAt": "2026-11-16T02:39:37.083Z"})],
                  [exchange(T, 0, body={"createdAt": "2026-11-16T02:39:37.083+00:00"})])
    assert fields(offset) == [("body", "body.createdAt", "<time Z>", "<time ±hh:mm>")]


def test_a_signed_body_is_compared_on_its_decoded_header_and_payload(tmp_path):
    """X9-SUITE-520 — a compact JWS is compared as its decoded header, minus iat, correlationId and
    x5c, and its decoded payload; the signature is masked. A different payload or a different header
    member is still caught.

    S11.1-AC2."""
    def signed(payload, *, iat, cid, typ="JOSE", sig="AAAA"):
        return jws({"alg": "PS512", "typ": typ, "iat": iat, "correlationId": cid,
                    "x5c": [f"cert-{iat}"], "crit": ["correlationId", "iat"]}, payload, sig)

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


def test_the_command_line_writes_a_json_report_and_exits_on_what_it_found(tmp_path, capsys):
    """X9-SUITE-524 — the command line prints a readable report (counts by kind, the recurring
    differences) and writes the JSON report; it exits 0 when the recordings agree and 1 when they
    do not.

    S11.1-AC2."""
    agree_java = write_recording(tmp_path / "aj", [exchange(T, 0, body={"a": 1})])
    agree_go = write_recording(tmp_path / "ag", [exchange(T, 0, body={"a": 1})])
    assert differential.main([agree_java, agree_go]) == 0

    java = write_recording(tmp_path / "j", [exchange(T, 0, status=200)])
    go = write_recording(tmp_path / "g", [exchange(T, 0, status=500)])
    report = tmp_path / "differences.json"
    assert differential.main([java, go, "--json", str(report)]) == 1

    printed = capsys.readouterr().out
    assert "By kind:" in printed and "status" in printed and T in printed
    written = json.loads(report.read_text())
    assert written["summary"]["byKind"] == {"status": 1}
    assert written["differences"][0] | {} == {
        "kind": "status", "test": T, "javaIndex": 0, "goIndex": 0,
        "request": "GET /api/v1/payment-request/x", "field": "status", "java": 200, "go": 500}
