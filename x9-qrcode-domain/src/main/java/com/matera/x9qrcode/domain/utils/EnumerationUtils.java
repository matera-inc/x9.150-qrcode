/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.EnumUtils;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import static java.util.Objects.nonNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EnumerationUtils {

    public static <E extends Enum<E>> E getNullableEnumIgnoreCases(final Class<E> enumClass, final String enumName) {
        //@formatter:off
        return EnumUtils.isValidEnumIgnoreCase(enumClass, enumName)
            ? EnumUtils.getEnumIgnoreCase(enumClass, enumName)
            : null;
        //@formatter:on
    }

    public static <E extends Enum<E>> E getNullableParsedEnum(final Class<E> enumClass, final Enum<?> enumeration) {
        //@formatter:off
        return nonNull(enumeration)
            ? Enum.valueOf(enumClass, enumeration.name())
            : null;
        //@formatter:on
    }

    public static <E extends Enum<E>> E getNullableParsedEnum(final Class<E> enumClass, final String enumeration) {
        //@formatter:off
        return isNotBlank(enumeration)
            ? Enum.valueOf(enumClass, enumeration)
            : null;
        //@formatter:on
    }

    public static <E extends Enum<E>> String getNullableEnumValue(E enumObj) {
        //@formatter:off
        return nonNull(enumObj)
            ? enumObj.name()
            : null;
        //@formatter:on
    }

}
