/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.mapper;

import com.matera.x9qrcode.app.dto.AddressDTO;
import com.matera.x9qrcode.domain.vo.AddressVO;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AddressMapper {

    public static AddressVO map(AddressDTO input) {
        if (isNull(input)) {
            return null;
        }
        return new AddressVO(
            input.line1(),
            input.line2(),
            input.city(),
            input.state(),
            input.postalCode(),
            input.country()
        );
    }

    public static AddressDTO map(AddressVO input) {
        if (isNull(input)) {
            return null;
        }
        return new AddressDTO(
            input.line1(),
            input.line2(),
            input.city(),
            input.state(),
            input.postalCode(),
            input.country()
        );
    }
}
