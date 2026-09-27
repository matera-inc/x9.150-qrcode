/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.service;

import com.matera.x9qrcode.domain.exception.BusinessRuleException;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeggedCurrencyMixPolicyTest {

    private final CurrencyMixPolicy policy =
        new PeggedCurrencyMixPolicy(List.of(Set.of("USD", "USDC", "USDT", "FRNT"), Set.of("BRL", "BRL1")));

    @Test
    void shouldAllowAnyMixWithinASinglePegGroup() {
        assertDoesNotThrow(() -> policy.validate(List.of("USD", "USDC")));
    }

    @Test
    void shouldAllowASingleNonPeggedCurrencyAlone() {
        assertDoesNotThrow(() -> policy.validate(List.of("BTC", "BTC")));
    }

    @Test
    void shouldRejectANonPeggedCurrencyMixedWithAPeggedCurrency() {
        BusinessRuleException exception = assertThrowsExactly(BusinessRuleException.class,
            () -> policy.validate(List.of("USD", "BTC")));

        assertEquals("currency", exception.field());
        assertEquals(
            "Currencies [USD, BTC] cannot be combined on the same request; currencies mixed on one request "
                + "must all belong to the same pegged group, or the request must use a single currency",
            exception.getMessage());
    }

    @Test
    void shouldRejectTwoDifferentNonPeggedCurrencies() {
        BusinessRuleException exception = assertThrowsExactly(BusinessRuleException.class,
            () -> policy.validate(List.of("BTC", "ETH")));

        assertEquals("currency", exception.field());
        assertEquals(
            "Currencies [BTC, ETH] cannot be combined on the same request; currencies mixed on one request "
                + "must all belong to the same pegged group, or the request must use a single currency",
            exception.getMessage());
    }

    @Test
    void shouldRejectPeggedCurrenciesFromDifferentGroups() {
        BusinessRuleException exception = assertThrowsExactly(BusinessRuleException.class,
            () -> policy.validate(List.of("USD", "BRL")));

        assertEquals("currency", exception.field());
        assertEquals(
            "Currencies [USD, BRL] cannot be combined on the same request; currencies mixed on one request "
                + "must all belong to the same pegged group, or the request must use a single currency",
            exception.getMessage());
    }

    @Test
    void shouldCompareCurrenciesCaseInsensitively() {
        assertDoesNotThrow(() -> policy.validate(List.of("usd", "UsDc")));
    }

    @Test
    void shouldIgnoreNullAndBlankCurrencies() {
        assertDoesNotThrow(() -> policy.validate(java.util.Arrays.asList("BTC", null, "  ", "btc")));
    }

    /**
     * Three dollar-pegged tokens on one request, which is the shape this deployment actually ships:
     * a USD bank method beside a USDC or FRNT method on Solana.
     *
     * <p>FRNT is the Frontier Stable Token issued by the State of Wyoming — pegged to the dollar and
     * redeemable at par. That redeemability is why it sits in the dollar group rather than merely
     * near it: the peg rule is about whether two amounts on one QR Code denote the same value, not
     * about whether two tokens happen to trade at a similar price.
     */
    @Test
    void severalDollarPeggedTokensMayShareARequest() {
        assertDoesNotThrow(() -> policy.validate(List.of("USD", "USDC", "FRNT")));
    }

    @Test
    void aDollarPeggedTokenStillCannotJoinAnotherGroup() {
        BusinessRuleException thrown = assertThrows(BusinessRuleException.class,
            () -> policy.validate(List.of("FRNT", "BRL")));

        assertTrue(thrown.getMessage().contains("FRNT") && thrown.getMessage().contains("BRL"),
            thrown.getMessage());
    }

}
