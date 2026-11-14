/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.service;

import com.matera.x9qrcode.domain.dto.FormulaResultDTO;
import com.matera.x9qrcode.domain.vo.AdjustmentParametersVO;
import com.matera.x9qrcode.domain.vo.DiscountVO;
import com.matera.x9qrcode.domain.vo.LateFeesVO;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The formula at the instants where its answer changes.
 *
 * <p>Every instant here is fixed and years in the future. The formula reads the wall clock only to
 * replace a payment date that is already in the past, so a future date is used exactly as given and
 * no clock has to be moved.
 */
class FixedDiscountLateFeeLinearInterestFormulaServiceTest {

    private static final long FACE = 10_000_000L;
    private static final long DISCOUNT = 1_000_000L;
    private static final long PER_DAY = 500_000L;
    private static final long FIXED_FEE = 0L;

    /** A due date with a time of day and milliseconds, so no edge can hide behind midnight. */
    private static final OffsetDateTime DUE_DATE = OffsetDateTime.now(ZoneOffset.UTC)
        .plusYears(5).withHour(14).withMinute(30).withSecond(15).withNano(250_000_000);

    private final FixedDiscountLateFeeLinearInterestFormulaService formula =
        new FixedDiscountLateFeeLinearInterestFormulaService();

    private static AdjustmentParametersVO parameters(List<DiscountVO> discounts) {
        return new AdjustmentParametersVO(discounts, new LateFeesVO(PER_DAY, FIXED_FEE, "late"));
    }

    private FormulaResultDTO at(OffsetDateTime when, AdjustmentParametersVO parameters) {
        return formula.calculate(when, DUE_DATE, FACE, "USDC", parameters);
    }

    // ------------------------------------------------------------ the due date (Story 1.3)

    /** S1.3-AC4: a payment at exactly dueDate is on time: full amount, no late fee, no exception. */
    @Test
    void aPaymentAtExactlyTheDueDateIsOnTimeForTheFullAmount() {
        FormulaResultDTO result = assertDoesNotThrow(
            () -> at(DUE_DATE, parameters(List.of(new DiscountVO(10, DISCOUNT, "early")))));

        assertNull(result, "at the due date no adjustment applies: the full amount, no late fee");
    }

    /** S1.3-AC4: the due date is on time with no discount configured too. */
    @Test
    void aPaymentAtExactlyTheDueDateIsOnTimeWithNoDiscountConfigured() {
        assertNull(assertDoesNotThrow(() -> at(DUE_DATE, parameters(null))));
    }

    /** S1.3-AC4: dueDate − 1 ms is the same as dueDate when no discount window covers it. */
    @Test
    void oneMillisecondBeforeTheDueDateIsTheFullAmountWhenNoDiscountCoversIt() {
        assertNull(at(DUE_DATE.minus(1, ChronoUnit.MILLIS),
            parameters(List.of(new DiscountVO(10, DISCOUNT, "early")))));
    }

    /** S1.3-AC4: dueDate + 1 ms owes the first day's late fee. */
    @Test
    void oneMillisecondAfterTheDueDateTheLateFeeApplies() {
        FormulaResultDTO result = at(DUE_DATE.plus(1, ChronoUnit.MILLIS), parameters(null));

        assertNotNull(result);
        assertEquals(PER_DAY + FIXED_FEE, result.adjustmentAmount());
        assertEquals(FACE + PER_DAY + FIXED_FEE, result.amount());
    }

}
