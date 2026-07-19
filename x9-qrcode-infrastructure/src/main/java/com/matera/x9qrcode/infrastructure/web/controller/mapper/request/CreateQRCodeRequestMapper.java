/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.request;

import com.matera.x9qrcode.app.dto.AccountDTO;
import com.matera.x9qrcode.app.dto.AddressDTO;
import com.matera.x9qrcode.app.dto.AdjustmentDTO;
import com.matera.x9qrcode.app.dto.AdjustmentParametersDTO;
import com.matera.x9qrcode.app.dto.AmountDueDTO;
import com.matera.x9qrcode.app.dto.AmountRangeDTO;
import com.matera.x9qrcode.app.dto.BankPaymentAddressDTO;
import com.matera.x9qrcode.app.dto.BillDTO;
import com.matera.x9qrcode.app.dto.CreditorDTO;
import com.matera.x9qrcode.app.dto.CryptoWalletPaymentAddressDTO;
import com.matera.x9qrcode.app.dto.CurrencyEditableDTO;
import com.matera.x9qrcode.app.dto.DiscountDTO;
import com.matera.x9qrcode.app.dto.InvoiceDTO;
import com.matera.x9qrcode.app.dto.InvoiceeDTO;
import com.matera.x9qrcode.app.dto.LateFeesDTO;
import com.matera.x9qrcode.app.dto.NetworksDTO;
import com.matera.x9qrcode.app.dto.OrderDTO;
import com.matera.x9qrcode.app.dto.PaymentMethodDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationDTO;
import com.matera.x9qrcode.app.dto.TipDTO;
import com.matera.x9qrcode.app.dto.UltimateCreditorDTO;
import com.matera.x9qrcode.app.dto.enumerated.FormulaEnumDTO;
import com.matera.x9qrcode.app.dto.enumerated.NotificationKindEnumDTO;
import com.matera.x9qrcode.app.dto.enumerated.PaymentTimingEnumDTO;
import com.matera.x9qrcode.app.usecase.createqrcode.CreateQRCodeInput;
import com.matera.x9qrcode.infrastructure.generated.dto.ACHDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BillDTO.PaymentTimingEnum;
import com.matera.x9qrcode.infrastructure.generated.dto.FedNowDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.IntegerRangeDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.KeyValuePairDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestInputDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PolygonDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.RTPDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.SolanaDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.EthereumDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BitcoinDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BaseDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.XRPDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.ArcDTO;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.springframework.util.CollectionUtils.isEmpty;

import static java.util.Objects.isNull;

public final class CreateQRCodeRequestMapper {

    public static CreateQRCodeInput map(PaymentRequestInputDTO paymentRequestInput) {
        return new CreateQRCodeInput(
                paymentRequestInput.getLocationId(),
                paymentRequestInput.getValidUntil(),
                createCreditorInput(paymentRequestInput.getCreditor()),
                createBillInput(paymentRequestInput.getBill()),
                paymentRequestInput.getUnstructured(),
                createAdditionalInformationInput(paymentRequestInput.getAdditionalInformation()),
                createPaymentNotificationInput(paymentRequestInput.getPaymentNotification()),
                createPaymentMethodsInput(paymentRequestInput.getPaymentMethods()));
    }

    private static CreditorDTO createCreditorInput(
            com.matera.x9qrcode.infrastructure.generated.dto.CreditorDTO creditor) {
        return new CreditorDTO(
                creditor.getName(),
                creditor.getPhone(),
                creditor.getEmail(),
                createAddressInput(creditor.getAddress()),
                createUltimateCreditorInput(creditor.getUltimateCreditor()),
                creditor.getMCC());
    }

    private static UltimateCreditorDTO createUltimateCreditorInput(
            com.matera.x9qrcode.infrastructure.generated.dto.CreditorUltimateCreditorDTO ultimateCreditor) {
        if (isNull(ultimateCreditor)) {
            return null;
        }

        return new UltimateCreditorDTO(
                createAccountInput(ultimateCreditor.getAccount()),
                ultimateCreditor.getName(),
                ultimateCreditor.getPhone(),
                ultimateCreditor.getEmail(),
                createAddressInput(ultimateCreditor.getAddress()));
    }

    private static BillDTO createBillInput(com.matera.x9qrcode.infrastructure.generated.dto.BillDTO bill) {
        return new BillDTO(
                bill.getDescription(),
                createOrderInput(bill.getOrder()),
                createInvoiceInput(bill.getInvoice()),
                createAmountDueInput(bill.getAmountDue()),
                createTipInput(bill.getTip()),
                createPaymentTiming(bill.getPaymentTiming()));
    }

    private static PaymentTimingEnumDTO createPaymentTiming(PaymentTimingEnum paymentTiming) {
        if (isNull(paymentTiming)) {
            return null;
        }

        return PaymentTimingEnumDTO.fromValue(paymentTiming.getValue());
    }

    private static OrderDTO createOrderInput(com.matera.x9qrcode.infrastructure.generated.dto.BillBaseOrderDTO order) {
        if (isNull(order)) {
            return null;
        }

        return new OrderDTO(
                order.getNumber(),
                order.getDate());
    }

    private static InvoiceDTO createInvoiceInput(
            com.matera.x9qrcode.infrastructure.generated.dto.BillBaseInvoiceDTO invoice) {
        if (isNull(invoice)) {
            return null;
        }

        return new InvoiceDTO(
                invoice.getNumber(),
                invoice.getDate(),
                invoice.getDueDate(),
                createInvoiceeInput(invoice.getInvoicee()));
    }

    private static TipDTO createTipInput(com.matera.x9qrcode.infrastructure.generated.dto.BillBaseTipDTO tip) {
        if (isNull(tip)) {
            return TipDTO.noTip();
        }

        Boolean allowed = tip.getAllowed();
        List<Integer> presets = isEmpty(tip.getPresets()) ? null : tip.getPresets();

        if (isNull(tip.getRange())) {
            return TipDTO.of(allowed, presets);
        }

        return TipDTO.of(allowed, tip.getRange().getMin(), tip.getRange().getMax(), presets);
    }

    private static Map<String, String> createAdditionalInformationInput(List<KeyValuePairDTO> additionalInformation) {
        if (isNull(additionalInformation)) {
            return null;
        }

        if (additionalInformation.isEmpty()) {
            return null;
        }

        Map<String, String> additionalInformationMap = new HashMap<>();

        additionalInformation.forEach(
                keyValuePairDTO -> additionalInformationMap.put(keyValuePairDTO.getKey(), keyValuePairDTO.getValue()));

        return additionalInformationMap;
    }

    private static AccountDTO createAccountInput(
            com.matera.x9qrcode.infrastructure.generated.dto.CreditorUltimateCreditorAccountDTO account) {
        return new AccountDTO(account.getId(), account.getSchemaName());
    }

    private static AddressDTO createAddressInput(com.matera.x9qrcode.infrastructure.generated.dto.AddressDTO address) {
        if (isNull(address)) {
            return null;
        }

        return new AddressDTO(
                address.getLine1(),
                address.getLine2(),
                address.getCity(),
                address.getState(),
                address.getPostalCode(),
                address.getCountry());
    }

    private static InvoiceeDTO createInvoiceeInput(
            com.matera.x9qrcode.infrastructure.generated.dto.InvoiceeDTO invoicee) {
        if (isNull(invoicee)) {
            return null;
        }

        return new InvoiceeDTO(
                invoicee.getName(),
                invoicee.getPhone(),
                invoicee.getEmail(),
                createAddressInput(invoicee.getAddress()));
    }

    private static AmountDueDTO createAmountDueInput(
            com.matera.x9qrcode.infrastructure.generated.dto.AmountDueDTO amountDue) {
        if (isNull(amountDue)) {
            return null;
        }

        return new AmountDueDTO(
                amountDue.getAmount(),
                amountDue.getCurrency(),
                createAdjustmentInput(amountDue.getAdjustments()));
    }

    private static AdjustmentDTO createAdjustmentInput(
            com.matera.x9qrcode.infrastructure.generated.dto.AdjustmentDTO adjustment) {
        if (isNull(adjustment)) {
            return null;
        }

        com.matera.x9qrcode.infrastructure.generated.dto.AdjustmentDTO.FormulaEnum formulaEnum = adjustment
                .getFormula();

        String formula = Objects.nonNull(formulaEnum)
                ? formulaEnum.getValue()
                : null;

        return new AdjustmentDTO(
                FormulaEnumDTO.fromValue(formula),
                createAdjustmentParametersInput(adjustment.getParameters()));
    }

    private static AdjustmentParametersDTO createAdjustmentParametersInput(
            com.matera.x9qrcode.infrastructure.generated.dto.AdjustmentParametersDTO adjustmentParameters) {
        List<DiscountDTO> discounts = adjustmentParameters.getDiscounts().isEmpty() ? null
                : adjustmentParameters.getDiscounts().stream().map(CreateQRCodeRequestMapper::createDiscountInput)
                        .toList();

        return new AdjustmentParametersDTO(discounts, createLateFeesInput(adjustmentParameters.getLateFees()));
    }

    private static DiscountDTO createDiscountInput(
            com.matera.x9qrcode.infrastructure.generated.dto.AdjustmentParametersDiscountsInnerDTO discount) {
        return new DiscountDTO(discount.getDaysBefore(), discount.getDiscount(), discount.getExplanation());
    }

    private static LateFeesDTO createLateFeesInput(
            com.matera.x9qrcode.infrastructure.generated.dto.AdjustmentParametersLateFeesDTO lateFees) {
        return new LateFeesDTO(
                lateFees.getFixed(),
                lateFees.getPerDay(),
                lateFees.getExplanation());
    }

    private static List<PaymentMethodDTO> createPaymentMethodsInput(
            List<com.matera.x9qrcode.infrastructure.generated.dto.PaymentMethodDTO> paymentMethods) {
        return paymentMethods.stream().map(paymentMethod -> new PaymentMethodDTO(paymentMethod.getCurrency(),
                paymentMethod.getValidUntil(),
                paymentMethod.getAmount(),
                createCurrencyEditableAmountInput(paymentMethod.getEditable()),
                createNetworksInput(paymentMethod.getNetworks()))).toList();
    }

    private static CurrencyEditableDTO createCurrencyEditableAmountInput(
            com.matera.x9qrcode.infrastructure.generated.dto.PaymentMethodEditableDTO currencyEditableRange) {
        if (isNull(currencyEditableRange)) {
            return null;
        }

        IntegerRangeDTO range = currencyEditableRange.getRange();

        return new CurrencyEditableDTO(
                createAmountRangeInput(range));
    }

    private static AmountRangeDTO createAmountRangeInput(IntegerRangeDTO paymentMethodEditableRange) {
        if (isNull(paymentMethodEditableRange)) {
            return null;
        }

        return new AmountRangeDTO(paymentMethodEditableRange.getMin(), paymentMethodEditableRange.getMax());
    }

    private static PaymentNotificationDTO createPaymentNotificationInput(
            com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDTO paymentNotification) {
        if (isNull(paymentNotification)) {
            return null;
        }

        return new PaymentNotificationDTO(NotificationKindEnumDTO.fromValue(paymentNotification.getKind().getValue()),
                paymentNotification.getEndpoint(), null);
    }

    private static NetworksDTO createNetworksInput(
            com.matera.x9qrcode.infrastructure.generated.dto.NetworksSimpleDTO networksSimple) {
        FedNowDTO fedNow = networksSimple.getFedNow();
        RTPDTO rtp = networksSimple.getRTP();
        ACHDTO ach = networksSimple.getACH();
        PolygonDTO polygon = networksSimple.getPolygon();
        SolanaDTO solana = networksSimple.getSolana();
        EthereumDTO ethereum = networksSimple.getEthereum();
        BitcoinDTO bitcoin = networksSimple.getBitcoin();
        BaseDTO base = networksSimple.getBase();
        XRPDTO xrp = networksSimple.getXRP();
        ArcDTO arc = networksSimple.getArc();

        return NetworksDTO.builder()
                .fedNow(isNull(fedNow) ? null
                        : new BankPaymentAddressDTO(fedNow.getRoutingNumber(), fedNow.getAccountNumber()))
                .rtp(isNull(rtp) ? null : new BankPaymentAddressDTO(rtp.getRoutingNumber(), rtp.getAccountNumber()))
                .ach(isNull(ach) ? null : new BankPaymentAddressDTO(ach.getRoutingNumber(), ach.getAccountNumber()))
                .polygon(isNull(polygon) ? null : new CryptoWalletPaymentAddressDTO(polygon.getWalletAddress()))
                .solana(isNull(solana) ? null : new CryptoWalletPaymentAddressDTO(solana.getWalletAddress()))
                .ethereum(isNull(ethereum) ? null : new CryptoWalletPaymentAddressDTO(ethereum.getWalletAddress()))
                .bitcoin(isNull(bitcoin) ? null : new CryptoWalletPaymentAddressDTO(bitcoin.getWalletAddress()))
                .base(isNull(base) ? null : new CryptoWalletPaymentAddressDTO(base.getWalletAddress()))
                .xrp(isNull(xrp) ? null : new CryptoWalletPaymentAddressDTO(xrp.getWalletAddress()))
                .arc(isNull(arc) ? null : new CryptoWalletPaymentAddressDTO(arc.getWalletAddress()))
                .additionalProperties(networksSimple.getAdditionalProperties())
                .build();
    }

}
