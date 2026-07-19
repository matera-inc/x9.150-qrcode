/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.retrieveqrcode.mapper;

import com.matera.x9qrcode.app.dto.OrderDTO;
import com.matera.x9qrcode.domain.vo.OrderVO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RetrieveQRCodeOrderMapper {

    public static OrderDTO map(OrderVO output) {
        if (isNull(output)) {
            return null;
        }

        return new OrderDTO(output.number(), output.date());
    }
    
}
