/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.mapper;

import com.matera.x9qrcode.app.dto.UltimateCreditorDTO;
import com.matera.x9qrcode.domain.vo.UltimateCreditorVO;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UltimateCreditorMapper {

    public static UltimateCreditorVO map(UltimateCreditorDTO input) {
        if (isNull(input)) {
            return null;
        }
        return new UltimateCreditorVO(
            AccountMapper.map(input.account()),
            input.name(),
            input.phone(),
            input.email(),
            AddressMapper.map(input.address())
        );
    }

    public static UltimateCreditorDTO map(UltimateCreditorVO input) {
        if (isNull(input)) {
            return null;
        }
        return new UltimateCreditorDTO(
            AccountMapper.map(input.account()),
            input.name(),
            input.phone(),
            input.email(),
            AddressMapper.map(input.address())
        );
    }
}
