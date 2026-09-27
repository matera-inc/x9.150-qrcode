/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.retrieveqrcode.mapper;

import com.matera.x9qrcode.app.dto.AmountRangeDTO;
import com.matera.x9qrcode.app.dto.BankPaymentAddressDTO;
import com.matera.x9qrcode.app.dto.CryptoWalletPaymentAddressDTO;
import com.matera.x9qrcode.app.dto.CurrencyEditableDTO;
import com.matera.x9qrcode.app.dto.NetworksDTO;
import com.matera.x9qrcode.app.dto.PaymentMethodDTO;
import com.matera.x9qrcode.domain.vo.AmountRangeVO;
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
public class RetrieveQRCodePaymentMethodMapper {

    public static List<PaymentMethodDTO> map(List<PaymentMethodVO> output) {
        return output.stream().map(it -> new PaymentMethodDTO(
            it.currency(),
            it.validUntil(),
            it.amount().value(),
            buildEditableAmount(it.editable()),
            buildNetworks(it.networks())
        )).toList();
    }

    private static CurrencyEditableDTO buildEditableAmount(EditableAmountVO output) {
        if (isNull(output)) {
            return null;
        }

        return new CurrencyEditableDTO(
            buildEditableAmountRange(output.range())
        );
    }

    private static AmountRangeDTO buildEditableAmountRange(AmountRangeVO output) {
        if (isNull(output)) {
            return null;
        }

        return new AmountRangeDTO(
            output.minAmount(),
            output.maxAmount()
        );
    }

    private static NetworksDTO buildNetworks(NetworksVO output) {
        return NetworksDTO.builder()
            .fedNow(buildBankPaymentAddress(output.fedNow()))
            .ach(buildBankPaymentAddress(output.ach()))
            .rtp(buildBankPaymentAddress(output.rtp()))
            .solana(buildSolanaPaymentAddress(output.solana()))
            .additionalProperties(output.additionalProperties())
            .build();
    }

    private static com.matera.x9qrcode.app.dto.SolanaPaymentAddressDTO buildSolanaPaymentAddress(
        com.matera.x9qrcode.domain.vo.SolanaPaymentAddressVO output) {

        if (isNull(output)) {
            return null;
        }

        return new com.matera.x9qrcode.app.dto.SolanaPaymentAddressDTO(output.recipient(), output.memo());
    }

    private static BankPaymentAddressDTO buildBankPaymentAddress(BankPaymentAddressVO output) {
        if (isNull(output)) {
            return null;
        }

        return new BankPaymentAddressDTO(
            output.routingNumber(),
            output.accountNumber()
        );
    }

    private static CryptoWalletPaymentAddressDTO buildCryptoWalletPaymentAddress(CryptoWalletPaymentAddressVO output) {
        if (isNull(output)) {
            return null;
        }

        return new CryptoWalletPaymentAddressDTO(output.walletAddress());
    }

}
