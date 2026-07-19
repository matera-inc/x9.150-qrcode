/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.patchqrcode.mapper;

import com.matera.x9qrcode.app.dto.AddressUpdateDTO;
import com.matera.x9qrcode.app.dto.AdjustmentParametersUpdateDTO;
import com.matera.x9qrcode.app.dto.AdjustmentUpdateDTO;
import com.matera.x9qrcode.app.dto.AmountDueUpdateDTO;
import com.matera.x9qrcode.app.dto.BillUpdateDTO;
import com.matera.x9qrcode.app.dto.DiscountDTO;
import com.matera.x9qrcode.app.dto.InvoiceUpdateDTO;
import com.matera.x9qrcode.app.dto.InvoiceeUpdateDTO;
import com.matera.x9qrcode.app.dto.LateFeesDTO;
import com.matera.x9qrcode.app.dto.OrderUpdateDTO;
import com.matera.x9qrcode.app.dto.TipRangeDTO;
import com.matera.x9qrcode.app.dto.TipUpdateDTO;
import com.matera.x9qrcode.app.dto.enumerated.PaymentTimingEnumDTO;
import com.matera.x9qrcode.app.usecase.PartialInput;
import com.matera.x9qrcode.domain.vo.AddressVO;
import com.matera.x9qrcode.domain.vo.AdjustmentParametersVO;
import com.matera.x9qrcode.domain.vo.AdjustmentVO;
import com.matera.x9qrcode.domain.vo.AmountDueVO;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.CurrencyAmountVO;
import com.matera.x9qrcode.domain.vo.DescriptionVO;
import com.matera.x9qrcode.domain.vo.DiscountVO;
import com.matera.x9qrcode.domain.vo.EmailVO;
import com.matera.x9qrcode.domain.vo.InvoiceVO;
import com.matera.x9qrcode.domain.vo.InvoiceeVO;
import com.matera.x9qrcode.domain.vo.LateFeesVO;
import com.matera.x9qrcode.domain.vo.NameVO;
import com.matera.x9qrcode.domain.vo.NumberIdentifierVO;
import com.matera.x9qrcode.domain.vo.OrderVO;
import com.matera.x9qrcode.domain.vo.PhoneVO;
import com.matera.x9qrcode.domain.vo.TipVO;
import com.matera.x9qrcode.domain.vo.enumerated.FormulaEnum;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PatchQRCodeBillMapper {

    public static BillVO map(BillVO bill, BillUpdateDTO billUpdateDTO) {
        AmountDueUpdateDTO amountDueUpdateDTO = billUpdateDTO.amountDueUpdateDTO();
        PartialInput<OrderUpdateDTO> orderUpdateDTO = billUpdateDTO.orderUpdateDTO();
        PartialInput<InvoiceUpdateDTO> invoiceUpdateDTO = billUpdateDTO.invoiceUpdateDTO();
        PartialInput<TipUpdateDTO> tipUpdateDTO = billUpdateDTO.tipUpdateDTO();
        PaymentTimingEnumDTO paymentTimingUpdateDTO = billUpdateDTO.paymentTimingUpdateDTO();

        return new BillVO(
            new DescriptionVO(billUpdateDTO.description()),
            buildOrder(bill.order(), orderUpdateDTO.isPresent() ? orderUpdateDTO.get() : null),
            buildInvoice(bill.invoice(), invoiceUpdateDTO.isPresent() ? invoiceUpdateDTO.get() : null),
            buildTip(bill.tip(), tipUpdateDTO.isPresent() ? tipUpdateDTO.get() : null),
            buildAmountDue(bill.amountDue(), amountDueUpdateDTO),
            isNull(paymentTimingUpdateDTO) ? bill.paymentTiming() : PaymentTimingEnum.fromValue(paymentTimingUpdateDTO.value())
        );
    }

    private static OrderVO buildOrder(OrderVO order, OrderUpdateDTO orderUpdateDTO) {
        if (isNull(orderUpdateDTO) || isNull(order)) {
            return null;
        }

        PartialInput<LocalDate> date = orderUpdateDTO.date();

        return new OrderVO(
            new NumberIdentifierVO(orderUpdateDTO.number()),
            date.isPresent() ? date.get() : order.date()
        );
    }

    private static InvoiceVO buildInvoice(InvoiceVO invoice, InvoiceUpdateDTO invoiceUpdateDTO) {
        if (isNull(invoiceUpdateDTO) || isNull(invoice)) {
            return null;
        }

        PartialInput<InvoiceeUpdateDTO> invoiceeUpdateDTO = invoiceUpdateDTO.invoiceeUpdateDTO();

        return new InvoiceVO(
            invoiceUpdateDTO.number(),
            invoiceUpdateDTO.date(),
            invoiceUpdateDTO.dueDate(),
            invoiceeUpdateDTO.isPresent() ? buildInvoicee(invoice.invoicee(), invoiceeUpdateDTO.get()) : invoice.invoicee()
        );
    }

    private static TipVO buildTip(TipVO tip, TipUpdateDTO tipUpdateDTO) {
        if (isNull(tipUpdateDTO) || isNull(tip) || !tipUpdateDTO.allowed()) {
            return TipVO.noTip();
        }

        List<Integer> presets = tipUpdateDTO.presets().isPresent() ? tipUpdateDTO.presets().get() : tip.presets();

        TipRangeDTO range = tipUpdateDTO.range();

        if (isNull(range)) {
            return TipVO.of(true, presets);
        }

        return TipVO.of(true, range.minimum(), range.maximum(), presets);
    }

    private static InvoiceeVO buildInvoicee(InvoiceeVO invoicee, InvoiceeUpdateDTO invoiceeUpdateDTO) {
        if (isNull(invoiceeUpdateDTO) || isNull(invoicee)) {
            return null;
        }

        PartialInput<AddressUpdateDTO> addressUpdateDTO = invoiceeUpdateDTO.addressUpdateDTO();
        PartialInput<String> phone = invoiceeUpdateDTO.phone();
        PartialInput<String> email = invoiceeUpdateDTO.email();

        return new InvoiceeVO(
            new NameVO(invoiceeUpdateDTO.name()),
            phone.isPresent() ? new PhoneVO(phone.get()) : invoicee.phone(),
            email.isPresent() ? new EmailVO(email.get()) : invoicee.email(),
            addressUpdateDTO.isPresent() ? buildAddress(invoicee.address(), addressUpdateDTO.get()) : invoicee.address()
        );
    }

    private static AmountDueVO buildAmountDue(AmountDueVO amountDue, AmountDueUpdateDTO amountDueUpdateDTO) {
        if (isNull(amountDue) || isNull(amountDueUpdateDTO)) {
            return null;
        }

        PartialInput<AdjustmentUpdateDTO> adjustmentUpdateDTO = amountDueUpdateDTO.adjustmentUpdateDTO();

        return new AmountDueVO(
            new CurrencyAmountVO(amountDueUpdateDTO.amount(), amountDue.currencyAmount().currency()),
            adjustmentUpdateDTO.isPresent() ? buildAdjustment(amountDue.adjustment(), adjustmentUpdateDTO.get()) : amountDue.adjustment()
        );
    }

    private static AddressVO buildAddress(AddressVO address, AddressUpdateDTO addressUpdateDTO) {
        if (isNull(addressUpdateDTO)) {
            return null;
        }

        PartialInput<String> line2 = addressUpdateDTO.line2();
        PartialInput<String> state = addressUpdateDTO.state();
        PartialInput<String> postalCode = addressUpdateDTO.postalCode();

        return new AddressVO(
            addressUpdateDTO.line1(),
            line2.isPresent() ? line2.get() : address.line2(),
            addressUpdateDTO.city(),
            state.isPresent() ? state.get() : address.state(),
            postalCode.isPresent() ? postalCode.get() : address.postalCode(),
            addressUpdateDTO.country()
        );
    }

    private static AdjustmentVO buildAdjustment(AdjustmentVO adjustment, AdjustmentUpdateDTO adjustmentUpdateDTO) {
        if (isNull(adjustment) || isNull(adjustmentUpdateDTO)) {
            return null;
        }

        return new AdjustmentVO(
            FormulaEnum.fromValue(adjustmentUpdateDTO.formulaDTO().value()),
            buildAdjustmentParameters(adjustment.parameters(), adjustmentUpdateDTO.adjustmentParametersUpdateDTO())
        );
    }

    private static AdjustmentParametersVO buildAdjustmentParameters(AdjustmentParametersVO parameters,
                                                                    AdjustmentParametersUpdateDTO adjustmentParametersUpdateDTO) {
        PartialInput<List<DiscountDTO>> discounts = adjustmentParametersUpdateDTO.discounts();

        LateFeesDTO lateFeesDTO = adjustmentParametersUpdateDTO.lateFees();

        LateFeesVO lateFeesVO = new LateFeesVO(lateFeesDTO.fixed(), lateFeesDTO.perDay(), lateFeesDTO.explanation());

        if (!discounts.isPresent()) {
            return new AdjustmentParametersVO(parameters.discounts(), lateFeesVO);
        }

        if (isNull(discounts.get())) {
            return new AdjustmentParametersVO(null, lateFeesVO);
        }

        List<DiscountVO> discountVOList = discounts.get().stream()
            .map(discount -> new DiscountVO(discount.daysBefore(), discount.discount(), discount.explanation()))
            .toList();

        return new AdjustmentParametersVO(discountVOList, lateFeesVO);
    }

}
