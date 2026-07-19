/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import lombok.EqualsAndHashCode;

import java.util.regex.Pattern;

import static java.util.Objects.isNull;

@EqualsAndHashCode(callSuper = true)
public class DescriptionVO extends ValueObject<String> {

    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile("^[\\x20-\\x7E]*$");

    public DescriptionVO(String value) {
        if (isNull(value)) {
            throw new ValueObjectRuleException("Description must not be null.");
        }

        if (value.length() > 100) {
            throw new ValueObjectRuleException("Description must not exceed 100 characters.");
        }

        if (!DESCRIPTION_PATTERN.matcher(value).matches()) {
            throw new ValueObjectRuleException("Description is invalid.");
        }

        this.value = value;
    }

}
