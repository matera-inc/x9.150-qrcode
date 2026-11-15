# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""A generated QR Code is correct (acceptance.py section 1, folded in by Story 3.4).

The CRC decides whether a scanner accepts the code at all, so the EMV string is decoded field by
field with ``x9client.emv`` rather than searched for substrings.
"""
import base64
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
