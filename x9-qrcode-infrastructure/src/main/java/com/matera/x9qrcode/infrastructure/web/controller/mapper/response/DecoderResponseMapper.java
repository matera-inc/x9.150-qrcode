/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.response;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import com.matera.x9qrcode.infrastructure.generated.dto.PayloadBillBaseTipDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BillPayloadResponseDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentPayloadResponseDTO;

import com.fasterxml.jackson.databind.JsonNode;

public class DecoderResponseMapper {

    /**
     * The decoder's answer for another bank's payload, {@code dto}, read from {@code payload}, the
     * same payload as a tree (to tell which members the other bank actually sent).
     */
    public static PaymentPayloadResponseDTO map(PaymentPayloadResponseDTO dto, JsonNode payload) {
        if (isNull(dto)) {
            return null;
        }

        if (nonNull(dto.getBill())) {
            presentPaymentTiming(dto.getBill(), isNull(payload) ? null : payload.path("bill"));
        }

        return map(dto);
    }

    /**
     * bill.paymentTiming is presented as "immediate" when the other bank sent none (or null) and the
     * bill has no invoice.dueDate; with a dueDate and no paymentTiming it stays absent (owner
     * decision, 2026-11-16).
     *
     * <p>Why a value is added by a decoder that otherwise presents what the other bank wrote: ANSI
     * X9.150-2026 §13.1 makes bill.paymentTiming mandatory ("SHALL be present"). "immediate" means due
     * when the QR Code is scanned; "deferred" means due at a future date, and then
     * bill.invoice.dueDate SHALL be present. Our openapi.yaml promises that paymentTiming is always
     * present in a payload response, and that it defaults to "immediate" on create. So when another
     * bank leaves it out, the decoder presents "immediate": the only safe reading, since a deferred
     * bill needs a due date the payer could defer to. It does so only when there is no dueDate: with
     * one, the bill may be meant as deferred, the intent is ambiguous, and we do not guess.
     *
     * <p>This does not break "strict to generate, tolerant to read" (INTERPRETATION I-16): that rule
     * is about not refusing a payload for vocabulary we do not know. This fills a field the standard
     * requires, with the default our contract documents. The generated DTO defaults the member to
     * IMMEDIATE whenever it is absent, dueDate or not, so the dueDate case is undone here. The Go
     * build applies the same rule (internal/decoder, withPaymentTiming).
     */
    private static void presentPaymentTiming(BillPayloadResponseDTO bill, JsonNode sentBill) {
        JsonNode sentTiming = isNull(sentBill) ? null : sentBill.get("paymentTiming");
        if (nonNull(sentTiming) && !sentTiming.isNull()) {
            return;                                         // sent: presented as it was read
        }
        boolean hasDueDate = nonNull(bill.getInvoice()) && nonNull(bill.getInvoice().getDueDate());
        bill.setPaymentTiming(hasDueDate ? null : BillPayloadResponseDTO.PaymentTimingEnum.IMMEDIATE);
    }

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

    private static void filterTipEmptyArrays(PayloadBillBaseTipDTO tip) {
        if (isNull(tip)) {
            return;
        }

        if (nonNull(tip.getPresets()) && tip.getPresets().isEmpty()) {
            tip.setPresets(null);
        }
    }
}
