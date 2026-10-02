/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.events;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exactly one instance drains, enforced rather than assumed.
 *
 * <p>Two drainers break the cursor contract silently — events are skipped, or relocated after a
 * consumer has passed them (ADR-0014). Neither surfaces as an error, which is why this is a lease in
 * the database rather than a note telling operators to keep {@code replicaCount} at 1.
 *
 * <p>Run against a real MongoDB, because the guarantee being tested is the unique {@code _id}
 * rejecting a concurrent upsert. A mock would assert that we wrote the code we wrote.
 */
class PaymentEventDrainLockTest extends AbstractIntegrationTest {

    @Autowired
    private MongoTemplate mongoTemplate;

    private PaymentEventDrainLock instanceA;
    private PaymentEventDrainLock instanceB;

    @BeforeEach
    void freshLease() {
        mongoTemplate.remove(new Query(), PaymentEventDrainLock.COLLECTION);

        // Separate objects stand in for separate pods: each has its own owner identity.
        instanceA = new PaymentEventDrainLock(mongoTemplate);
        instanceB = new PaymentEventDrainLock(mongoTemplate);
    }

    /**
     * <b>X9-EVT-010</b> — the first instance to ask gets the drain lease.
     *
     * <p><b>Source:</b> Ours. ADR-0002 — the embedded outbox is drained by one instance at a time.
     *
     * <p><b>Why:</b> Several replicas draining concurrently would publish the same event more than once, and the
     * consumer's deduplication would be carrying load that need not exist.
     */
    @Test
    void theFirstInstanceToAskGetsTheLease() {
        assertTrue(instanceA.acquire(), "an uncontended lease must be grantable");
    }

    /**
     * <b>X9-EVT-011</b> — a second instance is refused while the lease is held.
     *
     * <p><b>Source:</b> Ours. ADR-0002.
     *
     * <p><b>Why:</b> The exclusion that makes EVT-010 worth anything.
     */
    @Test
    void asecondInstanceIsRefusedWhileTheLeaseIsHeld() {
        assertTrue(instanceA.acquire());

        assertFalse(instanceB.acquire(),
                "two drainers skip and relocate events; only one may hold the lease");
    }

    /**
     * <b>X9-EVT-012</b> — the holder may reacquire its own lease.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> Re-entrancy: an instance must not lock itself out between drain cycles, which would stall the
     * stream until the lease expired.
     */
    @Test
    void theHolderMayReacquireItsOwnLease() {
        assertTrue(instanceA.acquire());

        assertTrue(instanceA.acquire(), "the holder must be able to renew");
    }

    /**
     * <b>X9-EVT-013</b> — releasing hands the lease over.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> A clean shutdown should not leave the stream stalled for the length of the lease.
     */
    @Test
    void releasingHandsOver() {
        assertTrue(instanceA.acquire());
        instanceA.release();

        assertTrue(instanceB.acquire(), "a released lease must be immediately available");
    }

    /**
     * <b>X9-EVT-014</b> — an expired lease is taken over without release.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> The crash case. An instance that dies holding the lease must not stop the stream forever —
     * which is why the lease expires rather than being held until released.
     */
    @Test
    void anExpiredLeaseIsTakenOverWithoutRelease() {
        assertTrue(instanceA.acquire());

        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").is(PaymentEventDrainLock.LOCK_ID)),
            new Update().set("expiresAt", Instant.now().minusSeconds(1)),
            Document.class,
            PaymentEventDrainLock.COLLECTION);

        assertTrue(instanceB.acquire(),
                "a dead instance must not hold the stream hostage; the lease has to lapse");
    }

}
