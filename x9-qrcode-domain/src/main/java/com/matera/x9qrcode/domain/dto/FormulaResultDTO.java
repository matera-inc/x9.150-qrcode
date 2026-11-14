/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.dto;

import java.time.OffsetDateTime;

import static java.util.Objects.isNull;

/**
 * An adjusted amount and the instant it stops applying.
 *
 * <p>{@code validUntil} is the exact instant the adjustment's rule changes (a discount's eligibility
 * edge, the next late-fee boundary), so what the payee signs next to the amount is what the payee
 * will accept: up to and including that instant, and not one millisecond after.
 */
public record FormulaResultDTO(
    Long amount,
    Long adjustmentAmount,
    String explanation,
    String targetCurrency,
    OffsetDateTime validUntil) {

    /**
     * The same result, advertised no later than {@code cap}: an adjusted amount cannot outlive the
     * QR Code or the payment methods it is quoted for.
     */
    public FormulaResultDTO cappedAt(OffsetDateTime cap) {
        if (isNull(cap) || isNull(validUntil) || !cap.isBefore(validUntil)) {
            return this;
        }

        return new FormulaResultDTO(amount, adjustmentAmount, explanation, targetCurrency, cap);
    }

}
