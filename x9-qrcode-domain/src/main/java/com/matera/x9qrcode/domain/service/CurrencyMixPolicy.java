/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.service;

import java.util.Collection;

/**
 * Enforces the ANSI X9.150 v1.0 "no mixing of non-pegged currencies" rule within a single payment
 * request. Given every currency used on the request (bill amount-due currency plus each payment
 * method currency), the request is valid if EITHER every currency belongs to the same configured peg
 * group (any mix within one group is allowed, e.g. USD + USDC) OR there is exactly one distinct
 * currency overall (a single non-pegged currency alone, e.g. BTC-only). Combining currencies that do
 * not all share one peg group (a non-pegged currency mixed with anything, or two currencies from
 * different groups such as USD + BRL) is a violation.
 */
public interface CurrencyMixPolicy {

    /**
     * Validates the given collection of currency codes against the peg-mixing rule. Null/blank
     * entries are ignored and comparison is case-insensitive.
     *
     * @param currencies every currency used on the request
     * @throws com.matera.x9qrcode.domain.exception.BusinessRuleException when the currencies do not
     *                                                                    all share a single peg group
     */
    void validate(Collection<String> currencies);

}
