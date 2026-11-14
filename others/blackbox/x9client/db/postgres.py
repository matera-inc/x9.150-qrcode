# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Read-only inspector for the Go build's PostgreSQL schema (``db/changelog``).

Connect as the read-only role (ADR-0032). The schema is whatever the role's ``search_path``
names, unless ``X9_DATABASE_SCHEMA`` says otherwise.
"""
import os
import uuid

import psycopg


class PostgresInspector:
    build = "go"

    def __init__(self, url):
        self.conn = psycopg.connect(url, autocommit=True)
        schema = os.environ.get("X9_DATABASE_SCHEMA")
        if schema:
            with self.conn.cursor() as cur:
                cur.execute("SELECT set_config('search_path', %s, false)", (schema,))

    def _one(self, sql, args=()):
        with self.conn.cursor() as cur:
            cur.execute(sql, args)
            return cur.fetchone()

    def qr_exists(self, qr_id):
        return self._one("SELECT count(*) FROM qr_code WHERE id = %s", (uuid.UUID(hex=qr_id),))[0] == 1

    def qr_count(self):
        return self._one("SELECT count(*) FROM qr_code")[0]

    def events_for(self, qr_id):
        with self.conn.cursor() as cur:
            cur.execute("SELECT t.name FROM payment_event e "
                        "JOIN event_type t ON t.code = e.type_code "
                        "WHERE e.qr_code_id = %s ORDER BY e.seq", (uuid.UUID(hex=qr_id),))
            return [row[0] for row in cur.fetchall()]
