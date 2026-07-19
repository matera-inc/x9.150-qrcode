/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import static java.util.Objects.isNull;

/**
 * Per ANSI X9.150 §14.4, the presence of the editable object ($.paymentMethods[].editable) is what
 * indicates the amount due is editable — there is no explicit boolean flag. When editable is present
 * its range is mandatory (§14.4.1), so this value object only exists when the amount is editable.
 */
public record EditableAmountVO(
    AmountRangeVO range
) {

    public EditableAmountVO {
        if (isNull(range)) {
            throw new ValueObjectRuleException("Editable amount range is required when the amount is editable.");
        }
    }

}
