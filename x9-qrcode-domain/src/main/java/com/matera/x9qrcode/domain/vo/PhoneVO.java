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
public class PhoneVO extends ValueObject<String> {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^(\\+1[2-9]\\d{2}[2-9]\\d{6}|\\+\\d{1,3}\\d{4,14})$");

    public PhoneVO(String value) {
        if (nonNull(value) && !PHONE_PATTERN.matcher(value).matches()) {
            throw new ValueObjectRuleException("Phone is invalid.");
        }

        this.value = value;
    }

}
