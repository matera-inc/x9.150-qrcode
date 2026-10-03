/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.events;

import com.matera.x9qrcode.infrastructure.persistence.mongodb.model.PaymentEventMongoPersistenceModel;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.model.QRCodeMongoPersistenceModel;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.repository.PaymentEventMongoModelRepository;

import com.github.f4b6a3.ulid.UlidCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

import static java.util.Objects.isNull;

/**
 * Moves events out of QR Code documents and into the readable event log.
 *
 * <p>Its only sink is a local collection — no broker, nothing that can be down — which is why it can
 * be this simple. A crash between the append and the removal re-appends the same {@code eventId} on
 * the next tick, and the log keys on that id, so the retry is a no-op rather than a duplicate. There
 * is no partner system to be inconsistent with.
 *
 * <p>X9.150 does not push events anywhere. Consumers pull them from the events API and bridge to
 * whatever they run.
 */
@Slf4j
@RequiredArgsConstructor
public class PaymentEventDrain {

    private static final int BATCH_SIZE = 100;

    private final MongoTemplate mongoTemplate;
    private final PaymentEventMongoModelRepository eventRepository;
    private final PaymentEventDrainLock drainLock;

    /**
     * The scheduled tick, which drains only if it holds the lease.
     *
     * <p>The lease is taken here rather than inside {@link #drainOnce()} so that the mechanism stays
     * directly callable — tests drive it deterministically instead of waiting for a tick, and they
     * are not testing leader election when they do.
     */
    @Scheduled(fixedDelayString = "${x9.events.drain.interval:PT1S}")
    public void drain() {
        if (!drainLock.acquire()) {
            // Another instance is draining. Not an error, and not worth logging at info: on a
            // multi-replica deployment this is the normal outcome for every instance but one.
            return;
        }

        try {
            drainOnce();
        } catch (Exception e) {
            // Never let a tick's failure kill the scheduler: the next tick retries, and the events
            // are still in their documents until they are safely in the log.
            log.error("Payment event drain failed; will retry on the next tick", e);
        } finally {
            drainLock.release();
        }
    }

    /**
     * Drains one batch and reports how many events moved. Public so a caller can force a drain
     * rather than wait for the scheduler — which is what makes the flow deterministic in a test.
     *
     * @return the number of events moved to the log
     */
    public int drainOnce() {
        List<QRCodeMongoPersistenceModel> pending = mongoTemplate.find(
            Query.query(Criteria.where("outbox.0").exists(true)).limit(BATCH_SIZE),
            QRCodeMongoPersistenceModel.class);

        int drained = 0;

        for (QRCodeMongoPersistenceModel qrCode : pending) {
            if (isNull(qrCode.getOutbox())) {
                continue;
            }

            for (QRCodeMongoPersistenceModel.OutboxEvent event : List.copyOf(qrCode.getOutbox())) {
                append(qrCode, event);
                remove(qrCode, event);
                drained++;
            }
        }

        if (drained > 0) {
            log.debug("Drained {} payment event(s) to the event log", drained);
        }

        return drained;
    }

    private void append(QRCodeMongoPersistenceModel qrCode, QRCodeMongoPersistenceModel.OutboxEvent event) {
        PaymentEventMongoPersistenceModel model = new PaymentEventMongoPersistenceModel();

        // The id is the event's own id, so re-draining after a crash upserts instead of duplicating.
        model.setId(event.getEventId());
        model.setSeq(UlidCreator.getMonotonicUlid().toString());
        model.setType(event.getType());
        model.setOccurredAt(event.getOccurredAt());
        model.setQrCodeId(qrCode.getId());
        model.setQrCodeRevision(event.getQrCodeRevision());
        model.setLocationId(event.getLocationId());
        model.setAmount(event.getAmount());
        model.setTipAmount(event.getTipAmount());
        model.setPayerInfo(event.getPayerInfo());
        model.setCurrency(event.getCurrency());
        model.setNetwork(event.getNetwork());
        model.setTransactionId(event.getTransactionId());
        model.setInvoiceNumber(event.getInvoiceNumber());
        model.setOrderNumber(event.getOrderNumber());
        model.setReason(event.getReason());
        model.setSchemaVersion(com.matera.x9qrcode.domain.event.PaymentEvent.SCHEMA_VERSION);

        if (eventRepository.existsById(event.getEventId())) {
            // Already drained; the outbox entry is a crash-retry. Keep the original seq so a consumer
            // that has already read past it is not sent backwards.
            return;
        }

        eventRepository.save(model);
    }

    private void remove(QRCodeMongoPersistenceModel qrCode, QRCodeMongoPersistenceModel.OutboxEvent event) {
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").is(qrCode.getId())),
            new Update().pull("outbox", Query.query(Criteria.where("event_id").is(event.getEventId())).getQueryObject()),
            QRCodeMongoPersistenceModel.class);
    }

}
