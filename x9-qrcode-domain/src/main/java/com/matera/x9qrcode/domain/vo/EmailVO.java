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

import static java.util.Objects.nonNull;

@EqualsAndHashCode
public class EmailVO extends ValueObject<String> {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    public EmailVO(String value) {
        if (nonNull(value) && !EMAIL_PATTERN.matcher(value).matches()) {
            throw new ValueObjectRuleException("'email' is invalid.");
        }

        this.value = value;
    }

}
