/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.dto.enumerated;

public enum ActionEnumDTO {
    PAYMENT_INITIATED("PAYMENT_INITIATED"),
    SENT("SENT"),
    NOT_SENT("NOT_SENT");

    private final String value;

    ActionEnumDTO(String value) {
        this.value = value;
    }

    public static ActionEnumDTO fromValue(String value) {
        for (ActionEnumDTO b : ActionEnumDTO.values()) {
            if (b.value.equals(value)) {
                return b;
            }
        }

        throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }

    public String value() {
        return value;
    }
}
