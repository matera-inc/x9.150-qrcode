/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import static java.util.Objects.isNull;

public record DiscountVO(
    Integer daysBefore,
    Long discount,
    String explanation
) {

    public DiscountVO {
        if (discount <= 0) {
            throw new ValueObjectRuleException("Discount value must be greater than 0.");
        }

        if (daysBefore <= 0) {
            throw new ValueObjectRuleException("Days before discount must be greater than 0.");
        }

        if (isNull(explanation) || explanation.isBlank()) {
            throw new ValueObjectRuleException("Discount explanation must not be null or blank.");
        }
    }

}
