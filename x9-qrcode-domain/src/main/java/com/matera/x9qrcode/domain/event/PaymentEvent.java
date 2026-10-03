/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.event;

import com.matera.x9qrcode.domain.vo.enumerated.PaymentEventTypeEnum;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A fact about a QR Code's payment, published for anyone to consume.
 *
 * <p>This is a public contract, not an internal message. Within a major version it changes only by
 * addition, and consumers are expected to ignore unknown fields and unknown types.
 *
 * <p>{@code eventId} is stable across re-publishes, so delivery can be at-least-once and consumers
 * deduplicate on it. {@code qrCodeId} is the ordering key: events for one QR Code are ordered
 * relative to each other, and no ordering is promised across QR Codes.
 *
 * <p>{@code qrCodeRevision} is monotonic per QR Code but NOT gap-free — a notification that only
 * records details bumps the revision without emitting an event — so a consumer uses it as "ignore
 * anything not greater than what I have", never as "expect the next one".
 */
public record PaymentEvent(
    UUID eventId,
    PaymentEventTypeEnum type,
    OffsetDateTime occurredAt,
    UUID qrCodeId,
    Integer qrCodeRevision,
    String locationId,
    Long amount,

    /**
     * How much of {@code amount} the payer reported as a tip, or null when none was reported.
     *
     * <p>Reported, not computed. X9.150 neither receives money nor pays anyone — it raises payment
     * requests and validates notifications — so this is a fact we transport, exactly as it arrived,
     * for the consuming system to reconcile against what its accounts actually received.
     *
     * <p>Carried because {@code amount} is the TOTAL, tip included (ANSI X9.150-2026 §2.1 with
     * §13.6.2). Without this field a consumer reading the stream sees one number and cannot tell
     * what part of it settled the bill.
     */
    Long tipAmount,

    /**
     * {@code payer.info} as the payer sent it, or null when none was sent.
     *
     * <p>Transported, never interpreted. ANSI X9.150-2026 §3.1 leaves the contents to the payer's
     * PSP, so this may be an opaque identifier, an email address or anything else — see
     * {@code official-spec/BEST-PRACTICES.md} for what a PSP should put there and why.
     *
     * <p>Carried because the stream is the public contract (ADR-0001) and without it a consumer
     * reading an event sees a payment and cannot tell <em>who paid</em>. That matters now that
     * only the party which announced may re-announce or release: a payee's own systems need to
     * know which party that was, and reading it out of the QR Code document instead means the
     * stream is not self-sufficient.
     *
     * <p>Null when nothing was sent, never an empty string: "did not say" and "said nothing" mean
     * different things and a consumer cannot tell them apart otherwise.
     */
    String payerInfo,

    String currency,
    String network,
    String transactionId,
    String invoiceNumber,
    String orderNumber,
    String reason
) {

    public static final String SCHEMA_VERSION = "1.0";

    public String schemaVersion() {
        return SCHEMA_VERSION;
    }

}
