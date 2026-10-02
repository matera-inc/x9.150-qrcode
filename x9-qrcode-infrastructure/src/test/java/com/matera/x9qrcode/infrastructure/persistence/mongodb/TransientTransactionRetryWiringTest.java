/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.persistence.mongodb;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;
import com.matera.x9qrcode.infrastructure.web.controller.PaymentRequestsController;

import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the retry is actually applied, and applied in the only order that works.
 *
 * <p>{@code TransientTransactionRetryTest} proves the advice behaves; this proves Spring puts it
 * where it was meant to go. The two are different claims, and the second is the one an
 * {@code @Order} value can quietly get wrong: a retry that runs <em>inside</em> the transaction
 * re-runs against a transaction the server has already aborted, so every attempt fails and the
 * whole thing looks like it works until the day it is needed.
 */
class TransientTransactionRetryWiringTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentRequestsController paymentRequestsController;

    /**
     * <b>X9-LIFE-087</b> — the retry advice wraps the transaction advice.
     *
     * <p><b>Source:</b> Ours. ADR-0013.
     *
     * <p><b>Why:</b> LIFE-086 proven on the actual Spring bean rather than in isolation. Advice order is
     * configuration, so a correct implementation can still be wired the wrong way round, and nothing
     * else would notice.
     */
    @Test
    void theRetryAdviceWrapsTheTransactionAdvice() {
        assertTrue(AopUtils.isAopProxy(paymentRequestsController),
            "the controller must be proxied for either advice to apply at all");

        List<Advisor> advisors = List.of(((Advised) paymentRequestsController).getAdvisors());

        int retryIndex = indexOf(advisors, advisor -> advisor.toString().contains("TransientTransactionRetry"));
        int transactionIndex = indexOf(advisors, advisor -> advisor.getAdvice() instanceof TransactionInterceptor);

        assertTrue(retryIndex >= 0, "retry advice is not applied to the controller at all: " + advisors);
        assertTrue(transactionIndex >= 0, "transaction advice is not applied: " + advisors);
        assertTrue(retryIndex < transactionIndex,
            "the retry must run OUTSIDE the transaction so each attempt gets a fresh one; advisors: " + advisors);
    }

    private static int indexOf(List<Advisor> advisors, java.util.function.Predicate<Advisor> match) {
        for (int index = 0; index < advisors.size(); index++) {
            if (match.test(advisors.get(index))) {
                return index;
            }
        }

        return -1;
    }

}
