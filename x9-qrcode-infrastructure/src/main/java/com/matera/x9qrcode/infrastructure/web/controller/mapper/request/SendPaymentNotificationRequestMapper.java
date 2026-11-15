/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.request;

import com.matera.x9qrcode.app.usecase.sendpaymentnotification.PaymentPhase;
import com.matera.x9qrcode.app.usecase.sendpaymentnotification.SendPaymentNotificationInput;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.infrastructure.generated.dto.OutboundPaymentNotificationDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.OutboundPaymentNotificationDataDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataBlockchainDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataPayerDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataPaymentDTO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.net.URI;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SendPaymentNotificationRequestMapper {

    public static SendPaymentNotificationInput map(PaymentPhase phase, OutboundPaymentNotificationDTO request) {
        return new SendPaymentNotificationInput(
            phase,
            // No signer subject: this is the OUTBOUND path, where we are the sender rather than
            // verifying somebody else's signature. Only the data is borrowed from that mapper.
            PaymentNotificationRequestMapper.map(asRead(request.getNotification()), null).paymentNotificationData(),
            endpoint(request.getEndpoint()),
            request.getCorrelationId());
    }

    /**
     * The notification as the inbound mapper reads it. The outbound body has its own, closed,
     * schema ({@code OutboundPaymentNotificationData}: what we generate refuses an unknown field,
     * INTERPRETATION I-16); the members are the same.
     */
    private static PaymentNotificationDataDTO asRead(OutboundPaymentNotificationDataDTO notification) {
        if (isNull(notification)) {
            return null;
        }
        var payment = notification.getPayment();
        var payer = notification.getPayer();
        var blockchain = notification.getBlockchain();
        return new PaymentNotificationDataDTO()
            .payment(isNull(payment) ? null : new PaymentNotificationDataPaymentDTO()
                .qrcodeId(payment.getQrcodeId())
                .amount(payment.getAmount())
                .tipAmount(payment.getTipAmount())
                .currency(payment.getCurrency())
                .network(payment.getNetwork())
                .transactionId(payment.getTransactionId()))
            .payer(isNull(payer) ? null : new PaymentNotificationDataPayerDTO().info(payer.getInfo()))
            .expectedDate(notification.getExpectedDate())
            .blockchain(isNull(blockchain) ? null : new PaymentNotificationDataBlockchainDTO()
                .action(blockchain.getAction())
                .from(blockchain.getFrom())
                .to(blockchain.getTo()));
    }

    /**
     * The contract already parses this as a URI, so what is left to check is that it can actually be
     * reached: an absolute URL with a host. A relative or schemeless value is the caller's mistake,
     * and refusing it here keeps it from surfacing later as a failure to deliver — which would read
     * as somebody else's outage.
     */
    private static URI endpoint(URI endpoint) {
        if (isNull(endpoint)) {
            throw new BusinessRuleException("endpoint", "The payee's notification endpoint is required.");
        }

        if (!endpoint.isAbsolute() || isNull(endpoint.getHost())) {
            throw new BusinessRuleException("endpoint",
                "The notification endpoint must be an absolute URL with a host: %s".formatted(endpoint));
        }

        return endpoint;
    }

}
