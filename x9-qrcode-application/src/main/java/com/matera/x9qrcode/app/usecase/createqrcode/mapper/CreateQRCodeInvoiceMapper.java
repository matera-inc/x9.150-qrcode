/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.createqrcode.mapper;

import com.matera.x9qrcode.app.dto.InvoiceDTO;
import com.matera.x9qrcode.app.dto.InvoiceeDTO;
import com.matera.x9qrcode.app.mapper.AddressMapper;
import com.matera.x9qrcode.domain.vo.EmailVO;
import com.matera.x9qrcode.domain.vo.InvoiceVO;
import com.matera.x9qrcode.domain.vo.InvoiceeVO;
import com.matera.x9qrcode.domain.vo.NameVO;
import com.matera.x9qrcode.domain.vo.PhoneVO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CreateQRCodeInvoiceMapper {

    public static InvoiceVO map(InvoiceDTO input) {
        if (isNull(input)) {
            return null;
        }

        return new InvoiceVO(
            input.number(),
            input.date(),
            input.dueDate(),
            buildInvoicee(input.invoiceeDTO())
        );
    }

    private static InvoiceeVO buildInvoicee(InvoiceeDTO input) {
        if (isNull(input)) {
            return null;
        }

        return new InvoiceeVO(
            new NameVO(input.name()),
            new PhoneVO(input.phone()),
            new EmailVO(input.email()),
            AddressMapper.map(input.addressDTO()) // Uses shared mapper
        );
    }
}
