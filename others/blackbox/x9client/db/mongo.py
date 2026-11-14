# Copyright © 2026 Matera Systems, Inc.
# Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
"""Read-only inspector for the Java build's MongoDB (``qrcodes``, ``payment_events``).

Java stores ids as legacy UUIDs (BSON subtype 3, Java byte order), so the client is opened with
``uuidRepresentation=javaLegacy`` and the API's 32-hex ids are compared as UUIDs.
"""
import uuid

import pymongo


class MongoInspector:
    build = "java"

    def __init__(self, url):
        self.client = pymongo.MongoClient(url, uuidRepresentation="javaLegacy",
                                          serverSelectionTimeoutMS=5000)
        self.db = self.client.get_default_database(default="x9-qrcode")

    @staticmethod
    def _uuid(qr_id):
        return uuid.UUID(hex=qr_id)

    def qr_exists(self, qr_id):
        return self.db.qrcodes.count_documents({"_id": self._uuid(qr_id)}, limit=1) == 1

    def qr_count(self):
        return self.db.qrcodes.estimated_document_count()

    def events_for(self, qr_id):
        """Published events only. Java holds undrained ones in the document's ``outbox``."""
        cursor = self.db.payment_events.find({"qrcode_id": self._uuid(qr_id)},
                                             {"type": 1, "seq": 1}).sort("seq", 1)
        return [doc["type"] for doc in cursor]

    def outbox_for(self, qr_id):
        doc = self.db.qrcodes.find_one({"_id": self._uuid(qr_id)}, {"outbox": 1}) or {}
        return list(doc.get("outbox") or [])
