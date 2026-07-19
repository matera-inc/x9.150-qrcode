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

        if (fixed < 0) {
            throw new ValueObjectRuleException("Fixed late fee must be greater than 0.");
        }

        if (perDay < 0) {
            throw new BusinessRuleException("Per day late fee must be greater than 0.");
        }
    }

}
