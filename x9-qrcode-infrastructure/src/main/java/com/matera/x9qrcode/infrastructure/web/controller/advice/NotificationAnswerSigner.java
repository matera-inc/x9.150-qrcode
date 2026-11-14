/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.advice;

import com.matera.x9qrcode.app.dto.SignatureInputDataDTO;
import com.matera.x9qrcode.app.dto.enumerated.SignatureTypeEnumDTO;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.INTERNAL_ERROR;
import static java.util.Objects.nonNull;

/**
 * Builds the payee's answer to a payment notification: a signed JWS, for a yes and for a no.
 *
 * <p>ANSI X9.150 A.4.5 notes that the answer's JWS body should be {@code {"statusCode": integer}},
 * so the payer holds the payee's decision signed, and because a JWS cannot have an empty body. The
 * payer's signed question and this signed answer, linked by {@code correlationId}, are the evidence
 * of what was authorized and when (Story 2.3).
 *
 * <p>A refusal keeps its HTTP status and today's error fields: the problem the
 * {@link GlobalControllerAdvice} would have rendered is rendered here, by the same handler, and
 * signed with {@code statusCode} added. One set of refusal rules, two encodings.
 *
 * <p>The answers the controller never writes, a request Spring refuses before it runs and an
 * exception nothing maps, are signed here too, through {@link PaymentNotificationAnswerExceptionResolver}
 * (Story 2.3 follow-up). Only a failure to sign leaves an answer unsigned.
 */
@Slf4j
@Component
public class NotificationAnswerSigner {

    public static final MediaType APPLICATION_JOSE = MediaType.parseMediaType("application/jose");

    private static final String STATUS_CODE = "statusCode";

    private final QRCodeSignatureService qrCodeSignatureService;
    private final GlobalControllerAdvice globalControllerAdvice;
    private final ExceptionHandlerMethodResolver handlers =
        new ExceptionHandlerMethodResolver(GlobalControllerAdvice.class);

    public NotificationAnswerSigner(QRCodeSignatureService qrCodeSignatureService,
                                    GlobalControllerAdvice globalControllerAdvice) {
        this.qrCodeSignatureService = qrCodeSignatureService;
        this.globalControllerAdvice = globalControllerAdvice;
    }

    /** The signed yes: body {@code {"statusCode": 200}}, correlationId echoing the question's. */
    public ResponseEntity<String> accepted(UUID correlationId) {
        return answer(HttpStatus.OK.value(), Map.of(STATUS_CODE, HttpStatus.OK.value()), correlationId);
    }

    /**
     * The signed no. The refusal is mapped exactly as the advice maps it everywhere else. One that
     * Spring raised itself carries its own problem (a 415, a 406); anything else nothing maps is a
     * signed 500 that says nothing about its cause.
     *
     * @param correlationId the question's, once its signature verified; otherwise a fresh one,
     *                      because an unverified question has no correlationId worth echoing
     */
    public ResponseEntity<String> refused(Exception refusal, UUID correlationId) {
        ProblemDetail problem = problemFor(refusal);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(STATUS_CODE, problem.getStatus());
        if (nonNull(problem.getType())) {
            body.put("type", problem.getType().toString());
        }
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("detail", problem.getDetail());
        if (nonNull(problem.getInstance())) {
            body.put("instance", problem.getInstance().toString());
        }
        if (nonNull(problem.getProperties())) {
            body.putAll(problem.getProperties());
        }

        return answer(problem.getStatus(), body, correlationId);
    }

    private ResponseEntity<String> answer(int status, Map<String, Object> body, UUID correlationId) {
        String jws = qrCodeSignatureService.signData(
            new SignatureInputDataDTO(SignatureTypeEnumDTO.X9, body, correlationId, null, status)).jwsToken();

        return ResponseEntity.status(status).contentType(APPLICATION_JOSE).body(jws);
    }

    private ProblemDetail problemFor(Exception refusal) {
        Method handler = handlers.resolveMethodByExceptionType(refusal.getClass());

        if (nonNull(handler)) {
            try {
                return (ProblemDetail) handler.invoke(globalControllerAdvice, refusal);
            } catch (IllegalAccessException | InvocationTargetException e) {
                log.error("Could not map a payment notification refusal to a problem", e);

                return internalError(refusal);
            }
        }

        if (refusal instanceof ErrorResponse spring) {
            // Raised by Spring before the controller ran (an unsupported Content-Type, a 405, a 406).
            // Its problem is already the one Spring would have rendered; only the encoding changes.
            log.warn("Payment notification refused by Spring before the controller: {}", refusal.getMessage());

            return spring.getBody();
        }

        return internalError(refusal);
    }

    private static ProblemDetail internalError(Exception cause) {
        log.error("Unexpected failure answering a payment notification; answering a signed 500", cause);

        ProblemDetail problem = ProblemDetail.forStatus(INTERNAL_ERROR.status());
        problem.setTitle(INTERNAL_ERROR.title());
        problem.setType(INTERNAL_ERROR.uriType());
        problem.setDetail(INTERNAL_ERROR.description());

        return problem;
    }
}
