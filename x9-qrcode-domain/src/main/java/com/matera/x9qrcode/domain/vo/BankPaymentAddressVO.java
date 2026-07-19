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

public record BankPaymentAddressVO(
    String routingNumber,
    String accountNumber
) {

    private static final Pattern ROUTING_NUMBER_PATTERN = Pattern.compile("^\\d{9}$");
    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile("^\\d{4,17}$");

    public BankPaymentAddressVO {
        if (isNull(routingNumber) || routingNumber.isBlank()) {
            throw new ValueObjectRuleException("RoutingNumber must not be null or blank.");
        }

        if (isNull(accountNumber) || accountNumber.isBlank()) {
            throw new ValueObjectRuleException("AccountNumber must not be null or blank.");
        }

        if (!ROUTING_NUMBER_PATTERN.matcher(routingNumber).matches()) {
            throw new ValueObjectRuleException("RoutingNumber is invalid.");
        }

        if (!ACCOUNT_NUMBER_PATTERN.matcher(routingNumber).matches()) {
            throw new ValueObjectRuleException("AccountNumber is invalid.");
        }
    }

}
