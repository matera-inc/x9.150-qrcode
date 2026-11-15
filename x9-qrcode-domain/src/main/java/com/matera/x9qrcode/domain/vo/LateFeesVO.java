/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import static java.util.Objects.isNull;

public record LateFeesVO(
    Long fixed,
    Long perDay,
    String explanation
) {

    /** The late fees' path in a payment request body. */
    private static final String FIELD = "bill.amountDue.adjustments.parameters.lateFees";

    public LateFeesVO {
        if (isNull(fixed)) {
            throw new ValueObjectRuleException("Fixed late fee must not be null.");
        }

        if (isNull(perDay)) {
            throw new ValueObjectRuleException("Per day late fee must not be null.");
        }

        if (isNull(explanation) || explanation.isBlank()) {
            throw new ValueObjectRuleException("Late fee explanation must not be null or blank.");
        }

        // One rule, one kind of refusal, each naming its own field (owner decision 2026-11-15).
        if (fixed < 0) {
            throw new BusinessRuleException(FIELD + ".fixed", "must be greater than or equal to 0");
        }

        if (perDay < 0) {
            throw new BusinessRuleException(FIELD + ".perDay", "must be greater than or equal to 0");
        }

        AmountVO.requireAtMost18Digits(fixed);
        AmountVO.requireAtMost18Digits(perDay);
    }

}
