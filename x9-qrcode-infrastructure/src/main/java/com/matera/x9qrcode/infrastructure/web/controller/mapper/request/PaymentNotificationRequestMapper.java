/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.request;

import com.matera.x9qrcode.app.dto.BlockchainDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationDataDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationPayerDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationPaymentDTO;
import com.matera.x9qrcode.app.dto.enumerated.ActionEnumDTO;
import com.matera.x9qrcode.app.dto.enumerated.NetworkEnumDTO;
import com.matera.x9qrcode.app.usecase.paymentnotification.PaymentNotificationQRCodeInput;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataPayerDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataPaymentDTO;

import static java.util.Objects.isNull;

public final class PaymentNotificationRequestMapper {

    public static PaymentNotificationQRCodeInput map(com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataDTO paymentNotificationInput) {
        PaymentNotificationDataDTO paymentNotificationData = new PaymentNotificationDataDTO(
            paymentNotificationInput.getPayment().getQrcodeId(),
            buildPaymentInput(paymentNotificationInput.getPayment()),
            buildPayerInput(paymentNotificationInput.getPayer()),
            paymentNotificationInput.getExpectedDate(),
            buildBlockchainInput(paymentNotificationInput.getBlockchain())
        );

        return new PaymentNotificationQRCodeInput(paymentNotificationData);
    }

    private static PaymentNotificationPaymentDTO buildPaymentInput(PaymentNotificationDataPaymentDTO payment) {
        return new PaymentNotificationPaymentDTO(
            payment.getAmount(),
            payment.getTipAmount(),
            payment.getCurrency(),
            NetworkEnumDTO.fromValue(payment.getNetwork().getValue()),
            payment.getTransactionId()
        );
    }

    private static PaymentNotificationPayerDTO buildPayerInput(PaymentNotificationDataPayerDTO payer) {
        if (isNull(payer)) {
            return null;
        }

        return new PaymentNotificationPayerDTO(payer.getInfo());
    }

    private static BlockchainDTO buildBlockchainInput(com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataBlockchainDTO blockchain) {
        if (blockchain == null) {
            return null;
        }

        return new BlockchainDTO(
            ActionEnumDTO.fromValue(blockchain.getAction().getValue()),
            blockchain.getFrom(),
            blockchain.getTo()
        );
    }

}
