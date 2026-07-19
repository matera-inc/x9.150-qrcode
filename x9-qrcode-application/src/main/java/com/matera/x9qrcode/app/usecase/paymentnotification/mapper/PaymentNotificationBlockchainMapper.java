/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.paymentnotification.mapper;

import com.matera.x9qrcode.app.dto.BlockchainDTO;
import com.matera.x9qrcode.domain.vo.BlockchainVO;
import com.matera.x9qrcode.domain.vo.CryptoWalletPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.enumerated.ActionEnum;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentNotificationBlockchainMapper {

    public static BlockchainVO map(BlockchainDTO input) {
        if (isNull(input)) {
            return null;
        }

        return new BlockchainVO(
            ActionEnum.fromValue(input.action().value()),
            new CryptoWalletPaymentAddressVO(input.from()),
            new CryptoWalletPaymentAddressVO(input.to())
        );
    }

}
