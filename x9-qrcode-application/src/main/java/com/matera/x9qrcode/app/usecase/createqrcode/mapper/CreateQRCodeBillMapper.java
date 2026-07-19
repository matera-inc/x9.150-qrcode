/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.createqrcode.mapper;

import com.matera.x9qrcode.app.dto.AdjustmentDTO;
import com.matera.x9qrcode.app.dto.AdjustmentParametersDTO;
import com.matera.x9qrcode.app.dto.AmountDueDTO;
import com.matera.x9qrcode.app.dto.BillDTO;
import com.matera.x9qrcode.app.dto.DiscountDTO;
import com.matera.x9qrcode.app.dto.LateFeesDTO;
import com.matera.x9qrcode.app.dto.TipDTO;
import com.matera.x9qrcode.domain.vo.AdjustmentParametersVO;
import com.matera.x9qrcode.domain.vo.AdjustmentVO;
import com.matera.x9qrcode.domain.vo.AmountDueVO;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.CurrencyAmountVO;
import com.matera.x9qrcode.domain.vo.DescriptionVO;
import com.matera.x9qrcode.domain.vo.DiscountVO;
import com.matera.x9qrcode.domain.vo.LateFeesVO;
import com.matera.x9qrcode.domain.vo.TipRangeVO;
import com.matera.x9qrcode.domain.vo.TipVO;
import com.matera.x9qrcode.domain.vo.enumerated.FormulaEnum;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CreateQRCodeBillMapper {

    public static BillVO map(BillDTO input) {
        return new BillVO(
            new DescriptionVO(input.description()),
            CreateQRCodeOrderMapper.map(input.order()),
            CreateQRCodeInvoiceMapper.map(input.invoice()),
            buildTip(input.tip()),
            buildAmountDue(input.amountDue()),
            PaymentTimingEnum.fromValue(input.paymentTiming().value())
        );
    }

    private static TipVO buildTip(TipDTO input) {
        if (isNull(input)) {
            return TipVO.noTip();
        }

        if (isNull(input.range())) {
            return TipVO.of(input.allowed(), input.presets());
        }

        return new TipVO(input.allowed(), new TipRangeVO(input.range().minimum(), input.range().maximum()), input.presets());
    }

    private static AmountDueVO buildAmountDue(AmountDueDTO input) {
        if (isNull(input)) {
            return null;
        }

        return new AmountDueVO(new CurrencyAmountVO(input.amount(), input.currency()),
            buildAdjustment(input.adjustments()));
    }

    private static AdjustmentVO buildAdjustment(AdjustmentDTO input) {
        if (isNull(input)) {
            return null;
        }

        return new AdjustmentVO(FormulaEnum.fromValue(input.formula().value()), buildAdjustmentParameters(input.adjustmentParameters()));
    }

    private static AdjustmentParametersVO buildAdjustmentParameters(AdjustmentParametersDTO input) {
        List<DiscountVO> discounts = isNull(input.discounts()) || input.discounts().isEmpty() ? null :
            input.discounts().stream().map(CreateQRCodeBillMapper::buildDiscount).toList();

        return new AdjustmentParametersVO(discounts, buildLateFees(input.lateFees()));
    }

    private static DiscountVO buildDiscount(DiscountDTO input) {
        return new DiscountVO(input.daysBefore(), input.discount(), input.explanation());
    }

    private static LateFeesVO buildLateFees(LateFeesDTO input) {
        return new LateFeesVO(input.fixed(), input.perDay(), input.explanation());
    }

}
