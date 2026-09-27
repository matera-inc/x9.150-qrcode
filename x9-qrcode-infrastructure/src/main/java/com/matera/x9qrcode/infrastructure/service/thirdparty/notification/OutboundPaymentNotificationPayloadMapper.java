/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.notification;

import com.matera.x9qrcode.app.dto.BlockchainDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationDataDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationPayerDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationPaymentDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.BlockchainActionEnumDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataBlockchainDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataPayerDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataPaymentDTO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import static java.util.Objects.isNull;

/**
 * Puts an outbound notification into the shape that goes <em>on the wire</em>.
 *
 * <p>The application's own {@link PaymentNotificationDataDTO} is not that shape, and the difference
 * is not cosmetic: internally the QR Code id is a field of the notification, while ANSI X9.150-2026
 * — and therefore our OpenAPI contract — carries it as {@code payment.qrcodeId}, inside the payment
 * object. Signing the internal record directly produced a payload with a top-level {@code qrCodeId}
 * and a {@code payment} with none, which every conformant payee refuses. Ours did.
 *
 * <p>So the adapter that speaks to another deployment maps to the generated contract types first.
 * They are the one description of the wire format we have, they live in this layer, and using them
 * here means the notification we <em>send</em> and the notification we <em>accept</em> can never
 * drift apart: both are the same generated class.
 *
 * <p>{@code network} stays a free string on purpose. A payer may be settling on a network this
 * service does not interpret, and an enum here would refuse to even report it.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OutboundPaymentNotificationPayloadMapper {

    public static com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataDTO map(
            PaymentNotificationDataDTO notification) {

        var wire = new com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataDTO();

        wire.setPayment(payment(notification.qrCodeId(), notification.payment()));
        wire.setPayer(payer(notification.payer()));
        wire.setExpectedDate(notification.expectedDate());
        wire.setBlockchain(blockchain(notification.blockchain()));

        return wire;
    }

    private static PaymentNotificationDataPaymentDTO payment(String qrCodeId, PaymentNotificationPaymentDTO payment) {
        var wire = new PaymentNotificationDataPaymentDTO();

        // The whole point of this mapper: the id belongs INSIDE payment.
        wire.setQrcodeId(qrCodeId);

        if (isNull(payment)) {
            return wire;
        }

        wire.setAmount(payment.amount());
        wire.setTipAmount(payment.tipAmount());
        wire.setCurrency(payment.currency());
        wire.setNetwork(payment.network());
        wire.setTransactionId(payment.transactionId());

        return wire;
    }

    private static PaymentNotificationDataPayerDTO payer(PaymentNotificationPayerDTO payer) {
        if (isNull(payer)) {
            return null;
        }

        var wire = new PaymentNotificationDataPayerDTO();
        wire.setInfo(payer.info());

        return wire;
    }

    private static PaymentNotificationDataBlockchainDTO blockchain(BlockchainDTO blockchain) {
        if (isNull(blockchain)) {
            return null;
        }

        var wire = new PaymentNotificationDataBlockchainDTO();

        wire.setAction(isNull(blockchain.action())
            ? null
            : BlockchainActionEnumDTO.fromValue(blockchain.action().value()));
        wire.setFrom(blockchain.from());
        wire.setTo(blockchain.to());

        return wire;
    }

}
