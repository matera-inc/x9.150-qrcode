/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.service;

import com.matera.x9qrcode.domain.exception.BusinessRuleException;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

/**
 * {@link SupportedCurrencyPolicy} backed by a configured allow list.
 *
 * <p>An empty allow list disables the check: a deployment that settles through some other route can
 * accept every currency the format can carry, which is the format's own position.
 */
public class AllowListSupportedCurrencyPolicy implements SupportedCurrencyPolicy {

    private final Set<String> allowed;

    public AllowListSupportedCurrencyPolicy(Collection<String> allowedCurrencies) {
        this.allowed = isNull(allowedCurrencies)
            ? Set.of()
            : allowedCurrencies.stream()
                .filter(currency -> !isNull(currency) && !currency.isBlank())
                .map(currency -> currency.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public void validate(Collection<String> currencies) {
        if (allowed.isEmpty() || isNull(currencies)) {
            return;
        }

        Set<String> rejected = currencies.stream()
            .filter(currency -> !isNull(currency) && !currency.isBlank())
            .map(currency -> currency.trim().toUpperCase(Locale.ROOT))
            .filter(currency -> !allowed.contains(currency))
            .collect(Collectors.toCollection(LinkedHashSet::new));

        if (rejected.isEmpty()) {
            return;
        }

        throw new BusinessRuleException("currency",
            "Currency %s is not settled by any payment rail this deployment supports. Supported: %s."
                .formatted(String.join(", ", rejected), String.join(", ", allowed)));
    }

}
