/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.retrievepayload.mapper;

import com.matera.x9qrcode.app.dto.AdjustmentDTO;
import com.matera.x9qrcode.app.dto.AdjustmentParametersDTO;
import com.matera.x9qrcode.app.dto.AmountDueDTO;
import com.matera.x9qrcode.app.dto.BillDTO;
import com.matera.x9qrcode.app.dto.DiscountDTO;
import com.matera.x9qrcode.app.dto.LateFeesDTO;
import com.matera.x9qrcode.app.dto.TipDTO;
import com.matera.x9qrcode.app.dto.TipRangeDTO;
import com.matera.x9qrcode.app.dto.enumerated.FormulaEnumDTO;
import com.matera.x9qrcode.app.dto.enumerated.PaymentTimingEnumDTO;
import com.matera.x9qrcode.domain.dto.FormulaResultDTO;
import com.matera.x9qrcode.domain.vo.AdjustmentParametersVO;
import com.matera.x9qrcode.domain.vo.AdjustmentVO;
import com.matera.x9qrcode.domain.vo.AmountDueVO;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.DiscountVO;
import com.matera.x9qrcode.domain.vo.LateFeesVO;
import com.matera.x9qrcode.domain.vo.TipRangeVO;
import com.matera.x9qrcode.domain.vo.TipVO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RetrieveQRCodePayloadBillMapper {
    public static BillDTO map(BillVO output, FormulaResultDTO formulaResult) {
        return new BillDTO(
            output.description().value(),
            RetrieveQRCodePayloadOrderMapper.map(output.order()),
            RetrieveQRCodePayloadInvoiceMapper.map(output.invoice()),
            buildAmountDue(output.amountDue(), formulaResult),
            buildTip(output.tip()),
            PaymentTimingEnumDTO.fromValue(output.paymentTiming().value())
        );
    }

    private static TipDTO buildTip(TipVO output) {
        if (isNull(output)) {
            return null;
        }

        return new TipDTO(output.allowed(), buildTipRange(output.range()), output.presets()
        );
    }

    private static TipRangeDTO buildTipRange(TipRangeVO output) {
        if (isNull(output)) {
            return null;
        }

        return new TipRangeDTO(
            output.minimum(),
            output.maximum()
        );
    }

    private static AmountDueDTO buildAmountDue(AmountDueVO output, FormulaResultDTO formulaResult) {
        if (isNull(output)) {
            return null;
        }

        return new AmountDueDTO(
            output.currencyAmount().amount(),
            output.currencyAmount().currency(),
            buildAdjustment(output.adjustment(), formulaResult)
        );
    }

    private static AdjustmentDTO buildAdjustment(AdjustmentVO output, FormulaResultDTO formulaResult) {
        if (isNull(output) || isNull(formulaResult)) {
            return null;
        }

        return new AdjustmentDTO(
            FormulaEnumDTO.fromValue(output.formula().value()),
            buildAdjustmentParameters(output.parameters())
        );
    }

    private static AdjustmentParametersDTO buildAdjustmentParameters(AdjustmentParametersVO output) {
        List<DiscountDTO> discounts = isNull(output.discounts()) || output.discounts().isEmpty() ? null :
            output.discounts().stream().map(RetrieveQRCodePayloadBillMapper::buildDiscount).toList();

        return new AdjustmentParametersDTO(discounts, buildLateFees(output.lateFees()));
    }

    private static DiscountDTO buildDiscount(DiscountVO output) {
        return new DiscountDTO(
            output.daysBefore(),
            output.discount(),
            output.explanation()
        );
    }

    private static LateFeesDTO buildLateFees(LateFeesVO output) {
        return new LateFeesDTO(
            output.fixed(),
            output.perDay(),
            output.explanation()
        );
    }
}
