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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

/**
 * {@link SupportedCurrencyPolicy} backed by a configured allow list.
 *
 * <p>The listed spelling is the <b>only</b> spelling this API accepts. ISO 4217 codes are upper-case
 * and §2.3's own example is {@code "USD"}, so accepting {@code usd} would mean emitting {@code usd}
 * — a non-conformant code that the caller then reads back differing from what they sent. As with
 * network names, leniency belongs at the boundary we do not control (a payer's notification), not
 * inside our own API.
 *
 * <p>An empty allow list disables the check: a deployment that settles through some other route can
 * accept every currency the format can carry, which is the format's own position.
 */
public class AllowListSupportedCurrencyPolicy implements SupportedCurrencyPolicy {

    /** Lower-cased code to the one spelling this deployment accepts and emits. */
    private final Map<String, String> allowed;

    public AllowListSupportedCurrencyPolicy(Collection<String> allowedCurrencies) {
        this.allowed = isNull(allowedCurrencies)
            ? Map.of()
            : allowedCurrencies.stream()
                .filter(currency -> !isNull(currency) && !currency.isBlank())
                .map(String::trim)
                .collect(Collectors.toMap(
                    currency -> currency.toLowerCase(Locale.ROOT),
                    currency -> currency,
                    (first, second) -> first,
                    LinkedHashMap::new));
    }

    @Override
    public void validate(Collection<String> currencies) {
        if (allowed.isEmpty() || isNull(currencies)) {
            return;
        }

        Set<String> unsupported = new LinkedHashSet<>();

        for (String currency : currencies) {
            if (isNull(currency) || currency.isBlank()) {
                continue;
            }

            String sent = currency.trim();
            String expected = allowed.get(sent.toLowerCase(Locale.ROOT));

            if (isNull(expected)) {
                unsupported.add(sent);
                continue;
            }

            if (!expected.equals(sent)) {
                throw new BusinessRuleException("currency",
                    "Currency \"%s\" must be written as \"%s\". This service accepts one spelling on its own API — the one it emits."
                        .formatted(sent, expected));
            }
        }

        if (unsupported.isEmpty()) {
            return;
        }

        throw new BusinessRuleException("currency",
            "Currency %s is not settled by any payment rail this deployment supports. Supported: %s."
                .formatted(String.join(", ", unsupported), String.join(", ", allowed.values())));
    }

}
