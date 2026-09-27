/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.request;

import com.matera.x9qrcode.app.dto.AddressUpdateDTO;
import com.matera.x9qrcode.app.dto.AdjustmentParametersUpdateDTO;
import com.matera.x9qrcode.app.dto.AdjustmentUpdateDTO;
import com.matera.x9qrcode.app.dto.AmountDueUpdateDTO;
import com.matera.x9qrcode.app.dto.AmountRangeDTO;
import com.matera.x9qrcode.app.dto.BankPaymentAddressDTO;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;
import com.matera.x9qrcode.app.dto.BillUpdateDTO;
import com.matera.x9qrcode.app.dto.CryptoWalletPaymentAddressDTO;
import com.matera.x9qrcode.app.dto.CurrencyEditableUpdateDTO;
import com.matera.x9qrcode.app.dto.DiscountDTO;
import com.matera.x9qrcode.app.dto.InvoiceUpdateDTO;
import com.matera.x9qrcode.app.dto.InvoiceeUpdateDTO;
import com.matera.x9qrcode.app.dto.LateFeesDTO;
import com.matera.x9qrcode.app.dto.NetworksUpdateDTO;
import com.matera.x9qrcode.app.dto.OrderUpdateDTO;
import com.matera.x9qrcode.app.dto.PaymentMethodUpdateDTO;
import com.matera.x9qrcode.app.dto.TipUpdateDTO;
import com.matera.x9qrcode.app.dto.enumerated.FormulaEnumDTO;
import com.matera.x9qrcode.app.dto.enumerated.PaymentTimingEnumDTO;
import com.matera.x9qrcode.app.usecase.PartialInput;
import com.matera.x9qrcode.app.usecase.patchqrcode.PatchQRCodeInput;
import com.matera.x9qrcode.infrastructure.generated.dto.ACHDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.AdjustmentParametersDiscountsInnerDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.AdjustmentParametersLateFeesDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.FedNowDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.IntegerRangeDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.KeyValuePairDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchAddressDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchAdjustmentDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchAdjustmentParametersDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchAmountDueDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchBillUpdateDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchEditableDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchInvoiceDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchInvoiceeDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchNetworksSimpleDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchOrderDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchPaymentMethodDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchPaymentRequestReplacementDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PatchTipDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.RTPDTO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.openapitools.jackson.nullable.JsonNullable;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.BooleanUtils.isNotTrue;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PatchQRCodeRequestMapper {

    public static PatchQRCodeInput map(String id, PatchPaymentRequestReplacementDTO paymentRequestReplacementDTO) {
        JsonNullable<String> locationId = paymentRequestReplacementDTO.getLocationId();
        JsonNullable<OffsetDateTime> validUntil = paymentRequestReplacementDTO.getValidUntil();
        JsonNullable<PatchBillUpdateDTO> bill = paymentRequestReplacementDTO.getBill();
        JsonNullable<String> unstructured = paymentRequestReplacementDTO.getUnstructured();
        JsonNullable<List<KeyValuePairDTO>> additionalInformation = paymentRequestReplacementDTO.getAdditionalInformation();

        return new PatchQRCodeInput(
            id,
            locationId.isPresent() ? PartialInput.of(locationId.get()) : PartialInput.absent(),
            validUntil.isPresent() ? PartialInput.of(validUntil.get()) : PartialInput.absent(),
            bill.isPresent() ? buildBillUpdateDTO(bill.get()) : PartialInput.absent(),
            unstructured.isPresent() ? PartialInput.of(unstructured.get()) : PartialInput.absent(),
            additionalInformation.isPresent() ? buildAdditionalInformationMap(additionalInformation.get()) : PartialInput.absent(),
            buildPaymentMethodDTOList(paymentRequestReplacementDTO.getPaymentMethods())
        );
    }

    private static PartialInput<BillUpdateDTO> buildBillUpdateDTO(PatchBillUpdateDTO patchBillUpdateDTO) {
        JsonNullable<PatchOrderDTO> order = patchBillUpdateDTO.getOrder();
        JsonNullable<PatchInvoiceDTO> invoice = patchBillUpdateDTO.getInvoice();
        JsonNullable<PatchTipDTO> tip = patchBillUpdateDTO.getTip();
        PatchBillUpdateDTO.PaymentTimingEnum paymentTiming = patchBillUpdateDTO.getPaymentTiming();
        
        BillUpdateDTO billUpdateDTO = new BillUpdateDTO(
            buildAmountDueUpdateDTO(patchBillUpdateDTO.getAmountDue()),
            patchBillUpdateDTO.getDescription(),
            order.isPresent() ? buildOrderUpdateDTO(order.get()) : PartialInput.absent(),
            invoice.isPresent() ? buildInvoiceUpdateDTO(invoice.get()) : PartialInput.absent(),
            tip.isPresent() ? buildTipUpdateDTO(tip.get()) : PartialInput.absent(),
            PaymentTimingEnumDTO.fromValue(paymentTiming.getValue())
        );

        return PartialInput.of(billUpdateDTO);
    }

    private static PartialInput<Map<String, String>> buildAdditionalInformationMap(List<KeyValuePairDTO> keyValuePairDTOList) {
        if (isNull(keyValuePairDTOList) || keyValuePairDTOList.isEmpty()) {
            return PartialInput.of(null);
        }

        Map<String, String> additionalInformationMap = keyValuePairDTOList.stream()
            .collect(Collectors.toMap(KeyValuePairDTO::getKey, KeyValuePairDTO::getValue, (existing, replacement) -> existing));

        return PartialInput.of(additionalInformationMap);
    }

    private static List<PaymentMethodUpdateDTO> buildPaymentMethodDTOList(List<PatchPaymentMethodDTO> patchPaymentMethodDTOList) {
        return patchPaymentMethodDTOList.stream()
            .map(patchPaymentMethodDTO -> {
                JsonNullable<PatchEditableDTO> editable = patchPaymentMethodDTO.getEditable();

                return new PaymentMethodUpdateDTO(
                    patchPaymentMethodDTO.getCurrency(),
                    patchPaymentMethodDTO.getValidUntil(),
                    patchPaymentMethodDTO.getAmount(),
                    editable.isPresent() ? buildCurrencyEditableUpdateDTO(editable.get()) : PartialInput.absent(),
                    buildNetworksUpdateDTO(patchPaymentMethodDTO.getNetworks())
                );
            }).toList();
    }

    private static AmountDueUpdateDTO buildAmountDueUpdateDTO(PatchAmountDueDTO patchAmountDueDTO) {
        JsonNullable<PatchAdjustmentDTO> adjustments = patchAmountDueDTO.getAdjustments();

        return new AmountDueUpdateDTO(
            patchAmountDueDTO.getAmount(),
            adjustments.isPresent() ? buildAdjustmentUpdateDTO(adjustments.get()) : PartialInput.absent()
        );
    }

    private static PartialInput<OrderUpdateDTO> buildOrderUpdateDTO(PatchOrderDTO patchOrderDTO) {
        if (isNull(patchOrderDTO)) {
            return PartialInput.of(null);
        }

        JsonNullable<LocalDate> date = patchOrderDTO.getDate();

        OrderUpdateDTO orderUpdateDTO = new OrderUpdateDTO(
            patchOrderDTO.getNumber(),
            date.isPresent() ? PartialInput.of(date.get()) : PartialInput.absent()
        );

        return PartialInput.of(orderUpdateDTO);
    }

    private static PartialInput<InvoiceUpdateDTO> buildInvoiceUpdateDTO(PatchInvoiceDTO patchInvoiceDTO) {
        if (isNull(patchInvoiceDTO)) {
            return PartialInput.of(null);
        }

        JsonNullable<PatchInvoiceeDTO> invoicee = patchInvoiceDTO.getInvoicee();

        InvoiceUpdateDTO invoiceUpdateDTO = new InvoiceUpdateDTO(
            patchInvoiceDTO.getNumber(),
            patchInvoiceDTO.getDate(),
            patchInvoiceDTO.getDueDate(),
            invoicee.isPresent() ? buildInvoiceeUpdateDTO(invoicee.get()) : PartialInput.absent()
        );

        return PartialInput.of(invoiceUpdateDTO);
    }

    private static PartialInput<TipUpdateDTO> buildTipUpdateDTO(PatchTipDTO patchTipDTO) {
        if (isNull(patchTipDTO)) {
            return PartialInput.of(null);
        }

        Boolean allowed = patchTipDTO.getAllowed();

        if (isNotTrue(allowed)) {
            return PartialInput.of(TipUpdateDTO.noTip());
        }

        com.matera.x9qrcode.infrastructure.generated.dto.TipRangeDTO range = patchTipDTO.getRange();

        JsonNullable<List<Integer>> presets = patchTipDTO.getPresets();

        PartialInput<List<Integer>> presetsPartialInput =
            presets.isPresent() ? PartialInput.of(presets.get()) : PartialInput.absent();

        if (isNull(range)) {
            return PartialInput.of(TipUpdateDTO.of(presetsPartialInput));
        }

        return PartialInput.of(TipUpdateDTO.of(range.getMin(), range.getMax(), presetsPartialInput));
    }

    private static PartialInput<AdjustmentUpdateDTO> buildAdjustmentUpdateDTO(PatchAdjustmentDTO patchAdjustmentDTO) {
        if (isNull(patchAdjustmentDTO)) {
            return PartialInput.of(null);
        }

        AdjustmentUpdateDTO adjustmentUpdateDTO = new AdjustmentUpdateDTO(
            FormulaEnumDTO.fromValue(patchAdjustmentDTO.getFormula().getValue()),
            buildAdjustmentParametersUpdateDTO(patchAdjustmentDTO.getParameters())
        );

        return PartialInput.of(adjustmentUpdateDTO);
    }

    private static AdjustmentParametersUpdateDTO buildAdjustmentParametersUpdateDTO(PatchAdjustmentParametersDTO patchAdjustmentParametersDTO) {
        JsonNullable<List<AdjustmentParametersDiscountsInnerDTO>> discounts = patchAdjustmentParametersDTO.getDiscounts();

        AdjustmentParametersLateFeesDTO lateFees = patchAdjustmentParametersDTO.getLateFees();

        LateFeesDTO lateFeesDTO = new LateFeesDTO(lateFees.getFixed(), lateFees.getPerDay(), lateFees.getExplanation());

        if (!discounts.isPresent()) {
            return new AdjustmentParametersUpdateDTO(PartialInput.absent(), lateFeesDTO);
        }

        if (discounts.isPresent() && isNull(discounts.get()) || discounts.get().isEmpty()) {
            return new AdjustmentParametersUpdateDTO(PartialInput.of(null), lateFeesDTO);
        }

        List<DiscountDTO> discountDTOList = discounts.get().stream()
            .map(adjustmentParametersDiscountsInnerDTO ->
                new DiscountDTO(
                    adjustmentParametersDiscountsInnerDTO.getDaysBefore(),
                    adjustmentParametersDiscountsInnerDTO.getDiscount(),
                    adjustmentParametersDiscountsInnerDTO.getExplanation()))
            .toList();

        return new AdjustmentParametersUpdateDTO(PartialInput.of(discountDTOList), lateFeesDTO);
    }

    private static PartialInput<CurrencyEditableUpdateDTO> buildCurrencyEditableUpdateDTO(PatchEditableDTO patchEditableDTO) {

        JsonNullable<IntegerRangeDTO> range = patchEditableDTO.getRange();

        if (!range.isPresent()) {
            return PartialInput.of(new CurrencyEditableUpdateDTO(PartialInput.absent()));
        }

        IntegerRangeDTO integerRangeDTO = range.get();

        if (range.isPresent() && isNull(integerRangeDTO)) {
            return PartialInput.of(new CurrencyEditableUpdateDTO(PartialInput.of(null)));
        }

        AmountRangeDTO amountRangeDTO = new AmountRangeDTO(
            integerRangeDTO.getMin(),
            integerRangeDTO.getMax()
        );

        return PartialInput.of(new CurrencyEditableUpdateDTO(PartialInput.of(amountRangeDTO)));
    }

    /**
     * A patch accepts the rails under any spelling, exactly as a create does.
     *
     * <p>The generated properties only capture the canonical lower-case keys, so a rail sent as
     * {@code FedNow} lands in {@code additionalProperties}. It is lifted out first — otherwise the
     * rejection below would refuse the service's own rail for being written differently.
     */
    private static NetworksUpdateDTO buildNetworksUpdateDTO(PatchNetworksSimpleDTO patchNetworksSimpleDTO) {
        Map<String, Object> others = StandardRailKeys.mutableCopy(patchNetworksSimpleDTO.getAdditionalProperties());

        PartialInput<BankPaymentAddressDTO> fedNow =
            rail(patchNetworksSimpleDTO.getFednow(), NetworkEnum.FEDNOW.value(), others);
        PartialInput<BankPaymentAddressDTO> rtp =
            rail(patchNetworksSimpleDTO.getRtp(), NetworkEnum.RTP.value(), others);
        PartialInput<BankPaymentAddressDTO> ach =
            rail(patchNetworksSimpleDTO.getAch(), NetworkEnum.ACH.value(), others);

        StandardRailKeys.rejectUnsupported(others);

        return NetworksUpdateDTO.builder()
            .fedNow(fedNow)
            .rtp(rtp)
            .ach(ach)
            .additionalProperties(PartialInput.of(others))
            .build();
    }

    /**
     * One rail's patch value: the canonically-spelled property when the caller used it, otherwise
     * whatever variant spelling {@code others} holds — which is removed as it is promoted, so it is
     * not also carried as an uninterpreted network. Absent from both means "no change".
     */
    private static PartialInput<BankPaymentAddressDTO> rail(JsonNullable<?> typed,
                                                            String canonicalKey,
                                                            Map<String, Object> others) {
        if (typed.isPresent()) {
            // Still drop any variant spelling, so one rail cannot be patched twice in one request.
            StandardRailKeys.takeCaseInsensitively(others, canonicalKey);

            return buildBankPaymentAddressDTO(typed.get());
        }

        return StandardRailKeys.takeCaseInsensitively(others, canonicalKey)
            .map(fields -> PartialInput.of(StandardRailKeys.toBankAddress(fields)))
            .orElseGet(PartialInput::absent);
    }

    private static PartialInput<BankPaymentAddressDTO> buildBankPaymentAddressDTO(Object bankPaymentAddress) {
        if (isNull(bankPaymentAddress)) {
            return PartialInput.of(null);
        }

        if (bankPaymentAddress instanceof FedNowDTO fedNowDTO) {
            return PartialInput.of(new BankPaymentAddressDTO(fedNowDTO.getRoutingNumber(), fedNowDTO.getAccountNumber()));
        }

        if (bankPaymentAddress instanceof RTPDTO rtpDTO) {
            return PartialInput.of(new BankPaymentAddressDTO(rtpDTO.getRoutingNumber(), rtpDTO.getAccountNumber()));
        }

        if (bankPaymentAddress instanceof ACHDTO achDTO) {
            return PartialInput.of(new BankPaymentAddressDTO(achDTO.getRoutingNumber(), achDTO.getAccountNumber()));
        }

        throw new IllegalArgumentException(
            String.format("Unsupported bank payment address type: %s", bankPaymentAddress.getClass().getName()));
    }

    private static PartialInput<CryptoWalletPaymentAddressDTO> buildCryptoWalletPaymentAddressDTO(Object cryptoWalletPaymentAddress) {
        if (isNull(cryptoWalletPaymentAddress)) {
            return PartialInput.of(null);
        }

        throw new IllegalArgumentException(
            String.format("Unsupported crypto wallet payment address type: %s", cryptoWalletPaymentAddress.getClass().getName()));
    }

    private static PartialInput<InvoiceeUpdateDTO> buildInvoiceeUpdateDTO(PatchInvoiceeDTO patchInvoiceeDTO) {
        JsonNullable<PatchAddressDTO> address = patchInvoiceeDTO.getAddress();
        JsonNullable<String> email = patchInvoiceeDTO.getEmail();
        JsonNullable<String> phone = patchInvoiceeDTO.getPhone();

        InvoiceeUpdateDTO invoiceeUpdateDTO = new InvoiceeUpdateDTO(
            patchInvoiceeDTO.getName(),
            phone.isPresent() ? PartialInput.of(phone.get()) : PartialInput.absent(),
            email.isPresent() ? PartialInput.of(email.get()) : PartialInput.absent(),
            address.isPresent() ? buildPatchAddressDTO(address.get()) : PartialInput.absent()
        );

        return PartialInput.of(invoiceeUpdateDTO);
    }

    private static PartialInput<AddressUpdateDTO> buildPatchAddressDTO(PatchAddressDTO patchAddressDTO) {
        JsonNullable<String> line2 = patchAddressDTO.getLine2();
        JsonNullable<String> postalCode = patchAddressDTO.getPostalCode();
        JsonNullable<String> state = patchAddressDTO.getState();

        AddressUpdateDTO addressUpdateDTO = new AddressUpdateDTO(
            patchAddressDTO.getLine1(),
            line2.isPresent() ? PartialInput.of(line2.get()) : PartialInput.absent(),
            patchAddressDTO.getCity(),
            state.isPresent() ? PartialInput.of(state.get()) : PartialInput.absent(),
            postalCode.isPresent() ? PartialInput.of(postalCode.get()) : PartialInput.absent(),
            patchAddressDTO.getCountry()
        );

        return PartialInput.of(addressUpdateDTO);
    }

}
