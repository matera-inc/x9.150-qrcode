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
public class NameVO extends ValueObject<String> {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\x20-\\x7E]{1,50}$");

    public NameVO(String value) {
        if (nonNull(value) && !NAME_PATTERN.matcher(value).matches()) {
            throw new ValueObjectRuleException("Name is invalid.");
        }

        this.value = value;
    }

    public String value() {
        return value;
    }

}
