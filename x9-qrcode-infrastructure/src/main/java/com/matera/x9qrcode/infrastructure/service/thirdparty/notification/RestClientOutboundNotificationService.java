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
import com.matera.x9qrcode.app.dto.enumerated.AnswerSignatureEnumDTO;
import com.matera.x9qrcode.app.dto.enumerated.SignatureProblemEnumDTO;
import com.matera.x9qrcode.app.dto.enumerated.SignatureTypeEnumDTO;
import com.matera.x9qrcode.app.exception.NotificationUndeliverableException;
import com.matera.x9qrcode.app.service.QRCodeOutboundNotificationService;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationInput;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationOutput;

import com.nimbusds.jose.JWSObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.text.ParseException;
import java.util.Map;
import java.util.UUID;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

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
 *
 * <h2>The answer is checked, and the check is reported</h2>
 *
 * <p>ANSI X9.150 A.4.5 asks the payee to answer with a signed {@code {"statusCode": integer}}. The
 * answer is verified against the payee's X9 certificate and the correlationId sent, and the outcome
 * travels with it as {@code signature} (Story 2.3, S2.3-AC6). Nothing here refuses on that basis:
 * an unsigned yes is still a yes, and what it is worth is the payer's call.
 */
@Slf4j
@RequiredArgsConstructor
public class RestClientOutboundNotificationService implements QRCodeOutboundNotificationService {

    private static final String APPLICATION_JOSE = "application/jose";
    private static final String STATUS_CODE = "statusCode";

    private final RestClient notificationRestClient;
    private final QRCodeSignatureService qrCodeSignatureService;

    @Override
    public OutboundNotificationResult send(PaymentNotificationDataDTO notification,
                                           URI endpoint,
                                           UUID correlationId) {

        UUID callCorrelationId = nonNull(correlationId) ? correlationId : UUID.randomUUID();

        log.info("Sending payment notification to {} with correlationId {}", endpoint, callCorrelationId);

        String jws = sign(notification, callCorrelationId);

        ResponseEntity<String> response;

        try {
            response = notificationRestClient.post()
                .uri(endpoint)
                .contentType(MediaType.parseMediaType(APPLICATION_JOSE))
                .body(jws)
                .retrieve()
                // A refused notification is an answer, not a transport failure. Suppressing the
                // default error handling is what lets the payee's own verdict reach the payer.
                .onStatus(status -> true, (request, errorResponse) -> { })
                .toEntity(String.class);
        } catch (Exception unreachable) {
            throw new NotificationUndeliverableException(
                "Could not deliver the payment notification to %s".formatted(endpoint), unreachable);
        }

        return checkAnswer(response.getStatusCode().value(), response.getBody(), callCorrelationId);
    }

    /**
     * Checks the payee's answer against ANSI X9.150 A.4.5: a JWS signed with the payee's X9 key,
     * whose body is {@code {"statusCode": integer}} and whose {@code correlationId} is the one sent.
     *
     * <p>Reports, never refuses. An answer that is not a JWS at all is {@code ABSENT}; one that is
     * presented as a JWS and fails any check is {@code INVALID}. In both cases the HTTP status is
     * what is reported, so a 200 stays an accepted, unsigned yes, and the payer decides what an
     * unsigned yes is worth. Only a {@code VERIFIED} answer reports the status the payee signed.
     */
    private OutboundNotificationResult checkAnswer(int httpStatus, String body, UUID sentCorrelationId) {
        if (isBlank(body)) {
            return unsigned(httpStatus, body, SignatureProblemEnumDTO.ABSENT_BODY, "the answer has no body");
        }

        // The shared verifier names what the body is when it is not a JWS (NOT_JWS, ALG_NONE,
        // ENCRYPTED), and every check a JWS can fail, each with its fixed code.
        SignatureValidationOutput verified;
        try {
            verified = qrCodeSignatureService.validateSignature(
                new SignatureValidationInput(SignatureTypeEnumDTO.X9, sentCorrelationId, body.trim(), null));
        } catch (Exception e) {
            verified = SignatureValidationOutput.invalidSignature(SignatureProblemEnumDTO.UNVERIFIABLE, e.getMessage());
        }

        if (!verified.isValid()) {
            return unsigned(httpStatus, body,
                isNull(verified.problem()) ? SignatureProblemEnumDTO.UNVERIFIABLE : verified.problem(),
                isBlank(verified.problemDetail()) ? "the signature did not verify" : verified.problemDetail());
        }

        Integer signedStatus = signedStatusCode(body.trim());

        if (isNull(signedStatus)) {
            return unsigned(httpStatus, body, SignatureProblemEnumDTO.STATUS_MISSING,
                "the signed body has no integer statusCode");
        }

        if (signedStatus != httpStatus) {
            return unsigned(httpStatus, body, SignatureProblemEnumDTO.STATUS_MISMATCH,
                "statusCode mismatch: HTTP %d, signed %d".formatted(httpStatus, signedStatus));
        }

        return new OutboundNotificationResult(signedStatus, body, AnswerSignatureEnumDTO.VERIFIED, null, null);
    }

    private static Integer signedStatusCode(String verifiedJws) {
        Map<String, Object> payload;
        try {
            payload = JWSObject.parse(verifiedJws).getPayload().toJSONObject();
        } catch (ParseException e) {
            return null;
        }

        if (isNull(payload) || !(payload.get(STATUS_CODE) instanceof Number number)) {
            return null;
        }

        return number.intValue();
    }

    /**
     * An answer that is not the payee's verified decision. {@code ABSENT} when there is no JWS at all
     * (an empty body, or one that is not JOSE); {@code INVALID} when the body is presented as JOSE and
     * fails a check.
     */
    private static OutboundNotificationResult unsigned(int httpStatus, String body,
                                                       SignatureProblemEnumDTO problem, String detail) {
        AnswerSignatureEnumDTO signature = switch (problem) {
            case ABSENT_BODY, NOT_JWS -> AnswerSignatureEnumDTO.ABSENT;
            default -> AnswerSignatureEnumDTO.INVALID;
        };

        log.warn("Payee answer reported as {} ({}: {}), HTTP {}", signature, problem, detail, httpStatus);

        return new OutboundNotificationResult(httpStatus, body, signature, problem, detail);
    }

    /**
     * Signs the notification in the shape the standard defines, not the shape we hold it in.
     *
     * <p>{@link OutboundPaymentNotificationPayloadMapper} exists for one reason: the application
     * record keeps the QR Code id beside the payment, while the wire format keeps it inside it.
     * What we sign has to be something a payee can actually read.
     */
    private String sign(PaymentNotificationDataDTO notification, UUID correlationId) {
        SignatureOutputDataDTO signed = qrCodeSignatureService.signData(
            new SignatureInputDataDTO(SignatureTypeEnumDTO.X9,
                OutboundPaymentNotificationPayloadMapper.map(notification), correlationId, null, null));

        return signed.jwsToken();
    }

}
