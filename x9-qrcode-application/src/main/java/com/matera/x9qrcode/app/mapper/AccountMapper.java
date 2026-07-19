/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.mapper;

import com.matera.x9qrcode.app.dto.AccountDTO;
import com.matera.x9qrcode.domain.vo.AccountVO;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AccountMapper {

    public static AccountVO map(AccountDTO input) {
        if (isNull(input)) {
            return null;
        }
        return new AccountVO(input.id(), input.schemaName());
    }

    public static AccountDTO map(AccountVO input) {
        if (isNull(input)) {
            return null;
        }
        return new AccountDTO(input.id(), input.schemaName());
    }
}
