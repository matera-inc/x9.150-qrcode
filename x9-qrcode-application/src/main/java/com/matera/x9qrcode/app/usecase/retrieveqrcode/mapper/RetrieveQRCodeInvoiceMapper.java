/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.retrieveqrcode.mapper;

import com.matera.x9qrcode.app.dto.InvoiceDTO;
import com.matera.x9qrcode.app.dto.InvoiceeDTO;
import com.matera.x9qrcode.app.mapper.AddressMapper;
import com.matera.x9qrcode.domain.vo.InvoiceVO;
import com.matera.x9qrcode.domain.vo.InvoiceeVO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RetrieveQRCodeInvoiceMapper {

    public static InvoiceDTO map(InvoiceVO output) {
        if (isNull(output)) {
            return null;
        }

        return new InvoiceDTO(
            output.number().value(),
            output.date(),
            output.dueDate(),
            reverseBuildInvoicee(output.invoicee())
        );
    }

    private static InvoiceeDTO reverseBuildInvoicee(InvoiceeVO output) {
        if (isNull(output)) {
            return null;
        }

        return new InvoiceeDTO(
            output.name().value(),
            output.phone().value(),
            output.email().value(),
            AddressMapper.map(output.address())
        );
    }
}
