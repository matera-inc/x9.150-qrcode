/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

public class AmountRangeVO {

    private final AmountVO minAmount;
    private final AmountVO maxAmount;

    public AmountRangeVO(Long minAmount, Long maxAmount) {
        this.minAmount = new AmountVO(minAmount);
        this.maxAmount = new AmountVO(maxAmount);

        if (minAmount() > maxAmount()) {
            throw new ValueObjectRuleException("Minimum amount cannot be greater than maximum amount.");
        }
    }

    public Long minAmount() {
        return minAmount.value();
    }

    public Long maxAmount() {
        return maxAmount.value();
    }

}
