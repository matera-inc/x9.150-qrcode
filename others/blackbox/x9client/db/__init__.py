# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Optional, read-only database inspectors, for what HTTP cannot show (ADR-0034).

Assert through the API first. A database check only covers what the API cannot: "nothing was
written", "exactly one event", the audit log. It runs only when ``X9_DATABASE_URL`` is set, and
only with its driver installed (extra ``db-postgres``):

    X9_DATABASE_URL=postgresql://x9_readonly:...@127.0.0.1:5432/x9

(A MongoDB inspector served the Java build until its retirement at 3.0.0, ADR-0039.)

Use the deployment's read-only role (ADR-0032). The inspectors never write.

The inspector offers these questions:

- ``qr_exists(qr_id)``: whether the store holds that QR Code;
- ``qr_count()``: how many QR Codes the store holds;
- ``events_for(qr_id)``: the event types published for it, in order.
"""
import os

ENV = "X9_DATABASE_URL"


def connect(url=None):
    """``(inspector, None)`` or ``(None, reason)``: a reason a test can skip with, never a crash."""
    url = url if url is not None else os.environ.get(ENV, "")
    if not url:
        return None, f"{ENV} is not set; database checks need a read-only connection"

    scheme = url.split(":", 1)[0].lower()
    if scheme in ("postgres", "postgresql"):
        try:
            from x9client.db.postgres import PostgresInspector
        except ImportError as e:
            return None, f"the PostgreSQL driver is not installed (extra db-postgres: psycopg): {e}"
        return PostgresInspector(url), None

    return None, f"{ENV} has a scheme this suite does not inspect: {scheme}"
