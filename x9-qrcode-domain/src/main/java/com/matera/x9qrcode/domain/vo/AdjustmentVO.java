/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;
import com.matera.x9qrcode.domain.vo.enumerated.FormulaEnum;

import static java.util.Objects.isNull;

public record AdjustmentVO(
    FormulaEnum formula,
    AdjustmentParametersVO parameters
) {

    public AdjustmentVO {
        if (isNull(formula)) {
            throw new ValueObjectRuleException("Adjustment formula must not be null");
        }

        if (isNull(parameters)) {
            throw new ValueObjectRuleException("Adjustment parameters must not be null");
        }
    }

}
