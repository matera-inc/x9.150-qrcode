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

    /**
     * <b>X9-CUR-010</b> — a currency on the deployment's list passes.
     *
     * <p><b>Source:</b> Ours. The payload format is currency-agnostic; what a DEPLOYMENT accepts is decided by the
     * rails it settles. ADR-0012, INTERPRETATION I-3.
     *
     * <p><b>Why:</b> The acceptance case. Without it the refusals below would pass against a policy that refused
     * every currency.
     */
    @Test
    void anAllowedCurrencyPasses() {
        assertDoesNotThrow(() -> USD_ONLY.validate(List.of("USD", "USD")));
    }

    /**
     * <b>X9-CUR-011</b> — surrounding whitespace is forgiven.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> A currency arriving as " USD " is a formatting accident in the caller's serialiser, not a
     * different currency. Refusing it would be a confusing failure for a correct intent.
     */
    @Test
    void surroundingSpaceIsForgiven() {
        assertDoesNotThrow(() -> USD_ONLY.validate(List.of(" USD ")));
    }

    /**
     * <b>X9-CUR-012</b> — the wrong case is refused, naming the spelling to use.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §2.3 — <i>"SHALL be a string that adheres to ISO 4217 alphabetic currency
     * codes"</i>, which are upper case. Conformance; the helpful message is ours.
     *
     * <p><b>Why:</b> "usd" is refused rather than silently corrected, because a deployment that quietly fixes its
     * input teaches callers nothing and diverges from every other implementation. The message names
     * the spelling to use, so the fix takes seconds rather than a support round trip.
     */
    @Test
    void theWrongCaseIsRefusedWithTheSpellingToUse() {
        BusinessRuleException thrown =
            assertThrows(BusinessRuleException.class, () -> USD_ONLY.validate(List.of("usd")));

        assertTrue(thrown.getMessage().contains("usd") && thrown.getMessage().contains("USD"),
            "name both what was sent and what to send: " + thrown.getMessage());
    }

    /**
     * <b>X9-CUR-013</b> — a currency outside the list is refused, by name.
     *
     * <p><b>Source:</b> Ours. ADR-0012 — refuse what this deployment cannot honour.
     *
     * <p><b>Why:</b> A QR Code advertising a currency no configured rail settles is a promise that cannot be kept,
     * and the payer discovers it at the till where nothing can be done.
     */
    @Test
    void aCurrencyOutsideTheListIsRefusedByName() {
        BusinessRuleException thrown =
            assertThrows(BusinessRuleException.class, () -> USD_ONLY.validate(List.of("USD", "EUR")));

        assertTrue(thrown.getMessage().contains("EUR"),
            "the biller must be told which currency was refused: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("USD"),
            "and what is supported, so they know what to send: " + thrown.getMessage());
    }

    /**
     * <b>X9-CUR-014</b> — every offending currency is named at once.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> Reporting one failure per round trip turns a three-currency mistake into three deployments.
     */
    @Test
    void everyOffendingCurrencyIsNamedAtOnce() {
        BusinessRuleException thrown =
            assertThrows(BusinessRuleException.class, () -> USD_ONLY.validate(List.of("EUR", "BRL")));

        assertTrue(thrown.getMessage().contains("EUR") && thrown.getMessage().contains("BRL"),
            thrown.getMessage());
    }

    /**
     * <b>X9-CUR-015</b> — blanks and nulls are ignored rather than refused.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> Absence is not a currency. The schema enforces presence at the edge; inventing a second
     * opinion here would produce two different errors for one mistake.
     */
    @Test
    void blanksAndNullsAreIgnoredRatherThanRefused() {
        assertDoesNotThrow(() -> USD_ONLY.validate(Arrays.asList("USD", null, "  ")));
    }

    /**
     * <b>X9-CUR-016</b> — an empty allow-list accepts anything.
     *
     * <p><b>Source:</b> Ours, a deliberate escape hatch. INTERPRETATION I-3.
     *
     * <p><b>Why:</b> An operator who clears supported-currencies.json is turning the check OFF, not configuring a
     * deployment that accepts nothing. The alternative makes a blank config file brick the service.
     */
    @Test
    void anEmptyAllowListAcceptsAnything() {
        SupportedCurrencyPolicy unrestricted = new AllowListSupportedCurrencyPolicy(List.of());

        assertDoesNotThrow(() -> unrestricted.validate(List.of("EUR", "BTC", "XPTO")));
    }

    /**
     * <b>X9-CUR-017</b> — a null allow-list behaves like an empty one.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> A missing config file and a cleared one mean the same thing to an operator, so they must
     * behave the same way rather than differing by how the absence was expressed.
     */
    @Test
    void aNullAllowListBehavesLikeAnEmptyOne() {
        assertDoesNotThrow(() -> new AllowListSupportedCurrencyPolicy(null).validate(List.of("EUR")));
    }

}
