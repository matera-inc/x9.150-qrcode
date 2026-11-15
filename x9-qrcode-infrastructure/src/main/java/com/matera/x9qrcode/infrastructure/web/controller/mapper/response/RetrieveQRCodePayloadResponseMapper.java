/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.response;

import com.matera.x9qrcode.domain.vo.AdditionalInformationVO;
import com.matera.x9qrcode.app.dto.AccountDTO;
import com.matera.x9qrcode.app.dto.AddressDTO;
import com.matera.x9qrcode.app.dto.AdjustmentDTO;
import com.matera.x9qrcode.app.dto.AmountDueDTO;
import com.matera.x9qrcode.app.dto.AmountRangeDTO;
import com.matera.x9qrcode.app.dto.BankPaymentAddressDTO;
import com.matera.x9qrcode.app.dto.BillDTO;
import com.matera.x9qrcode.app.dto.CreditorDTO;
import com.matera.x9qrcode.app.dto.CryptoWalletPaymentAddressDTO;
import com.matera.x9qrcode.app.dto.CurrencyEditableDTO;
import com.matera.x9qrcode.app.dto.FormulaResultDTO;
import com.matera.x9qrcode.app.dto.InvoiceDTO;
import com.matera.x9qrcode.app.dto.InvoiceeDTO;
import com.matera.x9qrcode.app.dto.NetworksDTO;
import com.matera.x9qrcode.app.dto.OrderDTO;
import com.matera.x9qrcode.app.dto.PaymentMethodDTO;
import com.matera.x9qrcode.app.dto.TipDTO;
import com.matera.x9qrcode.app.dto.TipRangeDTO;
import com.matera.x9qrcode.app.dto.UltimateCreditorDTO;
import com.matera.x9qrcode.app.usecase.retrievepayload.RetrieveQRCodePayloadOutput;
import com.matera.x9qrcode.domain.utils.UUIDUtils;
import com.matera.x9qrcode.infrastructure.generated.dto.PayloadSolanaDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PayloadACHDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.AdjustmentPayloadDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.AmountDuePayloadResponseDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PayloadBillBaseInvoiceDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PayloadBillBaseOrderDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PayloadBillBaseTipDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BillPayloadResponseDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PayloadFedNowDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PayloadKeyValuePairDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PayloadNetworksDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PayloadPaymentMethodDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PayloadPaymentMethodEditableDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentPayloadResponseDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.QRCodeStatusDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PayloadRTPDTO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RetrieveQRCodePayloadResponseMapper {

    public static PaymentPayloadResponseDTO map(RetrieveQRCodePayloadOutput output) {
        if (isNull(output)) {
            return null;
        }

        return new PaymentPayloadResponseDTO()
            .id(UUIDUtils.toShortenString(output.id()))
            .revision(output.revision())
            .createdAt(output.createdAt())
            .revisedAt(output.revisedAt())
            .sentAt(output.sentAt())
            .validUntil(output.validUntil())
            .status(QRCodeStatusDTO.fromValue(output.status()))
            .creditor(buildCreditor(output.creditor()))
            .bill(buildBill(output.billDTO(), output.formulaResult()))
            .unstructured(output.unstructured())
            .additionalInformation(buildAdditionalInformation(output.additionalInformation()))
            .paymentNotification(output.paymentNotification())
            .paymentMethods(isNull(output.paymentMethods())
                ? null
                : output.paymentMethods().stream().map(RetrieveQRCodePayloadResponseMapper::buildPaymentMethod)
            .toList());
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.PayloadCreditorDTO buildCreditor(CreditorDTO creditor) {
        if (isNull(creditor)) {
            return null;
        }

        return new com.matera.x9qrcode.infrastructure.generated.dto.PayloadCreditorDTO()
            .name(creditor.name())
            .phone(creditor.phone())
            .email(creditor.email())
            .address(buildAddress(creditor.address()))
            .ultimateCreditor(buildUltimateCreditor(creditor.ultimateCreditor()))
            .MCC(creditor.MCC());
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.PayloadCreditorUltimateCreditorDTO buildUltimateCreditor(
        UltimateCreditorDTO ultimateCreditor) {
        if (isNull(ultimateCreditor)) {
            return null;
        }

        return new com.matera.x9qrcode.infrastructure.generated.dto.PayloadCreditorUltimateCreditorDTO()
            .account(buildAccount(ultimateCreditor.account()))
            .name(ultimateCreditor.name())
            .phone(ultimateCreditor.phone())
            .email(ultimateCreditor.email())
            .address(buildAddress(ultimateCreditor.address()));
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.BillPayloadResponseDTO buildBill(BillDTO bill,
                                                                                                     FormulaResultDTO formula) {
        return new com.matera.x9qrcode.infrastructure.generated.dto.BillPayloadResponseDTO()
            .description(bill.description())
            .order(buildOrder(bill.order()))
            .invoice(buildInvoice(bill.invoice()))
            .tip(buildTip(bill.tip()))
            .amountDue(buildAmountDue(bill, formula))
            .paymentTiming(BillPayloadResponseDTO.PaymentTimingEnum.fromValue(bill.paymentTiming().value()));
    }

    private static PayloadBillBaseTipDTO buildTip(TipDTO tip) {
        if (isNull(tip)) {
            return null;
        }

        return new PayloadBillBaseTipDTO()
            .presets(tip.presets())
            .allowed(tip.allowed())
            .range(buildTipRange(tip.range()));
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.PayloadTipRangeDTO buildTipRange(TipRangeDTO tipRange) {
        if (isNull(tipRange)) {
            return null;
        }

        return new com.matera.x9qrcode.infrastructure.generated.dto.PayloadTipRangeDTO(
            tipRange.minimum(),
            tipRange.maximum()
        );
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.PayloadCreditorUltimateCreditorAccountDTO buildAccount(
        AccountDTO account) {
        return new com.matera.x9qrcode.infrastructure.generated.dto.PayloadCreditorUltimateCreditorAccountDTO()
            .id(account.id())
            .schemaName(account.schemaName());
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.PayloadAddressDTO buildAddress(AddressDTO address) {
        if (isNull(address)) {
            return null;
        }

        return new com.matera.x9qrcode.infrastructure.generated.dto.PayloadAddressDTO()
            .line1(address.line1())
            .line2(address.line2())
            .city(address.city())
            .country(address.country())
            .state(address.state())
            .postalCode(address.postalCode());
    }

    private static PayloadBillBaseInvoiceDTO buildInvoice(InvoiceDTO invoice) {
        if (isNull(invoice)) {
            return null;
        }

        return new PayloadBillBaseInvoiceDTO()
            .number(invoice.number())
            .date(invoice.date())
            .dueDate(invoice.dueDate())
            .invoicee(buildInvoicee(invoice.invoiceeDTO()));
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.PayloadInvoiceeDTO buildInvoicee(InvoiceeDTO invoicee) {
        if (isNull(invoicee)) {
            return null;
        }

        return new com.matera.x9qrcode.infrastructure.generated.dto.PayloadInvoiceeDTO()
            .name(invoicee.name())
            .phone(invoicee.phone())
            .email(invoicee.email())
            .address(buildAddress(invoicee.addressDTO()));
    }

    private static PayloadBillBaseOrderDTO buildOrder(OrderDTO order) {
        if (isNull(order)) {
            return null;
        }

        return new PayloadBillBaseOrderDTO()
            .number(order.number())
            .date(order.date());
    }

    private static List<PayloadKeyValuePairDTO> buildAdditionalInformation(List<AdditionalInformationVO> additionalInformation) {
        if (isNull(additionalInformation) || additionalInformation.isEmpty()) {
            return null;
        }

        return additionalInformation.stream()
            .map(entry -> new PayloadKeyValuePairDTO().key(entry.key()).value(entry.value()))
            .toList();
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.AmountDuePayloadResponseDTO buildAmountDue(BillDTO bill,
                                                                                                               FormulaResultDTO formula) {
        AmountDueDTO amountDue = bill.amountDue();

        if (isNull(amountDue)) {
            return null;
        }

        return new AmountDuePayloadResponseDTO()
            .amount(amountDue.amount())
            .currency(amountDue.currency())
            .adjustment(buildAdjustment(bill, formula));
    }

    private static List<com.matera.x9qrcode.infrastructure.generated.dto.AdjustmentPayloadDTO> buildAdjustment(BillDTO bill,
                                                                                                               FormulaResultDTO formula) {
        AdjustmentDTO adjustment = bill.amountDue().adjustments();

        if (isNull(adjustment) || isNull(formula)) {
            return null;
        }

        AdjustmentPayloadDTO adjustmentPayload =
            new AdjustmentPayloadDTO(formula.explanation(), formula.adjustmentAmount(), formula.validUntil());

        return List.of(adjustmentPayload);
    }

    private static PayloadPaymentMethodDTO buildPaymentMethod(PaymentMethodDTO paymentMethod) {
        return new PayloadPaymentMethodDTO()
            .currency(paymentMethod.currency())
            .validUntil(paymentMethod.validUntil())
            .amount(paymentMethod.amount())
            .editable(buildCurrencyEditable(paymentMethod.editable()))
            .networks(buildNetworks(paymentMethod.networks()));
    }

    private static PayloadPaymentMethodEditableDTO buildCurrencyEditable(CurrencyEditableDTO editable) {
        if (isNull(editable)) {
            return null;
        }

        return new PayloadPaymentMethodEditableDTO()
            .range(buildCurrencyEditableRange(editable.amountRangeDTO()));
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.PayloadIntegerRangeDTO buildCurrencyEditableRange(AmountRangeDTO range) {
        if (isNull(range)) {
            return null;
        }

        return new com.matera.x9qrcode.infrastructure.generated.dto.PayloadIntegerRangeDTO()
            .min(range.min())
            .max(range.max());
    }

    private static PayloadNetworksDTO buildNetworks(NetworksDTO networks) {
        PayloadNetworksDTO networksDTO =
            new PayloadNetworksDTO()
                .fednow(buildFedNow(networks.getFedNow()))
                .ach(buildACH(networks.getAch()))
                .rtp(buildRTP(networks.getRtp()))
                .solana(buildSolana(networks.getSolana()));

        if (isNull(networks.getAdditionalProperties())) {
            return networksDTO;
        }

        for (Map.Entry<String, Object> entry : networks.getAdditionalProperties().entrySet()) {
            networksDTO.putAdditionalProperty(entry.getKey(), entry.getValue());
        }

        return networksDTO;
    }

    private static PayloadSolanaDTO buildSolana(com.matera.x9qrcode.app.dto.SolanaPaymentAddressDTO solana) {
        if (isNull(solana)) {
            return null;
        }

        return new PayloadSolanaDTO()
            .recipient(solana.recipient())
            .memo(solana.memo());
    }

    private static PayloadRTPDTO buildRTP(BankPaymentAddressDTO bankPaymentAddress) {
        if (isNull(bankPaymentAddress)) {
            return null;
        }

        return new PayloadRTPDTO()
            .accountNumber(bankPaymentAddress.accountNumber())
            .routingNumber(bankPaymentAddress.routingNumber())
            .protectionType(protectionTypeOf(bankPaymentAddress.protectionType()));
    }

    private static PayloadACHDTO buildACH(BankPaymentAddressDTO bankPaymentAddress) {
        if (isNull(bankPaymentAddress)) {
            return null;
        }

        return new PayloadACHDTO()
            .accountNumber(bankPaymentAddress.accountNumber())
            .routingNumber(bankPaymentAddress.routingNumber())
            .protectionType(protectionTypeOf(bankPaymentAddress.protectionType()));
    }

    private static PayloadFedNowDTO buildFedNow(BankPaymentAddressDTO bankPaymentAddress) {
        if (isNull(bankPaymentAddress)) {
            return null;
        }

        return new PayloadFedNowDTO()
            .accountNumber(bankPaymentAddress.accountNumber())
            .routingNumber(bankPaymentAddress.routingNumber())
            .protectionType(protectionTypeOf(bankPaymentAddress.protectionType()));
    }

    /**
     * The protection type the payee set (X9.150 §14.5.x.3), as stored. A QR Code stored before the
     * value was kept has none, and reads as {@code tokenized}, which is what this build always said.
     */
    private static com.matera.x9qrcode.infrastructure.generated.dto.ProtectionTypeEnumDTO protectionTypeOf(String stored) {
        return isNull(stored)
            ? com.matera.x9qrcode.infrastructure.generated.dto.ProtectionTypeEnumDTO.TOKENIZED
            : com.matera.x9qrcode.infrastructure.generated.dto.ProtectionTypeEnumDTO.fromValue(stored);
    }

}
