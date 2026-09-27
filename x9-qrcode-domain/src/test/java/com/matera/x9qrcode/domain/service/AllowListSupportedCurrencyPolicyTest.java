/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.service;

import com.matera.x9qrcode.domain.exception.BusinessRuleException;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The allow list decides what this deployment can settle, which is not the same question as what
 * the format can carry. See {@link SupportedCurrencyPolicy} and ADR-0012.
 */
class AllowListSupportedCurrencyPolicyTest {

    private static final SupportedCurrencyPolicy USD_ONLY =
        new AllowListSupportedCurrencyPolicy(List.of("USD"));

    @Test
    void anAllowedCurrencyPasses() {
        assertDoesNotThrow(() -> USD_ONLY.validate(List.of("USD", "USD")));
    }

    /**
     * Surrounding space is a transport artefact and is forgiven; case is not. ISO 4217 codes are
     * upper-case, so accepting {@code usd} would mean emitting {@code usd} — and the caller reading
     * back something other than what they sent.
     */
    @Test
    void surroundingSpaceIsForgiven() {
        assertDoesNotThrow(() -> USD_ONLY.validate(List.of(" USD ")));
    }

    @Test
    void theWrongCaseIsRefusedWithTheSpellingToUse() {
        BusinessRuleException thrown =
            assertThrows(BusinessRuleException.class, () -> USD_ONLY.validate(List.of("usd")));

        assertTrue(thrown.getMessage().contains("usd") && thrown.getMessage().contains("USD"),
            "name both what was sent and what to send: " + thrown.getMessage());
    }

    @Test
    void aCurrencyOutsideTheListIsRefusedByName() {
        BusinessRuleException thrown =
            assertThrows(BusinessRuleException.class, () -> USD_ONLY.validate(List.of("USD", "EUR")));

        assertTrue(thrown.getMessage().contains("EUR"),
            "the biller must be told which currency was refused: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("USD"),
            "and what is supported, so they know what to send: " + thrown.getMessage());
    }

    /** One message naming every offender beats three round trips discovering them one at a time. */
    @Test
    void everyOffendingCurrencyIsNamedAtOnce() {
        BusinessRuleException thrown =
            assertThrows(BusinessRuleException.class, () -> USD_ONLY.validate(List.of("EUR", "BRL")));

        assertTrue(thrown.getMessage().contains("EUR") && thrown.getMessage().contains("BRL"),
            thrown.getMessage());
    }

    @Test
    void blanksAndNullsAreIgnoredRatherThanRefused() {
        assertDoesNotThrow(() -> USD_ONLY.validate(Arrays.asList("USD", null, "  ")));
    }

    /**
     * An empty list means "no opinion", for a deployment that settles by some route this service
     * does not model. The format itself is currency-agnostic; the allow list is the deployment's
     * narrowing of it, and a deployment may decline to narrow.
     */
    @Test
    void anEmptyAllowListAcceptsAnything() {
        SupportedCurrencyPolicy unrestricted = new AllowListSupportedCurrencyPolicy(List.of());

        assertDoesNotThrow(() -> unrestricted.validate(List.of("EUR", "BTC", "XPTO")));
    }

    @Test
    void aNullAllowListBehavesLikeAnEmptyOne() {
        assertDoesNotThrow(() -> new AllowListSupportedCurrencyPolicy(null).validate(List.of("EUR")));
    }

}
