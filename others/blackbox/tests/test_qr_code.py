# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""A generated QR Code is correct (acceptance.py section 1, folded in by Story 3.4).

The CRC decides whether a scanner accepts the code at all, so the EMV string is decoded field by
field with ``x9client.emv`` rather than searched for substrings.
"""
import base64
import json
import os
import re

import pytest

from x9client import emv
from x9client.samples import RECIPIENT


@pytest.fixture(scope="module")
def created(api):
    response = api.create_qr_detailed()
    assert response.status == 201, response
    return response.body


def test_the_id_is_32_uppercase_hex(created):
    """X9-LOC-507 — a QR Code's id is 32 upper-case hex characters.

    S3.4-AC1, section 1 — the id is 32 upper-case hex characters."""
    assert re.fullmatch(r"[0-9A-F]{32}", created["id"]), created["id"]


def test_the_emv_string_is_well_formed_and_its_crc_holds(created):
    """X9-LOC-508 — the EMV string is well formed: format indicator 01, honest TLV lengths, a valid
    CRC.

    S3.4-AC1, section 1 — format indicator 01, a valid CRC, every TLV length honest."""
    code = created["qrCode"]
    fields = emv.decode(code)

    assert code.startswith("000201") and fields["00"] == "01"
    assert emv.crc_is_valid(code), f"a wrong CRC makes every scanner reject the code: {code[-8:]}"


def test_the_x9_template_carries_the_location_url_within_99_characters(created):
    """X9-LOC-509 — the org.x9 template carries the loc URL naming this QR Code, within 99
    characters.

    S3.4-AC1, section 1 — the org.x9 GUI, the loc URL naming this QR Code, within the limit."""
    tag, template = emv.template_with(created["qrCode"], "org.x9")

    assert tag == "26", f"the X9 template is merchant account information 26: {tag}"
    raw = dict(emv.parse(created["qrCode"]))[tag]
    assert len(raw) <= 99, f"tag 26 exceeds the 99-character EMV limit: {len(raw)}"
    url = " ".join(v for k, v in template.items() if k != "00")
    assert f"/pub/api/v1/loc/{created['location']['id']}" in url, template


def test_qr_code_b64_is_exactly_the_emv_string(created):
    """X9-LOC-510 — qrCodeB64 decodes to the EMV string, byte for byte.

    S3.4-AC1, section 1 — qrCodeB64 decodes to the EMV string, byte for byte."""
    assert base64.b64decode(created["qrCodeB64"]).decode() == created["qrCode"]


def test_the_location_endpoint_is_https_and_names_the_location(created):
    """X9-LOC-511 — the advertised location endpoint is https and ends with the location id.

    S3.4-AC1, section 1 — the advertised endpoint is HTTPS and ends with the location id."""
    endpoint = created["location"]["endpoint"]
    assert endpoint.startswith("https://") and endpoint.endswith(created["location"]["id"]), endpoint


def test_it_reads_back_active_with_its_recipient(api, created):
    """X9-LOC-512 — a new QR Code reads back ACTIVE with its Solana recipient.

    S3.4-AC1, section 1 — ACTIVE, and the Solana recipient survives the round trip."""
    status, fetched = api.qr(created["id"])
    assert status == 200 and fetched["status"] == "ACTIVE", fetched
    assert fetched["paymentMethods"][0]["networks"]["solana"]["recipient"] == RECIPIENT


# --- Malformed EMV is refused, never tolerated (ADR-0027, EMV) ---------------------------------

def _with_crc(body):
    """``body`` with tag 63 appended and its CRC computed, as an encoder would write it."""
    body += "6304"
    return body + emv.crc16(body)


_GOLDEN = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "golden")
with open(os.path.join(_GOLDEN, "manifest.json")) as _manifest:
    _GOOD = json.load(_manifest)["emv"][0]["qrCode"]

_X9 = "26800006org.x90166x9-150.example.com/pub/api/v1/loc/01A1DC2A6A37D73D6313076E9BFE04F5"
_SHORT = _with_crc("000201")
MALFORMED, NO_X9 = "malformed", "no org.x9"

# The Go build's TestDecodeRefusesMalformedInput (internal/emv), case for case, by its names.
MALFORMED_EMV = [
    ("empty", "", MALFORMED),
    ("bad CRC", _GOOD[:-4] + "0000", MALFORMED),
    ("CRC not hex", _GOOD[:-4] + "ZZZZ", MALFORMED),
    ("no CRC", _GOOD[:-8], MALFORMED),
    ("CRC not last", _with_crc("6304AAAA" + "000201"), MALFORMED),
    ("CRC too short", _SHORT[:-8] + "6303ABC", MALFORMED),
    ("truncated", _GOOD[:-30], MALFORMED),
    ("truncated header", "000", MALFORMED),
    ("length beyond the end", "000501", MALFORMED),
    ("length not digits", _with_crc("00AB01"), MALFORMED),
    ("length zero", _with_crc("0000" + "000201"), MALFORMED),
    ("tag not digits", _with_crc("A00201"), MALFORMED),
    ("duplicate tag", _with_crc("000201000201" + _X9), MALFORMED),
    ("not UTF-8", b"\xff\xfe", MALFORMED),
    ("no template", _with_crc("000201010212"), NO_X9),
    ("wrong GUI", _with_crc("000201" + "26220006org.x80108x9.a/loc"), NO_X9),
    ("org.x9 without URL", _with_crc("000201" + "26100006org.x9"), NO_X9),
    ("template not TLV", _with_crc("000201" + "2603abc"), MALFORMED),
    ("template truncated", _with_crc("000201" + "26140006org.x90199"), MALFORMED),
]


@pytest.mark.parametrize("name,code,kind", MALFORMED_EMV, ids=[case[0] for case in MALFORMED_EMV])
def test_a_malformed_emv_is_refused_before_any_fetch(api, name, code, kind):
    """X9-DEC-016 — a malformed EMV string is refused by the decoder with a 400, before anything is
    fetched: the Go build's 19 malformed cases, by name.

    Source: Ours (owner decision 2026-11-15, ADR-0027 EMV section): "Don't tolerate bad QR Codes."
    EMVCo defines the TLV layout and tag 63's CRC16-CCITT; refusing a string that breaks them, on
    both builds alike, is ours.

    Why: Java's EMV library read a bad, missing or non-hex CRC and a truncated string as good and
    fetched the payload from the host the string named; a truncated header, a non-numeric length,
    a non-TLV template and bytes that are not UTF-8 escaped as a 500. A scanner rejects all of them.
    """
    if isinstance(code, bytes):
        response = api.call("POST", "/api/v1/qrcode-emv-decoder", raw=b'{"qrCode": "' + code + b'"}')
    else:
        response = api.call("POST", "/api/v1/qrcode-emv-decoder", body={"qrCode": code})

    text = response.body if isinstance(response.body, str) else json.dumps(response.body)
    assert response.status == 400, f"{name}: a malformed QR Code is the caller's fault: {response}"
    assert "payload-refused" not in text, f"{name}: nothing may be fetched for it: {text}"
    if isinstance(code, bytes):
        return  # bytes that are not UTF-8 are refused while the body is read, before the decoder
    expected = "The QR Code is malformed" if kind == MALFORMED else "does not contain a valid payload URL"
    assert expected in text, f"{name}: the refusal must say why ({expected}): {text}"
