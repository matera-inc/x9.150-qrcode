/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import static java.util.Objects.isNull;

public record InvoiceeVO(
    NameVO name,
    PhoneVO phone,
    EmailVO email,
    AddressVO address
) {

    public InvoiceeVO {
        if (isNull(name)) {
            throw new ValueObjectRuleException("Invoicee name must not be null");
        }
    }

}
