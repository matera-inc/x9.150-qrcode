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

    @Test
    void shouldLoadPeggedCurrencyGroupsFromStrictJsonAsUpperCasedSets() {
        List<Set<String>> peggedGroups = CurrencyConfiguration.loadPeggedCurrencyGroups(
            new ClassPathResource("pegged-currencies.json"), new ObjectMapper());

        assertEquals(List.of(Set.of("USD", "USDC", "USDT"), Set.of("BRL", "BRL1")), peggedGroups);
    }

}
