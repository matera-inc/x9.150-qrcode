/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.service;

import com.matera.x9qrcode.domain.dto.FormulaResultDTO;
import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;
import com.matera.x9qrcode.domain.vo.AmountVO;
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
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

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
        return new AdjustmentParametersVO(discounts, new LateFeesVO(FIXED_FEE, PER_DAY, "late"));
    }

    private FormulaResultDTO at(OffsetDateTime when, AdjustmentParametersVO parameters) {
        return formula.calculate(when, DUE_DATE, FACE, "USDC", parameters, null);
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

    // ------------------------------- the advertised validUntil is the edge (Story 2.6)

    private static OffsetDateTime plusMs(OffsetDateTime instant, long millis) {
        return instant.plus(millis, ChronoUnit.MILLIS);
    }

    /** S2.6-AC1, AC4: fetched before E = dueDate − N days, the discount is advertised until exactly E. */
    @Test
    void aDiscountIsAdvertisedUntilExactlyTheInstantItStopsApplying() {
        OffsetDateTime eligibilityEnds = DUE_DATE.minusDays(10);
        AdjustmentParametersVO parameters = parameters(List.of(new DiscountVO(10, DISCOUNT, "early")));

        FormulaResultDTO hoursBefore = at(eligibilityEnds.minusHours(3), parameters);

        assertNotNull(hoursBefore);
        assertEquals(FACE - DISCOUNT, hoursBefore.amount());
        assertEquals(eligibilityEnds, hoursBefore.validUntil(),
            "the discount must be advertised until E itself, not the end of E's calendar day");
    }

    /** S2.6-AC1, AC4: the discount still applies at E and no longer 1 ms after: one edge, not two. */
    @Test
    void aDiscountAppliesAtItsEdgeAndNotOneMillisecondAfter() {
        OffsetDateTime eligibilityEnds = DUE_DATE.minusDays(10);
        AdjustmentParametersVO parameters = parameters(List.of(new DiscountVO(10, DISCOUNT, "early")));

        FormulaResultDTO atTheEdge = at(eligibilityEnds, parameters);

        assertNotNull(atTheEdge);
        assertEquals(FACE - DISCOUNT, atTheEdge.amount());
        assertEquals(eligibilityEnds, atTheEdge.validUntil());

        assertNull(at(plusMs(eligibilityEnds, 1), parameters), "1 ms past E the full amount is owed");
    }

    /** S2.6-AC3: of several discounts the largest eligible is served, advertised until its own E. */
    @Test
    void ofSeveralDiscountsTheLargestEligibleIsServedUntilItsOwnEdge() {
        DiscountVO earlyAndLarge = new DiscountVO(30, 2 * DISCOUNT, "early");
        DiscountVO laterAndSmaller = new DiscountVO(10, DISCOUNT, "later");
        AdjustmentParametersVO parameters = parameters(List.of(laterAndSmaller, earlyAndLarge));

        OffsetDateTime largeEnds = DUE_DATE.minusDays(30);
        OffsetDateTime smallEnds = DUE_DATE.minusDays(10);

        FormulaResultDTO beforeBoth = at(largeEnds.minusHours(1), parameters);
        assertEquals(FACE - 2 * DISCOUNT, beforeBoth.amount());
        assertEquals(largeEnds, beforeBoth.validUntil());

        FormulaResultDTO afterTheLarge = at(plusMs(largeEnds, 1), parameters);
        assertEquals(FACE - DISCOUNT, afterTheLarge.amount());
        assertEquals(smallEnds, afterTheLarge.validUntil());
    }

    /** S2.6-AC3: the largest discount wins even when a smaller one would last longer. */
    @Test
    void theLargestEligibleDiscountWinsAndCarriesItsOwnEdge() {
        DiscountVO lateButLarge = new DiscountVO(10, 3 * DISCOUNT, "large");
        DiscountVO earlyButSmall = new DiscountVO(30, DISCOUNT, "small");

        FormulaResultDTO result = at(DUE_DATE.minusDays(40),
            parameters(List.of(earlyButSmall, lateButLarge)));

        assertEquals(FACE - 3 * DISCOUNT, result.amount());
        assertEquals(DUE_DATE.minusDays(10), result.validUntil());
    }

    /** S2.6-AC2, AC4: k days late, the fee is advertised until exactly dueDate + k × 24 h. */
    @Test
    void aLateFeeIsAdvertisedUntilTheNextTwentyFourHourBoundaryFromTheDueDate() {
        FormulaResultDTO firstDay = at(DUE_DATE.plusHours(5), parameters(null));

        assertEquals(FACE + PER_DAY, firstDay.amount());
        assertEquals(DUE_DATE.plusDays(1), firstDay.validUntil(),
            "the first day's fee must be advertised until dueDate + 24 h, not the end of the calendar day");

        FormulaResultDTO thirdDay = at(DUE_DATE.plusDays(2).plusMinutes(1), parameters(null));

        assertEquals(FACE + 3 * PER_DAY, thirdDay.amount());
        assertEquals(DUE_DATE.plusDays(3), thirdDay.validUntil());
    }

    /** S2.6-AC2, AC4: the fee for k days applies at dueDate + k × 24 h, and the next one 1 ms later. */
    @Test
    void aLateFeeAppliesAtItsBoundaryAndRisesOneMillisecondAfter() {
        OffsetDateTime boundary = DUE_DATE.plusDays(1);

        FormulaResultDTO atTheBoundary = at(boundary, parameters(null));
        assertEquals(FACE + PER_DAY, atTheBoundary.amount());
        assertEquals(boundary, atTheBoundary.validUntil());

        FormulaResultDTO justAfter = at(plusMs(boundary, 1), parameters(null));
        assertEquals(FACE + 2 * PER_DAY, justAfter.amount());
        assertEquals(DUE_DATE.plusDays(2), justAfter.validUntil());
    }

    /** S2.6-AC1, AC2: the advertised validUntil is capped by whatever ends sooner. */
    @Test
    void theAdvertisedValidUntilIsCappedByAnEarlierEnd() {
        OffsetDateTime eligibilityEnds = DUE_DATE.minusDays(10);
        FormulaResultDTO result = at(eligibilityEnds.minusDays(5),
            parameters(List.of(new DiscountVO(10, DISCOUNT, "early"))));

        OffsetDateTime methodEnds = eligibilityEnds.minusDays(2);

        assertEquals(methodEnds, result.cappedAt(methodEnds).validUntil());
        assertEquals(eligibilityEnds, result.cappedAt(eligibilityEnds.plusDays(1)).validUntil(),
            "a later cap leaves the edge alone");
        assertEquals(result.amount(), result.cappedAt(methodEnds).amount());
    }

    // ------------------------------- the fee stops at validUntil, and fits 18 digits (ADR-0005)

    /**
     * <b>X9-AMT-118</b> — the late fee stops growing at the QR Code's {@code validUntil}.
     *
     * <p><b>Source:</b> Ours. ADR-0005 (owner decision 2026-11-14): the QR Code cannot be paid after
     * it, so a fee for a later instant is a fee nobody can owe.
     *
     * <p><b>Why:</b> A payer may ask for the payload at any future {@code dateForPayment}. Without the
     * stop, a date years away grew the fee past what was checked at creation, and past 18 digits.
     */
    @Test
    void theLateFeeStopsGrowingAtValidUntil() {
        OffsetDateTime validUntil = DUE_DATE.plusDays(2).plusHours(1);

        FormulaResultDTO yearsLater = formula.calculate(DUE_DATE.plusYears(40), DUE_DATE, FACE, "USDC",
            parameters(null), validUntil);

        assertEquals(FACE + 3 * PER_DAY, yearsLater.amount(), "the fee owed at validUntil, not 40 years on");
        assertEquals(FACE + 3 * PER_DAY,
            formula.calculate(validUntil, DUE_DATE, FACE, "USDC", parameters(null), validUntil).amount());
        assertNull(formula.calculate(DUE_DATE.plusDays(1), DUE_DATE, FACE, "USDC", parameters(null), DUE_DATE),
            "a QR Code that expires at its due date never owes a late fee");
    }

    /**
     * <b>X9-AMT-119</b> — the formula never returns an amount past 18 digits, and never wraps: it
     * refuses.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.5.1, §13.5.3.2 — maximum length 18. Defence in depth
     * behind X9-AMT-114: a stored bill that slipped past the creation bound is refused at payload
     * time rather than signed into a non-conformant payload.
     *
     * <p><b>Why:</b> {@code perDay × days} on a {@code long} wrapped silently, so a large enough fee
     * came out negative and the payload asked for less than the bill.
     */
    @Test
    void theFormulaRefusesAnAmountPastEighteenDigitsAndNeverWraps() {
        long max = AmountVO.MAX_AMOUNT;
        AdjustmentParametersVO huge = new AdjustmentParametersVO(null, new LateFeesVO(max, max, "late"));

        assertThrowsExactly(ValueObjectRuleException.class,
            () -> formula.calculate(DUE_DATE.plusYears(30), DUE_DATE, 1L, "USDC", huge, null),
            "a fee that overflows a long is refused, not wrapped");

        AdjustmentParametersVO fits = new AdjustmentParametersVO(null, new LateFeesVO(0L, 1L, "late"));
        FormulaResultDTO exactly = formula.calculate(DUE_DATE.plusHours(1), DUE_DATE, max - 1, "USDC", fits, null);
        assertEquals(max, exactly.amount(), "18 nines is still served");

        assertThrowsExactly(ValueObjectRuleException.class,
            () -> formula.calculate(DUE_DATE.plusDays(1).plusHours(1), DUE_DATE, max - 1, "USDC", fits, null),
            "one more is refused");
    }

}
