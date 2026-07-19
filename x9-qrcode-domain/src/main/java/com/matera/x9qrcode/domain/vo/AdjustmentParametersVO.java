/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import java.util.Collections;
import java.util.List;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public record AdjustmentParametersVO(
    List<DiscountVO> discounts,
    LateFeesVO lateFees
) {

    public AdjustmentParametersVO {
        if (isNull(lateFees)) {
            throw new ValueObjectRuleException("Adjustment parameters late fees must not be null");
        }

        if (nonNull(discounts) && discounts.isEmpty()) {
            throw new ValueObjectRuleException("Adjustment parameters discounts must not be empty");
        }
    }

    @Override
    public List<DiscountVO> discounts() {
        return isNull(discounts)
            ? null
            : Collections.unmodifiableList(discounts);
    }

}
