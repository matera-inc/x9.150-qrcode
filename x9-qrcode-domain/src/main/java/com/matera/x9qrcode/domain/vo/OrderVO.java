/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import java.time.LocalDate;

public class OrderVO {

    private final NumberIdentifierVO number;
    private final LocalDate date;

    public OrderVO(NumberIdentifierVO number, LocalDate date) {
        this.number = number;
        this.date = date;

    }

    public String number() {
        return number.value();
    }

    public LocalDate date() {
        return date;
    }

}
