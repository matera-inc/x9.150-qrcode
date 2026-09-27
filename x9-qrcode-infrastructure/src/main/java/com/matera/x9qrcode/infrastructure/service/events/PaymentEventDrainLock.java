/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.bson.Document;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Makes exactly one instance drain the outbox at a time.
 *
 * <p>Without this, {@code replicaCount: 1} is load-bearing correctness rather than a capacity
 * choice — and nothing says so, which is the dangerous part. Two drainers break the cursor contract
 * in two ways, both silent:
 *
 * <ul>
 *   <li><b>Events are skipped.</b> Cursors are stamped before the write, so two instances can stamp
 *       {@code t=1ms} and {@code t=2ms} and have the second become visible first. A consumer that
 *       reads to the second and saves its cursor will never see the first: the next query is
 *       {@code seq > that}, and the earlier row is behind it forever.</li>
 *   <li><b>Events move.</b> {@code append} upserts on the event id so a re-drain after a crash does
 *       not duplicate. Two instances draining the same event would upsert it twice with different
 *       {@code seq} values — relocating a row a consumer may already have passed.</li>
 * </ul>
 *
 * <p>Neither surfaces as an error. A consumer simply never receives something, which is why this is
 * enforced rather than documented as an operational rule somebody has to remember.
 *
 * <h2>A lease, not a lock</h2>
 *
 * <p>Held for a bounded time and allowed to expire. An instance that dies mid-drain cannot wedge the
 * stream: the lease lapses and the next tick elsewhere takes over. The events it had not yet drained
 * are still in their QR Code documents, because the outbox entry is removed only after the log entry
 * is written.
 *
 * <p>Correctness does not depend on clock agreement between instances, only on each instance's own
 * clock advancing roughly in real time. The lease is generous relative to a drain of
 * {@code BATCH_SIZE} events, so overrunning it takes a pathological stall rather than a slow day.
 */
@Slf4j
@RequiredArgsConstructor
public class PaymentEventDrainLock {

    static final String COLLECTION = "payment_event_drain_lock";
    static final String LOCK_ID = "payment-event-drain";

    /** Generous against a bounded batch; short enough that a dead instance frees it promptly. */
    static final Duration LEASE = Duration.ofSeconds(60);

    /** Identifies this instance, so a holder can renew its own lease rather than contend with it. */
    private final String owner = UUID.randomUUID().toString();

    private final MongoTemplate mongoTemplate;

    /**
     * @return true if this instance now holds the lease and may drain
     */
    public boolean acquire() {
        Instant now = Instant.now();

        Query query = Query.query(new Criteria().andOperator(
            Criteria.where("_id").is(LOCK_ID),
            new Criteria().orOperator(
                Criteria.where("expiresAt").lt(now),
                Criteria.where("owner").is(owner))));

        Update update = new Update()
            .set("owner", owner)
            .set("expiresAt", now.plus(LEASE));

        try {
            // Upsert is what makes this work on an empty collection AND safe on a contended one:
            // when the lease is held elsewhere the query matches nothing, so Mongo attempts an
            // insert, and the unique _id rejects it. Losing the race is a DuplicateKeyException,
            // not a silent second winner.
            mongoTemplate.upsert(query, update, Document.class, COLLECTION);

            return true;
        } catch (DuplicateKeyException contended) {
            return false;
        }
    }

    /**
     * Ends the lease early so another instance need not wait it out. Best-effort: if this fails, or
     * the instance dies first, the lease simply expires.
     */
    public void release() {
        try {
            mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(LOCK_ID).and("owner").is(owner)),
                new Update().set("expiresAt", Instant.now()),
                Document.class,
                COLLECTION);
        } catch (Exception e) {
            log.debug("Could not release the drain lease; it will expire on its own", e);
        }
    }

}
