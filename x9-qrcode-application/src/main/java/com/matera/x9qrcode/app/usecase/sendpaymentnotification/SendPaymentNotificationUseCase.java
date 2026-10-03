/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.sendpaymentnotification;

import com.matera.x9qrcode.app.service.QRCodeOutboundNotificationService;
import com.matera.x9qrcode.app.service.QRCodeOutboundNotificationService.OutboundNotificationResult;
import com.matera.x9qrcode.app.usecase.UseCase;
import com.matera.x9qrcode.app.dto.BlockchainDTO;
import com.matera.x9qrcode.app.dto.enumerated.ActionEnumDTO;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;

import lombok.RequiredArgsConstructor;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Signs a payer's notification and delivers it to the payee.
 *
 * <p>The payer's side of the two-phase flow. Their software decides what to pay and when; this turns
 * that decision into a conformant, signed X9.150 message and puts it in front of the payee — so a PSP
 * integrating here never builds a JWS, manages a keystore, or learns the {@code crit} rules.
 *
 * <p>The payee's answer is returned rather than interpreted. A pre-payment is a request for
 * permission and its verdict is the thing the payer must act on.
 */
@RequiredArgsConstructor
public class SendPaymentNotificationUseCase
    extends UseCase<SendPaymentNotificationInput, SendPaymentNotificationOutput> {

    private final QRCodeOutboundNotificationService outboundNotificationService;

    @Override
    public SendPaymentNotificationOutput execute(SendPaymentNotificationInput input) {
        validate(input);

        OutboundNotificationResult result = outboundNotificationService.send(
            input.notification(), input.endpoint(), input.correlationId());

        return new SendPaymentNotificationOutput(result.statusCode(), result.accepted(), result.body());
    }

    /**
     * The phase and the transaction reference have to agree, and the check is worth more than it
     * looks.
     *
     * <p>A payee infers the phase from whether a reference is present — the standard gives it nothing
     * else to go on (ADR-0004). So a pre-payment that carries one is not merely mislabelled: it
     * arrives as a <em>post</em>-payment, the QR Code is never reserved, and the payer proceeds to
     * pay against a QR Code somebody else can still claim. Catching it here costs one comparison;
     * catching it afterwards means reconciling a double payment.
     */
    private void validate(SendPaymentNotificationInput input) {
        if (isNull(input.notification()) || isNull(input.notification().payment())) {
            throw new BusinessRuleException("payment", "A notification must carry its payment.");
        }

        if (isNull(input.endpoint())) {
            throw new BusinessRuleException("endpoint",
                "The payee's notification endpoint is required; it is published in the payload the payer fetched.");
        }

        String transactionId = input.notification().payment().transactionId();

        if (PaymentPhase.PRE_PAYMENT.equals(input.phase()) && !isBlank(transactionId)) {
            throw new BusinessRuleException("payment.transactionId",
                "A pre-payment notification must not carry a transactionId: the payee infers the phase "
                    + "from its absence, so one here would be read as a payment already made.");
        }

        // Keyed on the ACTION, not the phase. NOT_SENT is a post-payment notification whose entire
        // meaning is that no transaction exists and none will — so requiring a transactionId of it
        // refused the one message a payer can send to give a reservation back, for lacking the one
        // thing it cannot have. The domain accepted it without one; this half did not, and the two
        // halves of the same deployment disagreed.
        if (PaymentPhase.POST_PAYMENT.equals(input.phase()) && isBlank(transactionId) && reportsAPayment(input)) {
            throw new BusinessRuleException("payment.transactionId",
                "A post-payment notification must carry the transactionId — the on-chain hash, or the "
                    + "End-to-End ID for an ISO 20022 rail. Reporting a payment without its reference "
                    + "gives the payee nothing to reconcile against.");
        }

        if (nonNull(input.endpoint().getScheme()) && !input.endpoint().isAbsolute()) {
            throw new BusinessRuleException("endpoint", "The notification endpoint must be an absolute URL.");
        }
    }


    /**
     * Whether this notification reports a payment that happened, as opposed to one that did not.
     *
     * <p>Only {@code NOT_SENT} says a payment did not happen. Everything else — including a
     * notification with no blockchain section at all, on a rail that has no actions — is reporting
     * money that moved and must carry its reference.
     */
    private static boolean reportsAPayment(SendPaymentNotificationInput input) {
        BlockchainDTO blockchain = input.notification().blockchain();

        return isNull(blockchain) || !ActionEnumDTO.NOT_SENT.equals(blockchain.action());
    }

}
