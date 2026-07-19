/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.dto.enumerated;

public enum NotificationKindEnumDTO {
    DEFAULT,
    EXTERNAL;

    public static NotificationKindEnumDTO fromValue(String value) {
        for (NotificationKindEnumDTO b : NotificationKindEnumDTO.values()) {
            if (b.name().equals(value)) {
                return b;
            }
        }

        throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
}
