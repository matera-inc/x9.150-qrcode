/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CurrencyConfigurationTest {

    /**
     * <b>X9-CUR-070</b> — peg groups load from strict JSON as upper-cased sets.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> Strict parsing, so a typo in pegged-currencies.json fails at startup rather than silently
     * producing an empty group — which would disable the mix check entirely while looking healthy.
     * Upper-casing at load is what lets the policy compare case-insensitively without doing so on
     * every call.
     */
    @Test
    void shouldLoadPeggedCurrencyGroupsFromStrictJsonAsUpperCasedSets() {
        List<Set<String>> peggedGroups = CurrencyConfiguration.loadPeggedCurrencyGroups(
            new ClassPathResource("pegged-currencies.json"), new ObjectMapper());

        assertEquals(List.of(Set.of("USD", "USDC", "USDT", "FRNT"), Set.of("BRL", "BRL1")), peggedGroups);
    }

}
