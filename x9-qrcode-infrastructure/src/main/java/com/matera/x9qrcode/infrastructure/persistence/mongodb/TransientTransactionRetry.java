/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.persistence.mongodb;

import com.mongodb.MongoException;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Retries a transaction MongoDB told us to retry.
 *
 * <p>When two transactions touch the same document, MongoDB aborts one with error 112
 * {@code WriteConflict} and labels it {@code TransientTransactionError}. The label is not decoration:
 * it is the server saying <em>this would probably succeed if you ran it again</em>, and the drivers'
 * own {@code withTransaction} helper exists to do exactly that. Spring Data's transaction manager
 * does not, so without this the conflict reached the caller as a 500 — a write that was never
 * attempted a second time, for a reason that was never the caller's fault.
 *
 * <p>This is not theoretical. CI hit it on a loaded runner, in the one flow that writes two
 * documents: creating a QR Code against an existing {@code locationId} releases the previous holder
 * and then saves the new one. Two concurrent creates on one location is an ordinary thing for a
 * merchant terminal to do.
 *
 * <h2>Why the label, and not the exception type</h2>
 *
 * Spring translates the conflict to {@link org.springframework.dao.DataIntegrityViolationException}
 * — which is also what a <b>duplicate key</b> produces, and this schema has a unique index on
 * {@code locationId}. Retrying by exception type would therefore retry a permanent failure until the
 * attempts ran out, turning a clean 409 into a slow 500. So the decision is made on MongoDB's own
 * {@code TransientTransactionError} label, found by walking the cause chain, which separates
 * precisely the two cases Spring makes look identical.
 *
 * <h2>Why outside the transaction</h2>
 *
 * The order places this <em>just outside</em> Spring's transaction advice, so each attempt gets a
 * fresh transaction. Retrying inside would be pointless: the server has already aborted that
 * transaction, and every subsequent operation in it fails too.
 */
@Aspect
@Slf4j
public class TransientTransactionRetry implements Ordered {

    /** A conflict resolves in milliseconds; a handful of attempts is generous, not optimistic. */
    public static final int MAX_ATTEMPTS = 4;

    private static final long BASE_BACKOFF_MILLIS = 20L;

    /**
     * Just ahead of Spring's transaction advice, which sits at {@link Ordered#LOWEST_PRECEDENCE}.
     * Lower value means further out, so this wraps the transaction rather than running inside it.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object retryOnTransientConflict(ProceedingJoinPoint joinPoint) throws Throwable {
        int attempt = 1;

        while (true) {
            try {
                return joinPoint.proceed();
            } catch (Throwable failure) {
                if (!isTransient(failure) || attempt == MAX_ATTEMPTS) {
                    // Rethrown as-is on exhaustion: the caller should see MongoDB's own diagnosis,
                    // not a wrapper explaining that we gave up.
                    throw failure;
                }

                log.warn("Transient transaction conflict on {} (attempt {} of {}); retrying: {}",
                    joinPoint.getSignature().toShortString(), attempt, MAX_ATTEMPTS, failure.getMessage());

                backOff(attempt);

                attempt++;
            }
        }
    }

    /**
     * Whether MongoDB labelled this {@code TransientTransactionError}, anywhere in the cause chain.
     *
     * <p>The label is the server's, so it travels on the driver exception rather than on Spring's
     * translation of it — which is why this unwraps instead of matching a type.
     */
    static boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongoException
                && mongoException.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true;
            }

            if (cause.getCause() == cause) {
                break;
            }
        }

        return false;
    }

    /**
     * A short randomised pause. Two transactions that conflict and then retry in lockstep conflict
     * again; the jitter is what stops a retry storm from being a slower version of the same problem.
     */
    private void backOff(int attempt) throws InterruptedException {
        long ceiling = BASE_BACKOFF_MILLIS * attempt;

        Thread.sleep(ThreadLocalRandom.current().nextLong(1, ceiling + 1));
    }

}
