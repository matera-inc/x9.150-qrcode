/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.advice;

import com.matera.x9qrcode.app.exception.EntityNotFoundException;
import com.matera.x9qrcode.app.exception.ServiceException;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
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
import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.INVALID_HTTP_HEADER;
import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.METHOD_ARGUMENT_NOT_VALID;
import static com.matera.x9qrcode.infrastructure.web.controller.advice.error.ErrorTypeEnum.RESOURCE_NOT_FOUND;
import static org.apache.commons.lang3.StringUtils.isBlank;

@Slf4j
@ControllerAdvice
public class GlobalControllerAdvice {

    private static final String VIOLATIONS_MESSAGE_PATTERN = "%s: %s";
    private static final String VIOLATIONS_PROPERTY = "violations";
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
