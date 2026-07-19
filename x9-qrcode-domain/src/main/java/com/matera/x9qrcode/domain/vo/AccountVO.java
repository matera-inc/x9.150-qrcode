/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import static java.util.Objects.isNull;

public record AccountVO(
    String id,
    String schemaName
) {

    public AccountVO {
        if (isNull(id)) {
            throw new ValueObjectRuleException("Account id must not be null.");
        }

        if (isNull(schemaName)) {
            throw new ValueObjectRuleException("Account schemaName must not be null.");
        }
    }

}
