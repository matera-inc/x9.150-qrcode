# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""x9client checks itself: the parts that need no deployment.

A helper that decodes wrongly makes every test built on it wrong in the same direction, so the
helpers get their own known answers before any test leans on them.
"""
import pytest

from x9client import api as api_module
from x9client import clock as clock_module
from x9client import db as db_module
from x9client import emv


class TestEmv:

    def test_crc16_matches_the_published_check_value(self):
        """S3.1-AC1 — CRC-16/CCITT-FALSE of "123456789" is 29B1, its published check value."""
        assert emv.crc16("123456789") == "29B1"

    def test_a_well_formed_string_decodes_and_its_crc_holds(self):
        """S3.1-AC1 — TLV decoding, one template level down, and the CRC check."""
        body = "000201" + "010212" + "2620" + "0006org.x9" + "0106abcdef" + "5303840" + "6304"
        sample = body + emv.crc16(body)

        decoded = emv.decode(sample)

        assert decoded["00"] == "01"
        assert decoded["26"] == {"00": "org.x9", "01": "abcdef"}
        assert emv.crc_is_valid(sample)
        assert not emv.crc_is_valid(sample[:-1] + ("0" if sample[-1] != "0" else "1"))
        assert emv.template_with(sample, "org.x9") == ("26", {"00": "org.x9", "01": "abcdef"})

    def test_a_truncated_string_is_an_error_not_a_guess(self):
        """S3.1-AC1 — a length that runs past the end is refused, not padded."""
        with pytest.raises(emv.EmvError):
            emv.parse("000201" + "2699" + "short")


class TestApi:

    def test_a_response_unpacks_as_status_and_body_and_keeps_its_headers(self):
        """S3.1-AC1 — ETag and problem+json are read from what came back."""
        response = api_module.Response(
            412, {"type": "about:blank", "title": "Precondition Failed", "status": 412,
                  "violations": [{"field": "If-Match", "message": "stale"}]},
            {"ETag": '"3"', "Content-Type": "application/problem+json"})

        status, body = response
        assert status == 412 and body["status"] == 412
        assert response.etag == '"3"'
        assert response.problem["title"] == "Precondition Failed"
        assert response.violations == [{"field": "If-Match", "message": "stale"}]

    def test_nothing_listening_is_status_zero_not_an_exception(self):
        """S3.1-AC1 — a transport failure is reported, so the suite can skip with one line."""
        status, body = api_module.Api("http://127.0.0.1:9", timeout=2).get("/actuator/health")
        assert status == 0 and body


class TestOptionalCapabilities:

    def test_without_a_database_url_the_reason_is_stated(self, monkeypatch):
        """S3.1-AC3 — X9_DATABASE_URL unset: no inspector, and a reason a skip can print."""
        monkeypatch.delenv(db_module.ENV, raising=False)
        inspector, reason = db_module.connect()
        assert inspector is None and db_module.ENV in reason

    def test_a_database_url_this_suite_cannot_read_is_a_reason_not_a_crash(self):
        """S3.1-AC3 — an unknown scheme is skipped with a reason."""
        inspector, reason = db_module.connect("redis://127.0.0.1:6379/0")
        assert inspector is None and "redis" in reason

    def test_a_clock_nobody_serves_is_reported_unavailable(self):
        """S3.1-AC4 — the clock fixture skips with this message, rather than failing."""
        assert not clock_module.Clock("http://127.0.0.1:9", timeout=2).available()
        assert clock_module.NOT_ENABLED == "test clock not enabled"
