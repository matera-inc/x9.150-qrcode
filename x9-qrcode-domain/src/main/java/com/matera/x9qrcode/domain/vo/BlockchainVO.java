/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;
import com.matera.x9qrcode.domain.vo.enumerated.ActionEnum;

import static java.util.Objects.isNull;

public record BlockchainVO(
    ActionEnum action,
    CryptoWalletPaymentAddressVO from,
    CryptoWalletPaymentAddressVO to
) {
    public BlockchainVO {
        if (isNull(action)) {
            throw new ValueObjectRuleException("Blockchain action must not be null");
        }

        if (isNull(from)) {
            throw new ValueObjectRuleException("Blockchain from address must not be null");
        }

        if (isNull(to)) {
            throw new ValueObjectRuleException("Blockchain to address must not be null");
        }
    }
}
