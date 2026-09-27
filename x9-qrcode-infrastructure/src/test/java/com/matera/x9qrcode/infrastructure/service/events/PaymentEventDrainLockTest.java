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

    @Test
    void theFirstInstanceToAskGetsTheLease() {
        assertTrue(instanceA.acquire(), "an uncontended lease must be grantable");
    }

    /** The whole point: a second instance ticking at the same moment must not also drain. */
    @Test
    void asecondInstanceIsRefusedWhileTheLeaseIsHeld() {
        assertTrue(instanceA.acquire());

        assertFalse(instanceB.acquire(),
                "two drainers skip and relocate events; only one may hold the lease");
    }

    /** Re-entrant for its holder, so a long-running instance renews rather than locking itself out. */
    @Test
    void theHolderMayReacquireItsOwnLease() {
        assertTrue(instanceA.acquire());

        assertTrue(instanceA.acquire(), "the holder must be able to renew");
    }

    @Test
    void releasingHandsOver() {
        assertTrue(instanceA.acquire());
        instanceA.release();

        assertTrue(instanceB.acquire(), "a released lease must be immediately available");
    }

    /**
     * An instance that dies mid-drain must not wedge the stream. Nothing releases its lease, so the
     * expiry is the only thing that frees it — simulated here by ageing the record, since waiting a
     * minute would be a test of patience rather than of behaviour.
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
