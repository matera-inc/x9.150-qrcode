/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;
import com.matera.x9qrcode.domain.utils.UUIDUtils;

import lombok.EqualsAndHashCode;

import java.util.UUID;
import java.util.regex.Pattern;

import static java.util.Objects.isNull;

@EqualsAndHashCode
public class QRCodeIdVO extends ValueObject<UUID> {

    private static final Pattern ID_PATTERN = Pattern.compile("[0-9A-F]{32}", Pattern.CASE_INSENSITIVE);

    private QRCodeIdVO(UUID value) {
        if (isNull(value)) {
            throw new ValueObjectRuleException("QRCodeId can not be null");
        }

        this.value = value;
    }

    private QRCodeIdVO(String value) {
        if (isNull(value) || value.isBlank()) {
            throw new ValueObjectRuleException("QRCodeId can not be null or empty.");
        }

        if (!ID_PATTERN.matcher(value).matches()) {
            throw new ValueObjectRuleException("QRCodeId is invalid.");
        }

        this.value = UUIDUtils.parse(value, "QRCodeId");
    }

    public String valueAsString() {
        return UUIDUtils.toShortenString(value);
    }

    public static QRCodeIdVO from(UUID value) {
        return new QRCodeIdVO(value);
    }

    public static QRCodeIdVO from(String value) {
        return new QRCodeIdVO(value);
    }

}
