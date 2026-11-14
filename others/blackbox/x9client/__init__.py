# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""x9client: the only code in the black-box suite that knows the X9.150 protocol (ADR-0034).

- ``api``: HTTP on the standard library; ETag / If-Match; problem+json.
- ``emv``: TLV decoding and the CRC16 check.
- ``clock``: the movable test clock (ADR-0033): ``move_to``, ``now``, ``reset``.
- ``events``: a cursor reader of the event stream, deduplicating by ``eventId``.
- ``db``: optional read-only inspectors (MongoDB for Java, PostgreSQL for Go).
- ``samples`` and ``checks``: request bodies to start from, and the refused-for-the-right-reason
  assertion.

Tests read like scenarios; the plumbing lives here.
"""
