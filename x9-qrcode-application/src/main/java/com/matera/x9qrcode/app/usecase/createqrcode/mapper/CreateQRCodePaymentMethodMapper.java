/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.createqrcode.mapper;

import com.matera.x9qrcode.app.dto.AmountRangeDTO;
import com.matera.x9qrcode.app.dto.BankPaymentAddressDTO;
import com.matera.x9qrcode.app.dto.CryptoWalletPaymentAddressDTO;
import com.matera.x9qrcode.app.dto.CurrencyEditableDTO;
import com.matera.x9qrcode.app.dto.NetworksDTO;
import com.matera.x9qrcode.app.dto.PaymentMethodDTO;
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

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CreateQRCodePaymentMethodMapper {

    public static List<PaymentMethodVO> map(List<PaymentMethodDTO> input) {
        return input.stream().map(paymentMethodDTO -> new PaymentMethodVO(
            paymentMethodDTO.currency(),
            paymentMethodDTO.validUntil(),
            new AmountVO(paymentMethodDTO.amount()),
            buildEditableAmount(paymentMethodDTO.editable()),
            buildNetworks(paymentMethodDTO.networks())
        )).toList();
    }

    private static EditableAmountVO buildEditableAmount(CurrencyEditableDTO input) {
        if (isNull(input)) {
            return null;
        }

        return new EditableAmountVO(
            buildEditableAmountRange(input.amountRangeDTO())
        );
    }

    private static AmountRangeVO buildEditableAmountRange(AmountRangeDTO input) {
        if (isNull(input)) {
            return null;
        }

        return new AmountRangeVO(
            input.min(),
            input.max()
        );
    }

    private static NetworksVO buildNetworks(NetworksDTO input) {
        return new NetworksVO(
            buildBankPaymentAddress(input.getFedNow()),
            buildBankPaymentAddress(input.getAch()),
            buildBankPaymentAddress(input.getRtp()),
            buildCryptoWalletPaymentAddress(input.getPolygon()),
            buildCryptoWalletPaymentAddress(input.getSolana()),
            buildCryptoWalletPaymentAddress(input.getEthereum()),
            buildCryptoWalletPaymentAddress(input.getBitcoin()),
            buildCryptoWalletPaymentAddress(input.getBase()),
            buildCryptoWalletPaymentAddress(input.getXrp()),
            buildCryptoWalletPaymentAddress(input.getArc()),
            input.getAdditionalProperties()
        );
    }

    private static BankPaymentAddressVO buildBankPaymentAddress(BankPaymentAddressDTO input) {
        if (isNull(input)) {
            return null;
        }

        return new BankPaymentAddressVO(
            input.routingNumber(),
            input.accountNumber()
        );
    }

    private static CryptoWalletPaymentAddressVO buildCryptoWalletPaymentAddress(CryptoWalletPaymentAddressDTO input) {
        if (isNull(input)) {
            return null;
        }

        return new CryptoWalletPaymentAddressVO(input.walletAddress());
    }

}
