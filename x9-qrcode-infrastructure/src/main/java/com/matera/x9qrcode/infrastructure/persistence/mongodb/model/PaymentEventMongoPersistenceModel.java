/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.persistence.mongodb.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.UUID;

/**
 * An event that has left the outbox and is readable by consumers.
 *
 * <p>The id is the event's own {@code eventId}, so a re-drain of the same event is an upsert rather
 * than a duplicate — which is what makes an at-least-once drain safe to replay after a crash.
 *
 * <p>{@code seq} is a ULID assigned at drain time: monotonic, lexicographically sortable, and the
 * cursor consumers page by. It exists because {@code eventId} is a random UUID and cannot order
 * anything.
 */
@Data
@Document(collection = "payment_events")
public class PaymentEventMongoPersistenceModel {

    @Id
    private UUID id;

    @Indexed(unique = true)
    @Field(name = "seq")
    private String seq;

    @Field(name = "type")
    private String type;

    /**
     * When the event happened — and, via the TTL index, when it stops being available.
     *
     * <p>The log is the consumers' record, not ours, so it cannot be deleted on acknowledgement:
     * a cursor consumer may legitimately rewind and reprocess, and a second consumer may be added
     * later. But it cannot grow without bound either, so entries expire on a retention window that
     * is generous relative to any plausible outage.
     *
     * <p>A cursor older than the window resolves to nothing rather than to an error, which is the
     * right failure: the consumer learns it has fallen too far behind instead of silently skipping.
     *
     * <p>Thirty days is a literal rather than a property because {@code @Indexed} does not resolve
     * placeholders here. That is no great loss: retention is an operational decision, and MongoDB
     * changes it in place without a deployment —
     * {@code db.runCommand({collMod: "payment_events", index: {name: "payment_events_ttl",
     * expireAfterSeconds: <n>}})}.
     */
    @Indexed(name = "payment_events_ttl", expireAfter = "P30D")
    @Field(name = "occurred_at")
    private Instant occurredAt;

    @Field(name = "qrcode_id")
    private UUID qrCodeId;

    @Field(name = "qrcode_revision")
    private Integer qrCodeRevision;

    @Field(name = "location_id")
    private String locationId;

    @Field(name = "amount")
    private Long amount;

    @Field(name = "currency")
    private String currency;

    @Field(name = "network")
    private String network;

    @Field(name = "transaction_id")
    private String transactionId;

    @Field(name = "invoice_number")
    private String invoiceNumber;

    @Field(name = "order_number")
    private String orderNumber;

    @Field(name = "reason")
    private String reason;

    @Field(name = "schema_version")
    private String schemaVersion;

}
