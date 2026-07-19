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
import com.matera.x9qrcode.infrastructure.generated.dto.PolygonDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.QRCodeStatusDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.RTPDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.SolanaDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.EthereumDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BitcoinDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BaseDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.XRPDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.ArcDTO;

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

    private static com.matera.x9qrcode.infrastructure.generated.dto.PaymentMethodDTO buildPaymentMethod(PaymentMethodDTO paymentMethod) {
        return new com.matera.x9qrcode.infrastructure.generated.dto.PaymentMethodDTO()
            .currency(paymentMethod.currency())
            .validUntil(paymentMethod.validUntil())
            .amount(paymentMethod.amount())
            .editable(buildCurrencyEditable(paymentMethod.editable()))
            .networks(buildNetworks(paymentMethod.networks()));
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

    private static NetworksSimpleDTO buildNetworks(NetworksDTO networks) {
        NetworksSimpleDTO networksDTO =
            new NetworksSimpleDTO()
                .fedNow(buildFedNow(networks.getFedNow()))
                .ACH(buildACH(networks.getAch()))
                .RTP(buildRTP(networks.getRtp()))
                .polygon(buildPolygon(networks.getPolygon()))
                .solana(buildSolana(networks.getSolana()))
                .ethereum(buildEthereum(networks.getEthereum()))
                .bitcoin(buildBitcoin(networks.getBitcoin()))
                .base(buildBase(networks.getBase()))
                .XRP(buildXRP(networks.getXrp()))
                .arc(buildArc(networks.getArc()));

        if (isNull(networks.getAdditionalProperties())) {
            return networksDTO;
        }

        for (Map.Entry<String, Object> entry : networks.getAdditionalProperties().entrySet()) {
            networksDTO.putAdditionalProperty(entry.getKey(), entry.getValue());
        }

        return networksDTO;
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

    private static PolygonDTO buildPolygon(CryptoWalletPaymentAddressDTO cryptoWalletPaymentAddress) {
        if (isNull(cryptoWalletPaymentAddress)) {
            return null;
        }

        return new PolygonDTO().walletAddress(cryptoWalletPaymentAddress.walletAddress());
    }

    private static SolanaDTO buildSolana(CryptoWalletPaymentAddressDTO cryptoWalletPaymentAddress) {
        if (isNull(cryptoWalletPaymentAddress)) {
            return null;
        }

        return new SolanaDTO().walletAddress(cryptoWalletPaymentAddress.walletAddress());
    }

    private static EthereumDTO buildEthereum(CryptoWalletPaymentAddressDTO cryptoWalletPaymentAddress) {
        if (isNull(cryptoWalletPaymentAddress)) {
            return null;
        }

        return new EthereumDTO().walletAddress(cryptoWalletPaymentAddress.walletAddress());
    }

    private static BitcoinDTO buildBitcoin(CryptoWalletPaymentAddressDTO cryptoWalletPaymentAddress) {
        if (isNull(cryptoWalletPaymentAddress)) {
            return null;
        }

        return new BitcoinDTO().walletAddress(cryptoWalletPaymentAddress.walletAddress());
    }

    private static BaseDTO buildBase(CryptoWalletPaymentAddressDTO cryptoWalletPaymentAddress) {
        if (isNull(cryptoWalletPaymentAddress)) {
            return null;
        }

        return new BaseDTO().walletAddress(cryptoWalletPaymentAddress.walletAddress());
    }

    private static XRPDTO buildXRP(CryptoWalletPaymentAddressDTO cryptoWalletPaymentAddress) {
        if (isNull(cryptoWalletPaymentAddress)) {
            return null;
        }

        return new XRPDTO().walletAddress(cryptoWalletPaymentAddress.walletAddress());
    }

    private static ArcDTO buildArc(CryptoWalletPaymentAddressDTO cryptoWalletPaymentAddress) {
        if (isNull(cryptoWalletPaymentAddress)) {
            return null;
        }

        return new ArcDTO().walletAddress(cryptoWalletPaymentAddress.walletAddress());
    }
}