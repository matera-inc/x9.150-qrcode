/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;


import static java.util.Objects.isNull;

public record TipRangeVO (
    Integer minimum,
    Integer maximum
) {

    public TipRangeVO {
        if (isNull(minimum)) {
            throw new ValueObjectRuleException("Tip range min value cannot be null");
        }

        if (isNull(maximum)) {
            throw new ValueObjectRuleException("Tip range max value cannot be null");
        }

        if (minimum.compareTo(maximum) > 0) {
            throw new ValueObjectRuleException("Tip range min value cannot be greater than tip range max value");
        }
    }

}
