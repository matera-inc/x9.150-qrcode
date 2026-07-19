/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.retrievepayload.mapper;

import com.matera.x9qrcode.app.dto.CreditorDTO;
import com.matera.x9qrcode.app.mapper.AddressMapper;
import com.matera.x9qrcode.app.mapper.UltimateCreditorMapper;
import com.matera.x9qrcode.domain.vo.CreditorVO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RetrieveQRCodePayloadCreditorMapper {

    public static CreditorDTO map(CreditorVO output) {
        return new CreditorDTO(
            output.name().value(),
            output.phone().value(),
            output.email().value(),
            AddressMapper.map(output.address()),
            UltimateCreditorMapper.map(output.ultimateCreditor()),
            output.merchantCategoryCode().value()
        );
    }

}
