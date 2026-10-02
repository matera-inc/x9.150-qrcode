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

    /**
     * <b>X9-CUR-020</b> — currencies within one peg group may share a QR Code.
     *
     * <p><b>Source:</b> Ours. The standard permits several payment methods; whether their currencies may differ is
     * not addressed. ADR-0012 and pegged-currencies.json.
     *
     * <p><b>Why:</b> USD, USDC and FRNT are all dollar-denominated, so one bill offered across them asks for the
     * same value by each route. The acceptance case for the whole policy.
     */
    @Test
    void shouldAllowAnyMixWithinASinglePegGroup() {
        assertDoesNotThrow(() -> policy.validate(List.of("USD", "USDC")));
    }

    /**
     * <b>X9-CUR-021</b> — a single non-pegged currency alone is allowed.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> The rule is about MIXING. One currency cannot disagree with itself, whatever it is.
     */
    @Test
    void shouldAllowASingleNonPeggedCurrencyAlone() {
        assertDoesNotThrow(() -> policy.validate(List.of("BTC", "BTC")));
    }

    /**
     * <b>X9-CUR-022</b> — a non-pegged currency mixed with a pegged one is refused.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> The payload carries one amount per currency with no exchange rate between them. Offering a
     * bill in two unrelated currencies means the payer picks whichever is cheaper today, and the
     * biller never agreed to that.
     */
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

    /**
     * <b>X9-CUR-023</b> — two different non-pegged currencies are refused.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> Same reason as CUR-022 with neither side anchored — the amounts are unrelated numbers.
     */
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

    /**
     * <b>X9-CUR-024</b> — pegged currencies from different groups are refused.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> Being pegged is not enough; they must be pegged to the SAME thing. A euro-pegged token and a
     * dollar-pegged token are as unrelated as any other pair.
     */
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

    /**
     * <b>X9-CUR-025</b> — peg groups are compared case-insensitively.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> Treating "usdc" and "USDC" as two currencies would let a case difference slip a forbidden mix
     * past the check — the policy failing OPEN, which is the worst direction.
     */
    @Test
    void shouldCompareCurrenciesCaseInsensitively() {
        assertDoesNotThrow(() -> policy.validate(List.of("usd", "UsDc")));
    }

    /**
     * <b>X9-CUR-026</b> — null and blank currencies are ignored.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> Absence is not a currency; see CUR-015.
     */
    @Test
    void shouldIgnoreNullAndBlankCurrencies() {
        assertDoesNotThrow(() -> policy.validate(java.util.Arrays.asList("BTC", null, "  ", "btc")));
    }

    /**
     * <b>X9-CUR-027</b> — several dollar-pegged tokens may share a request.
     *
     * <p><b>Source:</b> Ours. pegged-currencies.json.
     *
     * <p><b>Why:</b> Explicitly more than two, because a rule written for pairs often breaks on the third member.
     */
    @Test
    void severalDollarPeggedTokensMayShareARequest() {
        assertDoesNotThrow(() -> policy.validate(List.of("USD", "USDC", "FRNT")));
    }

    /**
     * <b>X9-CUR-028</b> — a dollar-pegged token still cannot join another group.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> The complement of CUR-027: membership of a group permits mixing INSIDE it and nothing more.
     */
    @Test
    void aDollarPeggedTokenStillCannotJoinAnotherGroup() {
        BusinessRuleException thrown = assertThrows(BusinessRuleException.class,
            () -> policy.validate(List.of("FRNT", "BRL")));

        assertTrue(thrown.getMessage().contains("FRNT") && thrown.getMessage().contains("BRL"),
            thrown.getMessage());
    }

}
