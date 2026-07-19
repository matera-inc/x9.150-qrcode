/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import static java.util.Objects.isNull;

public record AmountDueVO(
    CurrencyAmountVO currencyAmount,
    AdjustmentVO adjustment
) {

    public AmountDueVO {
        if (isNull(currencyAmount)) {
            throw new ValueObjectRuleException("Amount due currency amount must not be null");
        }

        if (currencyAmount.amount() <= 0) {
            throw new ValueObjectRuleException("Amount due currency amount value must be greater than zero");
        }
    }

}
