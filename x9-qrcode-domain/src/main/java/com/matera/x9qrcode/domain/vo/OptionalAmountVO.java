/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import lombok.EqualsAndHashCode;

import static java.util.Objects.nonNull;

@EqualsAndHashCode
public class OptionalAmountVO extends ValueObject<Long> {

    public OptionalAmountVO(Long value) {
        if (nonNull(value) && value < 0) {
            throw new ValueObjectRuleException("monetary values should not be negative.");
        }

        this.value = value;
    }

}
