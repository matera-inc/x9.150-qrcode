/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.response;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import com.matera.x9qrcode.infrastructure.generated.dto.BillBaseTipDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BillPayloadResponseDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentPayloadResponseDTO;

public class DecoderResponseMapper {

    public static PaymentPayloadResponseDTO map(PaymentPayloadResponseDTO dto) {
        if (isNull(dto)) {
            return null;
        }

        if (nonNull(dto.getAdditionalInformation()) && dto.getAdditionalInformation().isEmpty()) {
            dto.setAdditionalInformation(null);
        }

        if (nonNull(dto.getPaymentMethods()) && dto.getPaymentMethods().isEmpty()) {
            dto.setPaymentMethods(null);
        }

        if (nonNull(dto.getBill())) {
            filterBillEmptyArrays(dto.getBill());
        }

        return dto;
    }

    private static void filterBillEmptyArrays(BillPayloadResponseDTO bill) {
        if (isNull(bill)) {
            return;
        }

        if (nonNull(bill.getTip())) {
            filterTipEmptyArrays(bill.getTip());
        }

        if (nonNull(bill.getAmountDue())
                && nonNull(bill.getAmountDue().getAdjustment())
                && bill.getAmountDue().getAdjustment().isEmpty()) {
            bill.getAmountDue().setAdjustment(null);
        }
    }

    private static void filterTipEmptyArrays(BillBaseTipDTO tip) {
        if (isNull(tip)) {
            return;
        }

        if (nonNull(tip.getPresets()) && tip.getPresets().isEmpty()) {
            tip.setPresets(null);
        }
    }
}
