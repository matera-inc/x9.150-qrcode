/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.app.usecase.sendpaymentnotification.PaymentPhase;
import com.matera.x9qrcode.app.usecase.sendpaymentnotification.SendPaymentNotificationInput;
import com.matera.x9qrcode.app.usecase.sendpaymentnotification.SendPaymentNotificationOutput;
import com.matera.x9qrcode.app.usecase.sendpaymentnotification.SendPaymentNotificationUseCase;
import com.matera.x9qrcode.infrastructure.generated.api.PaymentNotificationApi;
import com.matera.x9qrcode.infrastructure.generated.dto.OutboundPaymentNotificationDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.OutboundPaymentNotificationResultDTO;
import com.matera.x9qrcode.infrastructure.web.controller.mapper.request.SendPaymentNotificationRequestMapper;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payer's side of the two-phase flow.
 *
 * <p>Everything else in this service serves the payee: it mints QR Codes and receives notifications
 * about them. These two endpoints serve the other party — the software that is about to pay one.
 *
 * <p>What they buy is that a PSP never builds a JWS. It composes the notification as JSON, says
 * which half of the payment it is announcing, and this signs with the deployment's own X9
 * certificate and delivers. Keystores, {@code crit} headers and thumbprints stay on this side of the
 * line.
 *
 * <p>The payee's verdict is passed back untouched. A pre-payment is a request for permission, and
 * interpreting the answer here would only oblige the caller to unpick it again — they are the one
 * who decides whether to move money.
 */
@RestController
@RequiredArgsConstructor
public class OutboundPaymentNotificationController implements PaymentNotificationApi {

    private final SendPaymentNotificationUseCase sendPaymentNotificationUseCase;

    @Override
    public ResponseEntity<OutboundPaymentNotificationResultDTO> sendPrePaymentNotification(
        OutboundPaymentNotificationDTO outboundPaymentNotificationDTO) {

        return ResponseEntity.ok(send(PaymentPhase.PRE_PAYMENT, outboundPaymentNotificationDTO));
    }

    @Override
    public ResponseEntity<OutboundPaymentNotificationResultDTO> sendPostPaymentNotification(
        OutboundPaymentNotificationDTO outboundPaymentNotificationDTO) {

        return ResponseEntity.ok(send(PaymentPhase.POST_PAYMENT, outboundPaymentNotificationDTO));
    }

    /**
     * Note the 200 above: it reports that the payee <em>answered</em>, not that they agreed. Their
     * own verdict is in {@code accepted}, and a refusal is a successful round trip. A transport
     * failure is the case that does not reach here at all — it becomes a 502.
     */
    private OutboundPaymentNotificationResultDTO send(PaymentPhase phase, OutboundPaymentNotificationDTO request) {
        SendPaymentNotificationInput input = SendPaymentNotificationRequestMapper.map(phase, request);

        SendPaymentNotificationOutput output = sendPaymentNotificationUseCase.execute(input);

        return new OutboundPaymentNotificationResultDTO()
            .statusCode(output.statusCode())
            .accepted(output.accepted())
            .body(output.body());
    }

}
