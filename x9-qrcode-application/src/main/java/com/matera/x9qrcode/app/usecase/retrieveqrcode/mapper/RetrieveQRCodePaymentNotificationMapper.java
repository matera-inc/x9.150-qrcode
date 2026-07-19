/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.retrieveqrcode.mapper;

import com.matera.x9qrcode.app.dto.BlockchainDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationDataDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationPayerDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationPaymentDTO;
import com.matera.x9qrcode.app.dto.enumerated.ActionEnumDTO;
import com.matera.x9qrcode.app.dto.enumerated.NetworkEnumDTO;
import com.matera.x9qrcode.app.dto.enumerated.NotificationKindEnumDTO;
import com.matera.x9qrcode.domain.vo.BlockchainVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationPaymentVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationVO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;


import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class RetrieveQRCodePaymentNotificationMapper {

    public static PaymentNotificationDTO map(PaymentNotificationVO input) {
        if (isNull(input)) { return null; }
        return new PaymentNotificationDTO(
            NotificationKindEnumDTO.valueOf(input.kind().name()),
            input.endpoint(),
            buildData(input.data())
        );
    }

    private static PaymentNotificationDataDTO buildData(PaymentNotificationDataVO data) {
        if (isNull(data)) { return null; }
        return new PaymentNotificationDataDTO(
            null,
            buildPayment(data.payment()),
            isNull(data.payer()) ? null : new PaymentNotificationPayerDTO(data.payer().info()),
            isNull(data.expectedDate()) ? null : data.expectedDate().value(),
            buildBlockchain(data.blockchain())
        );
    }

    private static PaymentNotificationPaymentDTO buildPayment(PaymentNotificationPaymentVO payment) {
        return new PaymentNotificationPaymentDTO(
            payment.amount().value(),
            isNull(payment.tipAmount()) ? null : payment.tipAmount().value(),
            payment.currency(),
            NetworkEnumDTO.fromValue(payment.network().value()),
            payment.transactionId()
        );
    }
    
    private static BlockchainDTO buildBlockchain(BlockchainVO blockchain) {
        if (isNull(blockchain)) {
            return null;
        }
        return new BlockchainDTO(
            ActionEnumDTO.fromValue(blockchain.action().value()),
            blockchain.from().walletAddress(),
            blockchain.to().walletAddress()
        );
    }
}
