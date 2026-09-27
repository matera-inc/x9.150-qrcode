/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.notification;

import com.matera.x9qrcode.app.dto.PaymentNotificationDataDTO;
import com.matera.x9qrcode.app.dto.SignatureInputDataDTO;
import com.matera.x9qrcode.app.dto.SignatureOutputDataDTO;
import com.matera.x9qrcode.app.dto.enumerated.SignatureTypeEnumDTO;
import com.matera.x9qrcode.app.exception.NotificationUndeliverableException;
import com.matera.x9qrcode.app.service.QRCodeOutboundNotificationService;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.UUID;

import static java.util.Objects.nonNull;

/**
 * Signs a payment notification and posts it to the payee.
 *
 * <p>The counterpart of the endpoint that receives them, and deliberately thin: compose, sign,
 * deliver, report back what was said. The payee's verdict is returned untouched, because a
 * pre-payment is a request for permission and the answer belongs to the payer.
 *
 * <h2>Two outcomes, kept apart</h2>
 *
 * <p>"The payee refused" and "we never reached the payee" look alike from a distance and call for
 * different things: the first is an answer to act on, the second is a delivery to retry. A refusal
 * comes back with the payee's own status; an unreachable payee raises, so the caller is never left
 * reading a fabricated status code as though somebody had sent it.
 */
@Slf4j
@RequiredArgsConstructor
public class RestClientOutboundNotificationService implements QRCodeOutboundNotificationService {

    private static final String APPLICATION_JOSE = "application/jose";

    private final RestClient notificationRestClient;
    private final QRCodeSignatureService qrCodeSignatureService;

    @Override
    public OutboundNotificationResult send(PaymentNotificationDataDTO notification,
                                           URI endpoint,
                                           UUID correlationId) {

        UUID callCorrelationId = nonNull(correlationId) ? correlationId : UUID.randomUUID();

        log.info("Sending payment notification to {} with correlationId {}", endpoint, callCorrelationId);

        String jws = sign(notification, callCorrelationId);

        try {
            ResponseEntity<String> response = notificationRestClient.post()
                .uri(endpoint)
                .contentType(MediaType.parseMediaType(APPLICATION_JOSE))
                .body(jws)
                .retrieve()
                // A refused notification is an answer, not a transport failure. Suppressing the
                // default error handling is what lets the payee's own verdict reach the payer.
                .onStatus(status -> true, (request, errorResponse) -> { })
                .toEntity(String.class);

            return new OutboundNotificationResult(response.getStatusCode().value(), response.getBody());
        } catch (Exception unreachable) {
            throw new NotificationUndeliverableException(
                "Could not deliver the payment notification to %s".formatted(endpoint), unreachable);
        }
    }

    private String sign(PaymentNotificationDataDTO notification, UUID correlationId) {
        SignatureOutputDataDTO signed = qrCodeSignatureService.signData(
            new SignatureInputDataDTO(SignatureTypeEnumDTO.X9, notification, correlationId, null, null));

        return signed.jwsToken();
    }

}
