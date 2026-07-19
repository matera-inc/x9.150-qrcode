/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import java.time.OffsetDateTime;

import static java.util.Objects.isNull;

public record PaymentMethodVO(
    String currency,
    OffsetDateTime validUntil,
    AmountVO amount,
    EditableAmountVO editable,
    NetworksVO networks
) {

    public PaymentMethodVO {
        if (isNull(currency)) {
            throw new ValueObjectRuleException("Payment method currency must not be null");
        }

        if (isNull(validUntil)) {
            throw new ValueObjectRuleException("Payment method valid until must not be null");
        }

        if (isNull(amount)) {
            throw new ValueObjectRuleException("Payment method amount must not be null");
        }

        if (isNull(networks)) {
            throw new ValueObjectRuleException("Payment method networks must not be null");
        }
    }

}
