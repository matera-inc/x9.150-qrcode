/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.response;

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
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;
import com.matera.x9qrcode.infrastructure.configuration.property.NetworksProperties;
import com.matera.x9qrcode.infrastructure.generated.dto.ACHDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.AdjustmentPayloadDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.AmountDuePayloadResponseDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BillBaseInvoiceDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BillBaseOrderDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BillBaseTipDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BillPayloadResponseDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.FedNowDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.KeyValuePairDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.NetworksSimpleDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentMethodEditableDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentPayloadResponseDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.QRCodeStatusDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.RTPDTO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RetrieveQRCodePayloadResponseMapper {

    public static PaymentPayloadResponseDTO map(RetrieveQRCodePayloadOutput output,
                                                NetworksProperties networkNaming) {
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
                : output.paymentMethods().stream().map(paymentMethod -> buildPaymentMethod(paymentMethod, networkNaming))
            .toList());
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.CreditorDTO buildCreditor(CreditorDTO creditor) {
        if (isNull(creditor)) {
            return null;
        }

        return new com.matera.x9qrcode.infrastructure.generated.dto.CreditorDTO()
            .name(creditor.name())
            .phone(creditor.phone())
            .email(creditor.email())
            .address(buildAddress(creditor.address()))
            .ultimateCreditor(buildUltimateCreditor(creditor.ultimateCreditor()))
            .MCC(creditor.MCC());
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.CreditorUltimateCreditorDTO buildUltimateCreditor(
        UltimateCreditorDTO ultimateCreditor) {
        if (isNull(ultimateCreditor)) {
            return null;
        }

        return new com.matera.x9qrcode.infrastructure.generated.dto.CreditorUltimateCreditorDTO()
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

    private static BillBaseTipDTO buildTip(TipDTO tip) {
        if (isNull(tip)) {
            return null;
        }

        return new BillBaseTipDTO()
            .presets(tip.presets())
            .allowed(tip.allowed())
            .range(buildTipRange(tip.range()));
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.TipRangeDTO buildTipRange(TipRangeDTO tipRange) {
        if (isNull(tipRange)) {
            return null;
        }

        return new com.matera.x9qrcode.infrastructure.generated.dto.TipRangeDTO(
            tipRange.minimum(),
            tipRange.maximum()
        );
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.CreditorUltimateCreditorAccountDTO buildAccount(
        AccountDTO account) {
        return new com.matera.x9qrcode.infrastructure.generated.dto.CreditorUltimateCreditorAccountDTO()
            .id(account.id())
            .schemaName(account.schemaName());
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.AddressDTO buildAddress(AddressDTO address) {
        if (isNull(address)) {
            return null;
        }

        return new com.matera.x9qrcode.infrastructure.generated.dto.AddressDTO()
            .line1(address.line1())
            .line2(address.line2())
            .city(address.city())
            .country(address.country())
            .state(address.state())
            .postalCode(address.postalCode());
    }

    private static BillBaseInvoiceDTO buildInvoice(InvoiceDTO invoice) {
        if (isNull(invoice)) {
            return null;
        }

        return new BillBaseInvoiceDTO()
            .number(invoice.number())
            .date(invoice.date())
            .dueDate(invoice.dueDate())
            .invoicee(buildInvoicee(invoice.invoiceeDTO()));
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.InvoiceeDTO buildInvoicee(InvoiceeDTO invoicee) {
        if (isNull(invoicee)) {
            return null;
        }

        return new com.matera.x9qrcode.infrastructure.generated.dto.InvoiceeDTO()
            .name(invoicee.name())
            .phone(invoicee.phone())
            .email(invoicee.email())
            .address(buildAddress(invoicee.addressDTO()));
    }

    private static BillBaseOrderDTO buildOrder(OrderDTO order) {
        if (isNull(order)) {
            return null;
        }

        return new BillBaseOrderDTO()
            .number(order.number())
            .date(order.date());
    }

    private static List<KeyValuePairDTO> buildAdditionalInformation(Map<String, String> additionalInformation) {
        if (isNull(additionalInformation) || additionalInformation.isEmpty()) {
            return null;
        }

        return additionalInformation.entrySet().stream()
            .map(entry -> new KeyValuePairDTO().key(entry.getKey()).value(entry.getValue()))
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

    private static com.matera.x9qrcode.infrastructure.generated.dto.PaymentMethodDTO buildPaymentMethod(
        PaymentMethodDTO paymentMethod, NetworksProperties networkNaming) {
        return new com.matera.x9qrcode.infrastructure.generated.dto.PaymentMethodDTO()
            .currency(paymentMethod.currency())
            .validUntil(paymentMethod.validUntil())
            .amount(paymentMethod.amount())
            .editable(buildCurrencyEditable(paymentMethod.editable()))
            .networks(buildNetworks(paymentMethod.networks(), networkNaming));
    }

    private static PaymentMethodEditableDTO buildCurrencyEditable(CurrencyEditableDTO editable) {
        if (isNull(editable)) {
            return null;
        }

        return new PaymentMethodEditableDTO()
            .range(buildCurrencyEditableRange(editable.amountRangeDTO()));
    }

    private static com.matera.x9qrcode.infrastructure.generated.dto.IntegerRangeDTO buildCurrencyEditableRange(AmountRangeDTO range) {
        if (isNull(range)) {
            return null;
        }

        return new com.matera.x9qrcode.infrastructure.generated.dto.IntegerRangeDTO()
            .min(range.min())
            .max(range.max());
    }

    /**
     * The networks object, with each rail written under the key this deployment is configured to
     * emit (lower-case {@code fednow}/{@code rtp}/{@code ach} unless overridden — see
     * {@link NetworksProperties}).
     *
     * <p>The rails go through {@code additionalProperties} rather than the contract's own typed
     * properties, because a typed property's name is fixed at code-generation time and the whole
     * point here is that the emitted name is a deployment decision. With nothing configured the two
     * routes produce byte-identical JSON; configure an override and only this route can honour it.
     *
     * <p>Done here rather than in a Jackson serializer on purpose: this runs when the DTO is built,
     * so it cannot be bypassed by a hand-rolled {@code ObjectMapper} that missed a module — which is
     * a mistake this codebase has already made once.
     */
    private static NetworksSimpleDTO buildNetworks(NetworksDTO networks, NetworksProperties networkNaming) {
        NetworksSimpleDTO networksDTO = new NetworksSimpleDTO();

        putRail(networksDTO, networkNaming, NetworkEnum.FEDNOW, buildFedNow(networks.getFedNow()));
        putRail(networksDTO, networkNaming, NetworkEnum.RTP, buildRTP(networks.getRtp()));
        putRail(networksDTO, networkNaming, NetworkEnum.ACH, buildACH(networks.getAch()));

        if (isNull(networks.getAdditionalProperties())) {
            return networksDTO;
        }

        for (Map.Entry<String, Object> entry : networks.getAdditionalProperties().entrySet()) {
            networksDTO.putAdditionalProperty(entry.getKey(), entry.getValue());
        }

        return networksDTO;
    }

    private static void putRail(NetworksSimpleDTO networksDTO,
                                NetworksProperties networkNaming,
                                NetworkEnum rail,
                                Object address) {
        if (isNull(address)) {
            return;
        }

        networksDTO.putAdditionalProperty(networkNaming.keyFor(rail.value()), address);
    }

    private static RTPDTO buildRTP(BankPaymentAddressDTO bankPaymentAddress) {
        if (isNull(bankPaymentAddress)) {
            return null;
        }

        return new RTPDTO()
            .accountNumber(bankPaymentAddress.accountNumber())
            .routingNumber(bankPaymentAddress.routingNumber())
            .protectionType(com.matera.x9qrcode.infrastructure.generated.dto.ProtectionTypeEnumDTO.TOKENIZED);
    }

    private static ACHDTO buildACH(BankPaymentAddressDTO bankPaymentAddress) {
        if (isNull(bankPaymentAddress)) {
            return null;
        }

        return new ACHDTO()
            .accountNumber(bankPaymentAddress.accountNumber())
            .routingNumber(bankPaymentAddress.routingNumber())
            .protectionType(com.matera.x9qrcode.infrastructure.generated.dto.ProtectionTypeEnumDTO.TOKENIZED);
    }

    private static FedNowDTO buildFedNow(BankPaymentAddressDTO bankPaymentAddress) {
        if (isNull(bankPaymentAddress)) {
            return null;
        }

        return new FedNowDTO()
            .accountNumber(bankPaymentAddress.accountNumber())
            .routingNumber(bankPaymentAddress.routingNumber())
            .protectionType(com.matera.x9qrcode.infrastructure.generated.dto.ProtectionTypeEnumDTO.TOKENIZED);
    }
}