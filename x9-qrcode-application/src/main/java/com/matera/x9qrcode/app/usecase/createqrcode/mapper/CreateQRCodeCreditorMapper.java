/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.createqrcode.mapper;

import com.matera.x9qrcode.app.dto.CreditorDTO;
import com.matera.x9qrcode.app.mapper.AddressMapper;
import com.matera.x9qrcode.app.mapper.UltimateCreditorMapper;
import com.matera.x9qrcode.domain.vo.CreditorVO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CreateQRCodeCreditorMapper {

    public static CreditorVO map(CreditorDTO input) {
        if (isNull(input)) {
            return null;
        }

        return new CreditorVO(
            input.name(),
            input.phone(),
            input.email(),
            AddressMapper.map(input.address()),
            UltimateCreditorMapper.map(input.ultimateCreditor()),
            input.MCC()
        );
    }
}
