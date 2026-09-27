/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.persistence.mongodb;

import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import com.mongodb.MongoException;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;

/**
 * Retrying a conflict MongoDB said to retry — and, just as importantly, not retrying one it did not.
 *
 * <p>The distinction is the whole design. Spring translates a {@code WriteConflict} and a duplicate
 * key to the <em>same</em> {@link DataIntegrityViolationException}, and this schema has a unique
 * index on {@code locationId}, so both are live possibilities on the same code path. Retrying by
 * exception type would keep re-running a permanently doomed write and turn a clean 409 into a slow
 * 500. The decision is therefore made on MongoDB's own {@code TransientTransactionError} label.
 *
 * <p>These are deterministic by construction: reproducing a genuine write conflict would need two
 * racing transactions and would reintroduce exactly the flakiness that prompted this work.
 */
class TransientTransactionRetryTest {

    private final TransientTransactionRetry retry = new TransientTransactionRetry();

    /** A conflict shaped as the server sends it: error 112, carrying the transient label. */
    private static MongoException writeConflict() {
        MongoCommandException conflict = new MongoCommandException(
            BsonDocument.parse("{ \"ok\": 0, \"errmsg\": \"Write conflict during plan execution\", "
                + "\"code\": 112, \"codeName\": \"WriteConflict\" }"),
            new ServerAddress());

        conflict.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);

        return conflict;
    }

    /** A duplicate key: error 11000, and pointedly NOT labelled transient. */
    private static MongoException duplicateKey() {
        return new MongoCommandException(
            BsonDocument.parse("{ \"ok\": 0, \"errmsg\": \"E11000 duplicate key error\", "
                + "\"code\": 11000, \"codeName\": \"DuplicateKey\" }"),
            new ServerAddress());
    }

    private static ProceedingJoinPoint joinPointThat(AtomicInteger attempts, Throwable failUntilSucceeded, int failures)
        throws Throwable {

        Signature signature = mock(Signature.class);
        when(signature.toShortString()).thenReturn("PaymentRequestsController.createPaymentRequest(..)");

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() <= failures) {
                throw failUntilSucceeded;
            }

            return "committed";
        });

        return joinPoint;
    }

    // ------------------------------------------------------------------------------ retried

    @Test
    void aTransientConflictIsRetriedUntilItSucceeds() throws Throwable {
        AtomicInteger attempts = new AtomicInteger();

        Object result = retry.retryOnTransientConflict(
            joinPointThat(attempts, new DataIntegrityViolationException("write conflict", writeConflict()), 2));

        assertEquals("committed", result);
        assertEquals(3, attempts.get(), "two conflicts, then the commit");
    }

    /** The label travels on the driver exception, under Spring's translation of it. */
    @Test
    void theLabelIsFoundThroughTheCauseChain() {
        assertTrue(TransientTransactionRetry.isTransient(
            new RuntimeException("wrapped", new DataIntegrityViolationException("x", writeConflict()))));
    }

    // -------------------------------------------------------------------------- NOT retried

    /**
     * The case that makes type-based retrying wrong: identical Spring exception, permanent failure.
     * One attempt, and the caller hears about it immediately.
     */
    @Test
    void aDuplicateKeyIsNotRetried() throws Throwable {
        AtomicInteger attempts = new AtomicInteger();
        DataIntegrityViolationException duplicate =
            new DataIntegrityViolationException("duplicate locationId", duplicateKey());

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
            () -> retry.retryOnTransientConflict(joinPointThat(attempts, duplicate, 99)));

        assertSame(duplicate, thrown, "a permanent failure must surface unchanged");
        assertEquals(1, attempts.get(), "a duplicate key is not going to resolve itself");
    }

    @Test
    void anOrdinaryBusinessFailureIsNotRetried() throws Throwable {
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("nothing to do with Mongo");

        assertThrows(IllegalStateException.class,
            () -> retry.retryOnTransientConflict(joinPointThat(attempts, failure, 99)));

        assertEquals(1, attempts.get());
    }

    @Test
    void aDuplicateKeyIsNotMistakenForTransient() {
        assertFalse(TransientTransactionRetry.isTransient(
            new DataIntegrityViolationException("duplicate", duplicateKey())));
    }

    // ------------------------------------------------------------------------- exhaustion

    /**
     * Attempts are bounded, and exhaustion surfaces MongoDB's own diagnosis rather than a wrapper
     * explaining that we gave up — the server's message is the one worth reading.
     */
    @Test
    void attemptsAreBoundedAndTheOriginalFailureSurvives() throws Throwable {
        AtomicInteger attempts = new AtomicInteger();
        DataIntegrityViolationException conflict =
            new DataIntegrityViolationException("write conflict", writeConflict());

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
            () -> retry.retryOnTransientConflict(joinPointThat(attempts, conflict, 99)));

        assertSame(conflict, thrown);
        assertEquals(TransientTransactionRetry.MAX_ATTEMPTS, attempts.get());
    }

    // ------------------------------------------------------------------------------ order

    /**
     * The ordering IS the correctness argument: one step outside Spring's transaction advice, so
     * every attempt gets a fresh transaction. Retrying inside an aborted one can only fail again.
     */
    @Test
    void theRetryWrapsTheTransactionRatherThanRunningInsideIt() {
        assertTrue(retry.getOrder() < Ordered.LOWEST_PRECEDENCE,
            "must order outside Spring's @Transactional advice");
    }

}
