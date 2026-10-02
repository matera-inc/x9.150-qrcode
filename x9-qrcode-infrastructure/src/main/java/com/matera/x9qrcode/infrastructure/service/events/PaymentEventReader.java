/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.events;

import com.matera.x9qrcode.domain.utils.UUIDUtils;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentEventDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentEventPageDTO;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.model.PaymentEventMongoPersistenceModel;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.repository.PaymentEventMongoModelRepository;

import lombok.RequiredArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.data.domain.Limit;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static java.util.Objects.isNull;

/**
 * Reads the event log for a consumer, with optional long-polling.
 *
 * <p>Holding a request open costs no platform thread — {@code spring.threads.virtual.enabled} is on
 * — so an idle consumer parks instead of hammering the endpoint. The hold is bounded so it stays
 * under proxy and ingress idle timeouts.
 */
@RequiredArgsConstructor
public class PaymentEventReader {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private final PaymentEventMongoModelRepository eventRepository;

    public PaymentEventPageDTO read(String after, Integer limit, Integer waitSeconds) {
        int pageSize = isNull(limit) ? 100 : limit;
        Duration wait = Duration.ofSeconds(isNull(waitSeconds) ? 0 : waitSeconds);

        List<PaymentEventMongoPersistenceModel> events = fetch(after, pageSize);

        if (events.isEmpty() && !wait.isZero()) {
            events = waitForEvents(after, pageSize, wait);
        }

        return toPage(events, pageSize);
    }

    private List<PaymentEventMongoPersistenceModel> waitForEvents(String after, int pageSize, Duration wait) {
        long deadline = System.nanoTime() + wait.toNanos();

        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            List<PaymentEventMongoPersistenceModel> events = fetch(after, pageSize);

            if (!events.isEmpty()) {
                return events;
            }
        }

        // Returning empty after the wait is a normal answer, not an error: the consumer simply polls
        // again with the same cursor.
        return List.of();
    }

    private List<PaymentEventMongoPersistenceModel> fetch(String after, int pageSize) {
        // One extra row tells us whether more is available without a second query.
        Limit limit = Limit.of(pageSize + 1);

        return isNull(after) || after.isBlank()
            ? eventRepository.findByOrderBySeqAsc(limit)
            : eventRepository.findBySeqGreaterThanOrderBySeqAsc(after, limit);
    }

    private PaymentEventPageDTO toPage(List<PaymentEventMongoPersistenceModel> fetched, int pageSize) {
        boolean hasMore = fetched.size() > pageSize;
        List<PaymentEventMongoPersistenceModel> page = hasMore ? fetched.subList(0, pageSize) : fetched;

        PaymentEventPageDTO dto = new PaymentEventPageDTO();
        dto.setEvents(page.stream().map(PaymentEventReader::toDto).toList());
        dto.setHasMore(hasMore);

        if (!page.isEmpty()) {
            dto.setNextCursor(page.get(page.size() - 1).getSeq());
        }

        return dto;
    }

    private static PaymentEventDTO toDto(PaymentEventMongoPersistenceModel model) {
        PaymentEventDTO dto = new PaymentEventDTO();

        dto.setEventId(model.getId());
        dto.setType(PaymentEventDTO.TypeEnum.fromValue(model.getType()));
        dto.setOccurredAt(OffsetDateTime.ofInstant(model.getOccurredAt(), ZoneOffset.UTC));
        // The shortened form, as everywhere else in this API — the create response's `id`, the path
        // parameter, and the notification's qrcodeId. An event carrying a dashed UUID would force a
        // consumer to normalise before it could correlate anything.
        dto.setQrCodeId(UUIDUtils.toShortenString(model.getQrCodeId()));
        dto.setQrCodeRevision(model.getQrCodeRevision());
        dto.setLocationId(model.getLocationId());
        dto.setAmount(model.getAmount());
        // JsonNullable so an event with no tip omits the field rather than reporting a tip of zero,
        // which a consumer would have to tell apart from "none reported".
        dto.setTipAmount(JsonNullable.of(model.getTipAmount()));
        dto.setCurrency(model.getCurrency());
        dto.setNetwork(model.getNetwork());
        dto.setTransactionId(JsonNullable.of(model.getTransactionId()));
        dto.setInvoiceNumber(JsonNullable.of(model.getInvoiceNumber()));
        dto.setOrderNumber(JsonNullable.of(model.getOrderNumber()));
        dto.setReason(JsonNullable.of(model.getReason()));
        dto.setSchemaVersion(model.getSchemaVersion());

        return dto;
    }

}
