/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import java.util.regex.Pattern;

import static java.util.Objects.isNull;

public class CurrencyAmountVO {

    /**
     * Currency is a free-form code (ANSI X9.150 §13.5.2): an ISO 4217 alphabetic code (USD, EUR, JPY)
     * or a non-ISO digital-asset ticker (USDC, BTC, ETH), 1-32 characters. This module repeats the
     * informed value verbatim; the paying PSP interprets it.
     */
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Za-z]{1,32}$");

    private final AmountVO amount;
    private final String currency;

    public CurrencyAmountVO(Long amount, String currency) {
        if (isNull(amount)) {
            throw new ValueObjectRuleException("Currency amount must not be null.");
        }

        if (isNull(currency) || !CURRENCY_PATTERN.matcher(currency).matches()) {
            throw new ValueObjectRuleException("Currency must be a 1-32 character alphabetic code.");
        }

        this.amount = new AmountVO(amount);
        this.currency = currency;
    }

    public Long amount() {
        return amount.value();
    }

    public String currency() {
        return currency;
    }

}
