/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.service;

import com.matera.x9qrcode.domain.vo.LateFeesVO;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * How large a late fee can grow, counted exactly as the formula counts it (ADR-0005).
 *
 * <p>A late fee is {@code fixed + perDay × daysLate}, and {@code daysLate} has no bound of its own.
 * It stops growing at the QR Code's {@code validUntil}, because the code cannot be paid after it, so
 * the largest fee a bill can ever show is known when the bill is created. Everything here is
 * computed in {@link BigInteger}: the bound must hold for inputs whose product does not fit a
 * {@code long}, and a wrapped product is exactly the error it exists to catch.
 */
public final class LateFeeBound {

    private LateFeeBound() {
    }

    /**
     * Days late at {@code at}: ceil((at − dueDate) / 24 h), on the difference truncated to the
     * millisecond. Zero on or before the due date. Overflow-free for any two instants.
     */
    public static long daysLate(OffsetDateTime dueDate, OffsetDateTime at) {
        if (!at.isAfter(dueDate)) {
            return 0;
        }

        Duration late = Duration.between(dueDate, at).truncatedTo(ChronoUnit.MILLIS);
        long whole = late.toDays();

        return late.minusDays(whole).isZero() ? whole : whole + 1;
    }

    /** {@code fixed + perDay × daysLate}, exactly. */
    public static BigInteger fee(LateFeesVO lateFees, long daysLate) {
        return BigInteger.valueOf(lateFees.perDay())
            .multiply(BigInteger.valueOf(daysLate))
            .add(BigInteger.valueOf(lateFees.fixed()));
    }

    /**
     * The largest late fee a bill can show before {@code validUntil}: the fee owed at
     * {@code validUntil} itself. Zero when the QR Code expires on or before its due date, since the
     * formula charges nothing, not even the fixed part, until strictly after it.
     */
    public static BigInteger largestFee(LateFeesVO lateFees, OffsetDateTime dueDate, OffsetDateTime validUntil) {
        if (!validUntil.isAfter(dueDate)) {
            return BigInteger.ZERO;
        }

        return fee(lateFees, daysLate(dueDate, validUntil));
    }

    /**
     * A payment method's amount with the bill's adjustment carried to it by ratio, rounded half up:
     * what the payload serves (currencies on one QR Code share a peg, not a scale).
     */
    public static BigInteger prorated(long methodAmount, long billAmount, BigInteger adjustedBillAmount) {
        return new BigDecimal(BigInteger.valueOf(methodAmount).multiply(adjustedBillAmount))
            .divide(BigDecimal.valueOf(billAmount), 0, RoundingMode.HALF_UP)
            .toBigIntegerExact();
    }

}
