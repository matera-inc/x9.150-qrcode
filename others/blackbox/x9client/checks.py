# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""The house rule, as code: a refusal is refused for the RIGHT reason."""
import json


def refusal(response):
    """The violations of a problem+json body (or the whole body), as one searchable string."""
    status, body = response
    if isinstance(body, dict):
        return json.dumps(body.get("violations") or body)
    return str(body)


def assert_refused(response, *, naming, status=None):
    """
    A refusal has to be refused FOR THE RIGHT REASON.

    Asserting only on the status code is how a check passes because the fixture was never
    built, or because an unrelated rule fired first. Every refusal here names the field it
    is about, so the test can insist on it. ``status`` pins the exact code when it matters.
    """
    got, body = response
    assert got >= 400, f"expected a refusal, got {got}: {body}"
    if status is not None:
        assert got == status, f"expected {status}, got {got}: {body}"
    text = refusal(response)
    assert naming in text, \
        f"refused, but not for the expected reason.\n  wanted: {naming}\n  got:    {text}"
