/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.advice;

import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Signs the answers to the payment-notification endpoint that the controller never writes itself.
 *
 * <p>The controller signs every yes and every no it decides (Story 2.3). Some requests are refused
 * before it runs: Spring rejects an empty or unreadable body while reading the argument, and a wrong
 * {@code Content-Type} or {@code Accept} while choosing the handler, which is before any advice tied
 * to the controller applies. An exception raised after the controller returns has no handler either.
 * Each of those reaches the payer, and an unsigned answer cannot be told from one forged on the way,
 * so they are signed too (Story 2.3 follow-up), with the same {@link NotificationAnswerSigner} and
 * the same mapping from exception to problem.
 *
 * <p>No question was verified on this path, so the answer goes out under a fresh {@code correlationId}.
 *
 * <p>It runs ahead of Spring's own resolvers and answers only for the payment-notification path;
 * every other request falls through to them untouched. If signing itself fails, the exception falls
 * through too, and Spring answers as it would have: unsigned is better than no answer.
 */
@Slf4j
@Component
public class PaymentNotificationAnswerExceptionResolver implements HandlerExceptionResolver, Ordered {

    private final NotificationAnswerSigner notificationAnswerSigner;
    private final X9Properties x9Properties;

    public PaymentNotificationAnswerExceptionResolver(NotificationAnswerSigner notificationAnswerSigner,
                                                      X9Properties x9Properties) {
        this.notificationAnswerSigner = notificationAnswerSigner;
        this.x9Properties = x9Properties;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception ex) {
        if (!isPaymentNotification(request) || response.isCommitted()) {
            return null;
        }

        ResponseEntity<String> answer;
        try {
            answer = notificationAnswerSigner.refused(ex, UUID.randomUUID());
        } catch (RuntimeException signingFailed) {
            log.error("Could not sign the answer to a payment notification refused outside the controller", signingFailed);

            return null;
        }

        try {
            response.resetBuffer();
            response.setStatus(answer.getStatusCode().value());
            response.setContentType(NotificationAnswerSigner.APPLICATION_JOSE.toString());
            // A compact JWS is base64url and dots: ASCII, so the writer's charset cannot alter it.
            response.getOutputStream().write(
                Objects.requireNonNullElse(answer.getBody(), "").getBytes(StandardCharsets.US_ASCII));
            response.flushBuffer();
        } catch (IOException | IllegalStateException e) {
            log.error("Could not write the signed answer to a payment notification", e);

            return null;
        }

        return new ModelAndView();
    }

    /** Ahead of Spring's resolvers (which sit at order 0 and below), behind Boot's error-attribute recorder. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    private boolean isPaymentNotification(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();

        if (Objects.nonNull(contextPath) && !contextPath.isEmpty() && Objects.nonNull(path) && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }

        return Objects.equals(path, x9Properties.getPublicEndpoints().getPaymentNotificationPath());
    }
}
