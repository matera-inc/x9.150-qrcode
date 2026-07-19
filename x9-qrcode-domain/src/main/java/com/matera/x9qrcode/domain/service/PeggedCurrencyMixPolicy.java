/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.service;

import com.matera.x9qrcode.domain.exception.BusinessRuleException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Default {@link CurrencyMixPolicy} backed by configured peg groups.
 * <p>
 * Each group is a set of currency codes that track the same peg target and may therefore be mixed on
 * a single request (e.g. dollar-pegged USD/USDC/USDT). Currencies from two different groups must not
 * be combined, and a currency not listed in any group is non-pegged and must stand alone. Groups are
 * normalized to upper case so comparison is case-insensitive. The module stays currency-agnostic:
 * unknown currencies are never rejected on their own, only the mixing of currencies that do not all
 * share one peg group is.
 */
public class PeggedCurrencyMixPolicy implements CurrencyMixPolicy {

    private static final String CURRENCY_FIELD = "currency";

    private final List<Set<String>> peggedGroups;

    public PeggedCurrencyMixPolicy(List<Set<String>> peggedGroups) {
        this.peggedGroups = normalize(peggedGroups);
    }

    @Override
    public void validate(Collection<String> currencies) {
        if (isNull(currencies)) {
            return;
        }

        // Distinct currencies keyed by upper-case code, keeping the first-seen original for messages.
        Map<String, String> distinct = new LinkedHashMap<>();
        for (String currency : currencies) {
            if (isNull(currency) || currency.isBlank()) {
                continue;
            }
            distinct.putIfAbsent(currency.trim().toUpperCase(Locale.ROOT), currency.trim());
        }

        // Zero or one distinct currency is always valid (a lone non-pegged currency is fine).
        if (distinct.size() <= 1) {
            return;
        }

        // Two or more distinct currencies: they must all belong to the same peg group.
        boolean sharedGroup = peggedGroups.stream().anyMatch(group -> group.containsAll(distinct.keySet()));
        if (!sharedGroup) {
            String currencyList = String.join(", ", distinct.values());
            throw new BusinessRuleException(
                CURRENCY_FIELD,
                ("Currencies [%s] cannot be combined on the same request; currencies mixed on one request "
                    + "must all belong to the same pegged group, or the request must use a single currency")
                    .formatted(currencyList)
            );
        }
    }

    private static List<Set<String>> normalize(List<Set<String>> peggedGroups) {
        if (isNull(peggedGroups)) {
            return List.of();
        }

        return peggedGroups.stream()
            .filter(Objects::nonNull)
            .map(PeggedCurrencyMixPolicy::normalizeGroup)
            .filter(group -> !group.isEmpty())
            .collect(Collectors.toUnmodifiableList());
    }

    private static Set<String> normalizeGroup(Set<String> group) {
        return group.stream()
            .filter(currency -> nonNull(currency) && !currency.isBlank())
            .map(currency -> currency.trim().toUpperCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }

}
