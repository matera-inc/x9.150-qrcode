/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.advice;

import com.matera.x9qrcode.app.exception.EntityNotFoundException;
import com.matera.x9qrcode.app.exception.InvalidSignatureException;
import com.matera.x9qrcode.app.exception.NotificationUndeliverableException;
import com.matera.x9qrcode.app.exception.PayloadRefusedException;
import com.matera.x9qrcode.app.exception.PayloadUnreachableException;
import com.matera.x9qrcode.app.exception.ServiceException;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.exception.QRCodePreconditionFailedException;
import com.matera.x9qrcode.domain.exception.QRCodeStatusConflictException;
import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.stream.Collectors;

import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.BUSINESS_RULE;
import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.CONSTRAINT_VALIDATION;
import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.HTTP_MESSAGE_NOT_READABLE;
import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.INVALID_SIGNATURE;
import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.NOTIFICATION_UNDELIVERABLE;
import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.PAYLOAD_REFUSED;
import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.PAYLOAD_UNREACHABLE;
import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.INVALID_HTTP_HEADER;
import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.METHOD_ARGUMENT_NOT_VALID;
import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.RESOURCE_NOT_FOUND;
import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.PRECONDITION_FAILED;
import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.STATUS_CONFLICT;
import static org.apache.commons.lang3.StringUtils.isBlank;

@Slf4j
@ControllerAdvice
public class GlobalControllerAdvice {

    private static final String VIOLATIONS_MESSAGE_PATTERN = "%s: %s";
    private static final String VIOLATIONS_PROPERTY = "violations";
    private static final String CURRENT_STATUS_PROPERTY = "currentStatus";
    private static final String CURRENT_REVISION_PROPERTY = "currentRevision";
    private static final String INVALID_PROPERTY_VIOLATION = "%s has invalid value.";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        logExceptionStacktrace(ex);

        ProblemDetail problemDetail = ProblemDetail.forStatus(METHOD_ARGUMENT_NOT_VALID.status());
        problemDetail.setTitle(METHOD_ARGUMENT_NOT_VALID.title());
        problemDetail.setType(METHOD_ARGUMENT_NOT_VALID.uriType());
        problemDetail.setDetail(METHOD_ARGUMENT_NOT_VALID.description());

        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();

        List<String> violations = fieldErrors.stream()
            .map(error -> String.format(VIOLATIONS_MESSAGE_PATTERN, error.getField(), error.getDefaultMessage()))
            .collect(Collectors.toList());

        problemDetail.setProperty(VIOLATIONS_PROPERTY, violations);

        return problemDetail;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        logExceptionStacktrace(ex);

        ProblemDetail problemDetail = ProblemDetail.forStatus(CONSTRAINT_VALIDATION.status());
        problemDetail.setTitle(CONSTRAINT_VALIDATION.title());
        problemDetail.setType(CONSTRAINT_VALIDATION.uriType());
        problemDetail.setDetail(CONSTRAINT_VALIDATION.description());

        List<String> violations = ex.getConstraintViolations().stream()
            .map(cv -> VIOLATIONS_MESSAGE_PATTERN.formatted(cv.getPropertyPath(), cv.getMessage()))
            .toList();

        return createArgumentNotValidProblemDetail(violations);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        logExceptionStacktrace(ex);

        ProblemDetail problemDetail = ProblemDetail.forStatus(HTTP_MESSAGE_NOT_READABLE.status());
        problemDetail.setTitle(HTTP_MESSAGE_NOT_READABLE.title());
        problemDetail.setType(HTTP_MESSAGE_NOT_READABLE.uriType());
        problemDetail.setDetail(HTTP_MESSAGE_NOT_READABLE.description());

        return problemDetail;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        logExceptionStacktrace(ex);

        ProblemDetail problemDetail = ProblemDetail.forStatus(METHOD_ARGUMENT_NOT_VALID.status());
        problemDetail.setTitle(METHOD_ARGUMENT_NOT_VALID.title());
        problemDetail.setType(METHOD_ARGUMENT_NOT_VALID.uriType());
        problemDetail.setDetail(METHOD_ARGUMENT_NOT_VALID.description());
        problemDetail.setProperty(VIOLATIONS_PROPERTY, INVALID_PROPERTY_VIOLATION.formatted(ex.getPropertyName()));

        return problemDetail;
    }

    @ExceptionHandler(InvalidSignatureException.class)
    public ProblemDetail handleInvalidSignatureException(InvalidSignatureException ex) {
        // Deliberately does not echo the exception message: the caller is unauthenticated and the
        // detail could describe our verification internals. The reason is in the log, not the body.
        log.warn("Rejected a request whose JWS did not verify: {}", ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatus(INVALID_SIGNATURE.status());
        problemDetail.setTitle(INVALID_SIGNATURE.title());
        problemDetail.setType(INVALID_SIGNATURE.uriType());
        problemDetail.setDetail(INVALID_SIGNATURE.description());

        return problemDetail;
    }

    @ExceptionHandler(QRCodeStatusConflictException.class)
    public ProblemDetail handleQRCodeStatusConflictException(QRCodeStatusConflictException ex) {
        logExceptionStacktrace(ex);

        ProblemDetail problemDetail = ProblemDetail.forStatus(STATUS_CONFLICT.status());
        problemDetail.setTitle(STATUS_CONFLICT.title());
        problemDetail.setType(STATUS_CONFLICT.uriType());
        problemDetail.setDetail(STATUS_CONFLICT.description());
        problemDetail.setProperty(CURRENT_STATUS_PROPERTY, ex.getCurrentStatus().value());
        problemDetail.setProperty(VIOLATIONS_PROPERTY, List.of(ex.getMessage()));

        return problemDetail;
    }

    /**
     * A conditional request whose precondition no longer holds.
     *
     * <p>412 rather than 409 because that is what the precondition failed, not the transition:
     * RFC 9110 reserves 412 for exactly this, and an HTTP client library will already understand it.
     * Both the status and the revision found are reported, so the caller can retake the decision
     * without a second request.
     */
    @ExceptionHandler(QRCodePreconditionFailedException.class)
    public ProblemDetail handleQRCodePreconditionFailedException(QRCodePreconditionFailedException ex) {
        logExceptionStacktrace(ex);

        ProblemDetail problemDetail = ProblemDetail.forStatus(PRECONDITION_FAILED.status());
        problemDetail.setTitle(PRECONDITION_FAILED.title());
        problemDetail.setType(PRECONDITION_FAILED.uriType());
        problemDetail.setDetail(PRECONDITION_FAILED.description());
        problemDetail.setProperty(CURRENT_STATUS_PROPERTY, ex.getCurrentStatus().value());
        problemDetail.setProperty(CURRENT_REVISION_PROPERTY, ex.getCurrentRevision());
        problemDetail.setProperty(VIOLATIONS_PROPERTY, List.of(ex.getMessage()));

        return problemDetail;
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail handleBusinessRuleException(BusinessRuleException ex) {
        logExceptionStacktrace(ex);

        ProblemDetail problemDetail = ProblemDetail.forStatus(BUSINESS_RULE.status());
        problemDetail.setTitle(BUSINESS_RULE.title());
        problemDetail.setType(BUSINESS_RULE.uriType());
        problemDetail.setDetail(BUSINESS_RULE.description());

        String field = ex.field();

        String violation = isBlank(field)
            ? ex.getMessage()
            : String.format(VIOLATIONS_MESSAGE_PATTERN, field, ex.getMessage());

        problemDetail.setProperty(VIOLATIONS_PROPERTY, List.of(violation));

        return problemDetail;
    }

    @ExceptionHandler(ValueObjectRuleException.class)
    public ProblemDetail handleValueObjectRuleException(ValueObjectRuleException ex) {
        logExceptionStacktrace(ex);

        return createArgumentNotValidProblemDetail(ex.getMessage());
    }

    /**
     * The payee's server did not answer, which is not the caller's fault.
     *
     * <p>Declared ahead of the {@link ServiceException} handler it inherits from, because that one
     * answers 400 — and telling a payer their request was malformed when the truth is that somebody
     * else is down would send them looking in the wrong place.
     */
    @ExceptionHandler(NotificationUndeliverableException.class)
    public ProblemDetail handleNotificationUndeliverableException(NotificationUndeliverableException ex) {
        logExceptionStacktrace(ex);

        ProblemDetail problemDetail = ProblemDetail.forStatus(NOTIFICATION_UNDELIVERABLE.status());
        problemDetail.setTitle(NOTIFICATION_UNDELIVERABLE.title());
        problemDetail.setType(NOTIFICATION_UNDELIVERABLE.uriType());
        problemDetail.setDetail(NOTIFICATION_UNDELIVERABLE.description());

        return problemDetail;
    }

    /**
     * A decode whose payload the payee's service refused to release.
     *
     * <p>The payee's own status and reason are relayed rather than replaced. A decoder that answers
     * the same thing for "this bill is already paid" and "we could not reach anybody" is telling a
     * payer to retry in the one case where retrying is the wrong move, and leaving them to shrug at
     * a tampered code.
     *
     * <p>The {@code violations} here were written by whichever host the scanned QR Code names, and
     * the {@code type} says so. They are a quotation, not this service's own finding.
     */
    @ExceptionHandler(PayloadRefusedException.class)
    public ProblemDetail handlePayloadRefusedException(PayloadRefusedException ex) {
        logExceptionStacktrace(ex);

        ProblemDetail problemDetail = ProblemDetail.forStatus(ex.upstreamStatus());
        problemDetail.setTitle(PAYLOAD_REFUSED.title());
        problemDetail.setType(PAYLOAD_REFUSED.uriType());
        problemDetail.setDetail(PAYLOAD_REFUSED.description());
        problemDetail.setProperty(VIOLATIONS_PROPERTY, ex.violations());

        return problemDetail;
    }

    /**
     * A decode that never got an answer. The one case where "try again" is the right advice.
     *
     * <p>No address in the body: see {@link PayloadUnreachableException}.
     */
    @ExceptionHandler(PayloadUnreachableException.class)
    public ProblemDetail handlePayloadUnreachableException(PayloadUnreachableException ex) {
        logExceptionStacktrace(ex);

        ProblemDetail problemDetail = ProblemDetail.forStatus(PAYLOAD_UNREACHABLE.status());
        problemDetail.setTitle(PAYLOAD_UNREACHABLE.title());
        problemDetail.setType(PAYLOAD_UNREACHABLE.uriType());
        problemDetail.setDetail(PAYLOAD_UNREACHABLE.description());

        return problemDetail;
    }

    @ExceptionHandler(ServiceException.class)
    public ProblemDetail handleGatewayException(ServiceException ex) {
        logExceptionStacktrace(ex);

        return createArgumentNotValidProblemDetail(ex.getMessage());
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleEntityNotFoundException(EntityNotFoundException ex) {
        logExceptionStacktrace(ex);

        ProblemDetail problemDetail = ProblemDetail.forStatus(RESOURCE_NOT_FOUND.status());
        problemDetail.setTitle(RESOURCE_NOT_FOUND.title());
        problemDetail.setType(RESOURCE_NOT_FOUND.uriType());
        problemDetail.setDetail(RESOURCE_NOT_FOUND.description());
        problemDetail.setProperty(VIOLATIONS_PROPERTY, List.of(ex.getMessage()));

        return problemDetail;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingRequestHeaderException(MissingRequestHeaderException ex) {
        logExceptionStacktrace(ex);

        ProblemDetail problemDetail = ProblemDetail.forStatus(INVALID_HTTP_HEADER.status());
        problemDetail.setTitle(INVALID_HTTP_HEADER.title());
        problemDetail.setType(INVALID_HTTP_HEADER.uriType());
        problemDetail.setDetail(INVALID_HTTP_HEADER.description());
        problemDetail.setProperty(VIOLATIONS_PROPERTY, List.of(ex.getMessage()));

        return problemDetail;
    }

    private void logExceptionStacktrace(Exception ex) {
        log.error("An {} was thrown", ex.getClass().getSimpleName(), ex);
    }

    private ProblemDetail createArgumentNotValidProblemDetail(String violation) {
        return createArgumentNotValidProblemDetail(List.of(violation));
    }

    private ProblemDetail createArgumentNotValidProblemDetail(List<String> violations) {
        ProblemDetail problemDetail = ProblemDetail.forStatus(METHOD_ARGUMENT_NOT_VALID.status());
        problemDetail.setTitle(METHOD_ARGUMENT_NOT_VALID.title());
        problemDetail.setType(METHOD_ARGUMENT_NOT_VALID.uriType());
        problemDetail.setDetail(METHOD_ARGUMENT_NOT_VALID.description());
        problemDetail.setProperty(VIOLATIONS_PROPERTY, violations);

        return problemDetail;
    }

}
