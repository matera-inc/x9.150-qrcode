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

    /**
     * <b>X9-LIFE-080</b> — a transient write conflict is retried until it succeeds.
     *
     * <p><b>Source:</b> Ours. ADR-0013 — retry what MongoDB says to retry.
     *
     * <p><b>Why:</b> Two payers reaching one QR Code concurrently produce a WriteConflict that MongoDB itself
     * labels TransientTransactionError, meaning "try again". Surfacing it as a 500 would turn a
     * normal race into a failed payment.
     */
    @Test
    void aTransientConflictIsRetriedUntilItSucceeds() throws Throwable {
        AtomicInteger attempts = new AtomicInteger();

        Object result = retry.retryOnTransientConflict(
            joinPointThat(attempts, new DataIntegrityViolationException("write conflict", writeConflict()), 2));

        assertEquals("committed", result);
        assertEquals(3, attempts.get(), "two conflicts, then the commit");
    }

    /**
     * <b>X9-LIFE-081</b> — the transient label is found through the cause chain.
     *
     * <p><b>Source:</b> Ours. ADR-0013.
     *
     * <p><b>Why:</b> Spring wraps the driver's exception. Checking only the top-level type misses the label
     * entirely, so the retry silently never fires — and the symptom is an occasional 500 under load
     * that nobody can reproduce.
     */
    @Test
    void theLabelIsFoundThroughTheCauseChain() {
        assertTrue(TransientTransactionRetry.isTransient(
            new RuntimeException("wrapped", new DataIntegrityViolationException("x", writeConflict()))));
    }

    // -------------------------------------------------------------------------- NOT retried

    /**
     * <b>X9-LIFE-082</b> — a duplicate key is not retried.
     *
     * <p><b>Source:</b> Ours. ADR-0013 — only what MongoDB labels transient.
     *
     * <p><b>Why:</b> A duplicate key will fail identically every time. Retrying it burns the budget and delays the
     * real error reaching the caller.
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

    /**
     * <b>X9-LIFE-083</b> — an ordinary business failure is not retried.
     *
     * <p><b>Source:</b> Ours. ADR-0013.
     *
     * <p><b>Why:</b> A refused payment is a decision, not a glitch. Retrying it would re-run business rules that
     * already said no.
     */
    @Test
    void anOrdinaryBusinessFailureIsNotRetried() throws Throwable {
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("nothing to do with Mongo");

        assertThrows(IllegalStateException.class,
            () -> retry.retryOnTransientConflict(joinPointThat(attempts, failure, 99)));

        assertEquals(1, attempts.get());
    }

    /**
     * <b>X9-LIFE-084</b> — a duplicate key is not mistaken for transient.
     *
     * <p><b>Source:</b> Ours. ADR-0013.
     *
     * <p><b>Why:</b> Both arrive as Mongo write errors. Classifying by the LABEL rather than by the type is what
     * keeps them apart; matching loosely would retry the permanent one.
     */
    @Test
    void aDuplicateKeyIsNotMistakenForTransient() {
        assertFalse(TransientTransactionRetry.isTransient(
            new DataIntegrityViolationException("duplicate", duplicateKey())));
    }

    // ------------------------------------------------------------------------- exhaustion

    /**
     * <b>X9-LIFE-085</b> — attempts are bounded and the original failure survives.
     *
     * <p><b>Source:</b> Ours. ADR-0013.
     *
     * <p><b>Why:</b> An unbounded retry is an outage that looks like a slow request. And the exception the caller
     * finally sees must be the REAL one — a retry wrapper that reports "gave up" discards the
     * diagnosis.
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
     * <b>X9-LIFE-086</b> — the retry wraps the transaction rather than running inside it.
     *
     * <p><b>Source:</b> Ours. ADR-0013.
     *
     * <p><b>Why:</b> Order matters and getting it wrong is silent: retrying INSIDE an already-aborted transaction
     * re-runs the work against a session MongoDB has given up on, so every attempt fails for a
     * second, unrelated reason.
     */
    @Test
    void theRetryWrapsTheTransactionRatherThanRunningInsideIt() {
        assertTrue(retry.getOrder() < Ordered.LOWEST_PRECEDENCE,
            "must order outside Spring's @Transactional advice");
    }

}
