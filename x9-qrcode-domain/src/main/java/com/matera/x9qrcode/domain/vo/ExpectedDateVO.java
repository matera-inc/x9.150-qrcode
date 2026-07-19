/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;

import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

import static java.util.Objects.nonNull;

@EqualsAndHashCode
public class ExpectedDateVO extends ValueObject<OffsetDateTime> {

    public ExpectedDateVO(OffsetDateTime value) {
        if (nonNull(value) && DateTimeUtils.nowUTC().isAfter(value)) {
            throw new ValueObjectRuleException("ExpectedDate must not be less than the current date.");
        }

        this.value = value;
    }


}
