/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo.enumerated;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

public enum FormulaEnum {
    FIXED_DISCOUNT_LATE_FEE_LINEAR_INTEREST("FixedDiscountLateFeeLinearInterest");

    private final String value;

    FormulaEnum(String value) {
        this.value = value;
    }

    public static FormulaEnum fromValue(String value) {
        for (FormulaEnum b : FormulaEnum.values()) {
            if (b.value.equals(value)) {
                return b;
            }
        }

        throw new ValueObjectRuleException("Unexpected value '" + value + "'");
    }

    public String value() {
        return value;
    }

}
