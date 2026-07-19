/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.patchqrcode.mapper;

import com.matera.x9qrcode.app.dto.AmountRangeDTO;
import com.matera.x9qrcode.app.dto.BankPaymentAddressDTO;
import com.matera.x9qrcode.app.dto.CryptoWalletPaymentAddressDTO;
import com.matera.x9qrcode.app.dto.CurrencyEditableUpdateDTO;
import com.matera.x9qrcode.app.dto.NetworksUpdateDTO;
import com.matera.x9qrcode.app.dto.PaymentMethodUpdateDTO;
import com.matera.x9qrcode.app.usecase.PartialInput;
import com.matera.x9qrcode.domain.vo.AmountRangeVO;
import com.matera.x9qrcode.domain.vo.AmountVO;
import com.matera.x9qrcode.domain.vo.BankPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.CryptoWalletPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.EditableAmountVO;
import com.matera.x9qrcode.domain.vo.NetworksVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PatchQRCodePaymentMethodsMapper {

    public static List<PaymentMethodVO> map(List<PaymentMethodVO> paymentMethods, List<PaymentMethodUpdateDTO> paymentMethodsUpdate) {
        List<PaymentMethodVO> updatedPaymentMethods = paymentMethodsUpdate.stream()
            .map(buildPaymentMethodsFunction(paymentMethods))
            .filter(Objects::nonNull)
            .toList();

        if (updatedPaymentMethods.isEmpty()) {
            return paymentMethods;
        } else {
            return updatedPaymentMethods;
        }
    }

    private static Function<PaymentMethodUpdateDTO, PaymentMethodVO> buildPaymentMethodsFunction(List<PaymentMethodVO> paymentMethods) {
        return paymentMethodUpdateDTO -> {
            PaymentMethodVO paymentMethod =
                paymentMethods.stream()
                    .filter(pm -> pm.currency().equals(paymentMethodUpdateDTO.currency()))
                    .findFirst()
                    .orElse(null);

            if (isNull(paymentMethod)) {
                return null;
            }

            EditableAmountVO editable = paymentMethod.editable();

            return new PaymentMethodVO(
                paymentMethodUpdateDTO.currency(),
                paymentMethodUpdateDTO.validUntil(),
                new AmountVO(paymentMethodUpdateDTO.amount()),
                paymentMethodUpdateDTO.currencyEditableUpdateDTO().isPresent()
                    ? buildPaymentMethod(editable, paymentMethodUpdateDTO.currencyEditableUpdateDTO().get())
                    : editable,
                buildNetworks(paymentMethod.networks(), paymentMethodUpdateDTO.networksUpdateDTO()));
        };
    }

    private static EditableAmountVO buildPaymentMethod(EditableAmountVO editable, CurrencyEditableUpdateDTO currencyEditableUpdateDTO) {
        PartialInput<AmountRangeDTO> amountRangeDTO = currencyEditableUpdateDTO.amountRangeUpdateDTO();

        return new EditableAmountVO(
            amountRangeDTO.isPresent() ? buildAmountRange(amountRangeDTO.get()) : editable.range()
        );
    }

    private static NetworksVO buildNetworks(NetworksVO networks, NetworksUpdateDTO networksUpdateDTO) {
        PartialInput<BankPaymentAddressDTO> fedNow = networksUpdateDTO.getFedNow();
        PartialInput<BankPaymentAddressDTO> rtp = networksUpdateDTO.getRtp();
        PartialInput<BankPaymentAddressDTO> ach = networksUpdateDTO.getAch();
        PartialInput<CryptoWalletPaymentAddressDTO> polygon = networksUpdateDTO.getPolygon();
        PartialInput<CryptoWalletPaymentAddressDTO> solana = networksUpdateDTO.getSolana();
        PartialInput<CryptoWalletPaymentAddressDTO> ethereum = networksUpdateDTO.getEthereum();
        PartialInput<CryptoWalletPaymentAddressDTO> bitcoin = networksUpdateDTO.getBitcoin();
        PartialInput<CryptoWalletPaymentAddressDTO> base = networksUpdateDTO.getBase();
        PartialInput<CryptoWalletPaymentAddressDTO> xrp = networksUpdateDTO.getXrp();
        PartialInput<CryptoWalletPaymentAddressDTO> arc = networksUpdateDTO.getArc();
        PartialInput<Map<String, Object>> additionalProperties = networksUpdateDTO.getAdditionalProperties();

        return new NetworksVO(
            fedNow.isPresent() ? buildBankPaymentAddress(fedNow.get()) : networks.fedNow(),
            ach.isPresent() ? buildBankPaymentAddress(ach.get()) : networks.ach(),
            rtp.isPresent() ? buildBankPaymentAddress(rtp.get()) : networks.rtp(),
            polygon.isPresent() ? buildCryptoWalletPaymentAddress(polygon.get()) : networks.polygon(),
            solana.isPresent() ? buildCryptoWalletPaymentAddress(solana.get()) : networks.solana(),
            ethereum.isPresent() ? buildCryptoWalletPaymentAddress(ethereum.get()) : networks.ethereum(),
            bitcoin.isPresent() ? buildCryptoWalletPaymentAddress(bitcoin.get()) : networks.bitcoin(),
            base.isPresent() ? buildCryptoWalletPaymentAddress(base.get()) : networks.base(),
            xrp.isPresent() ? buildCryptoWalletPaymentAddress(xrp.get()) : networks.xrp(),
            arc.isPresent() ? buildCryptoWalletPaymentAddress(arc.get()) : networks.arc(),
            additionalProperties.isPresent() ? additionalProperties.get() : networks.additionalProperties()
        );
    }

    private static AmountRangeVO buildAmountRange(AmountRangeDTO amountRangeDTO) {
        if (isNull(amountRangeDTO)) {
            return null;
        }

        return new AmountRangeVO(
            amountRangeDTO.min(),
            amountRangeDTO.max()
        );
    }

    private static BankPaymentAddressVO buildBankPaymentAddress(BankPaymentAddressDTO bankPaymentAddressDTO) {
        if (isNull(bankPaymentAddressDTO)) {
            return null;
        }

        return new BankPaymentAddressVO(
            bankPaymentAddressDTO.routingNumber(),
            bankPaymentAddressDTO.accountNumber()
        );
    }

    private static CryptoWalletPaymentAddressVO buildCryptoWalletPaymentAddress(CryptoWalletPaymentAddressDTO cryptoWalletPaymentAddressDTO) {
        if (isNull(cryptoWalletPaymentAddressDTO)) {
            return null;
        }

        return new CryptoWalletPaymentAddressVO(cryptoWalletPaymentAddressDTO.walletAddress());
    }

}
