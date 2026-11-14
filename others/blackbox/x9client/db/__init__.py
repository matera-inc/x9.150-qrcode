# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Optional, read-only database inspectors, for what HTTP cannot show (ADR-0034).

Assert through the API first. A database check only covers what the API cannot: "nothing was
written", "exactly one event", the audit log. It runs only when ``X9_DATABASE_URL`` is set, and
only with its driver installed (extra ``db-mongo`` for the Java build, ``db-postgres`` for Go):

    X9_DATABASE_URL=mongodb://127.0.0.1:27017/x9-qrcode?directConnection=true
    X9_DATABASE_URL=postgresql://x9_readonly:...@127.0.0.1:5432/x9

Use the deployment's read-only role (ADR-0032). The inspectors never write.

Every inspector offers the same questions, so a test does not care which build it runs against:

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
    if scheme in ("mongodb", "mongodb+srv"):
        try:
            from x9client.db.mongo import MongoInspector
        except ImportError as e:
            return None, f"the MongoDB driver is not installed (extra db-mongo: pymongo): {e}"
        return MongoInspector(url), None

    if scheme in ("postgres", "postgresql"):
        try:
            from x9client.db.postgres import PostgresInspector
        except ImportError as e:
            return None, f"the PostgreSQL driver is not installed (extra db-postgres: psycopg): {e}"
        return PostgresInspector(url), None

    return None, f"{ENV} has a scheme this suite does not inspect: {scheme}"
