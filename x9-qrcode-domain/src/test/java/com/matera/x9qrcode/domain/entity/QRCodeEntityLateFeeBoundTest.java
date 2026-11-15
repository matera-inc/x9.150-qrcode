/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.entity;

import com.matera.x9qrcode.domain.AbstractTest;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.service.LateFeeBound;
import com.matera.x9qrcode.domain.vo.AdjustmentParametersVO;
import com.matera.x9qrcode.domain.vo.AdjustmentVO;
import com.matera.x9qrcode.domain.vo.AmountDueVO;
import com.matera.x9qrcode.domain.vo.AmountVO;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.CurrencyAmountVO;
import com.matera.x9qrcode.domain.vo.DescriptionVO;
import com.matera.x9qrcode.domain.vo.InvoiceVO;
import com.matera.x9qrcode.domain.vo.LateFeesVO;
import com.matera.x9qrcode.domain.vo.NetworksVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationVO;
import com.matera.x9qrcode.domain.vo.SolanaPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.TipVO;
import com.matera.x9qrcode.domain.vo.UnstructuredVO;
import com.matera.x9qrcode.domain.vo.enumerated.FormulaEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationKindEnum;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bill whose late fee could push an amount past 18 digits before the QR Code expires is refused
 * (ADR-0005).
 *
 * <p>The fee is {@code fixed + perDay × daysLate}, and it stops growing at the QR Code's
 * {@code validUntil}: the code cannot be paid after it. So the largest amount a bill can ever show is
 * known when it is created, and a bill whose largest amount would not fit the 18 digits X9.150 allows
 * is refused then, not discovered on the day the payload would carry it.
 */
class QRCodeEntityLateFeeBoundTest extends AbstractTest {

    private static final long MAX = AmountVO.MAX_AMOUNT;
    private static final String WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";

    /** A due date with a time of day and milliseconds, so no edge can hide behind midnight. */
    private static final OffsetDateTime DUE = OffsetDateTime.now(ZoneOffset.UTC)
        .plusYears(3).withHour(14).withMinute(30).withSecond(15).withNano(250_000_000);
    /** Two days and one millisecond after the due date: the formula counts three days late. */
    private static final OffsetDateTime VALID_UNTIL = DUE.plusDays(2).plus(1, ChronoUnit.MILLIS);
    private static final long DAYS = 3;

    private static QRCodeEntity create(long bill, long fixed, long perDay, OffsetDateTime validUntil,
                                       long... methodAmounts) {
        return QRCodeEntity.create(
            UUID::randomUUID, null, validUntil, CREDITOR_FIXTURE.creditor(),
            bill(bill, fixed, perDay),
            new UnstructuredVO("late fee bound"), List.of(),
            new PaymentNotificationVO(NotificationKindEnum.DEFAULT, null, null),
            methods(validUntil, methodAmounts));
    }

    static BillVO bill(long amount, long fixed, long perDay) {
        AdjustmentVO adjustment = new AdjustmentVO(FormulaEnum.FIXED_DISCOUNT_LATE_FEE_LINEAR_INTEREST,
            new AdjustmentParametersVO(null, new LateFeesVO(fixed, perDay, "late")));

        return new BillVO(
            new DescriptionVO("late fee bound"), null,
            new InvoiceVO("INV-1", DUE.toLocalDate(), DUE, null),
            TipVO.noTip(),
            new AmountDueVO(new CurrencyAmountVO(amount, "USDC"), adjustment),
            PaymentTimingEnum.IMMEDIATE);
    }

    private static List<PaymentMethodVO> methods(OffsetDateTime validUntil, long... amounts) {
        String[] currencies = {"USDC", "USD", "FRNT"};

        return java.util.stream.IntStream.range(0, amounts.length)
            .mapToObj(i -> new PaymentMethodVO(currencies[i], validUntil, new AmountVO(amounts[i]), null,
                new NetworksVO(null, null, null, new SolanaPaymentAddressVO(WALLET, null), Map.of())))
            .toList();
    }

    private static void assertRefusedNaming(String field, Runnable action) {
        BusinessRuleException refused = assertThrows(BusinessRuleException.class, action::run);

        assertEquals(field, refused.field(), refused.getMessage());
        assertTrue(refused.getMessage().contains(String.valueOf(MAX)),
            "the refusal must state the bound: " + refused.getMessage());
    }

    /**
     * <b>X9-AMT-113</b> — the largest late fee is {@code fixed + perDay × days late at validUntil},
     * the days counted exactly as the formula counts them.
     *
     * <p><b>Source:</b> Ours. ADR-0005 (owner decision 2026-11-14); INTERPRETATION I-14 for how days
     * are counted: k = ceil((t − dueDate) / 24 h), and nothing is owed at exactly {@code dueDate}.
     *
     * <p><b>Why:</b> A bound counted differently from the formula is wrong at the one instant that
     * matters: a day short and it lets through a bill whose last day overflows; a day long and it
     * refuses a bill that fits.
     */
    @Test
    void theLargestFeeIsTheFeeAtValidUntilCountedAsTheFormulaCounts() {
        LateFeesVO fees = new LateFeesVO(100L, 10L, "late");

        assertEquals(BigInteger.ZERO, LateFeeBound.largestFee(fees, DUE, DUE),
            "a QR Code that expires at its due date can never charge a late fee, not even the fixed part");
        assertEquals(BigInteger.ZERO, LateFeeBound.largestFee(fees, DUE, DUE.minusDays(1)));
        assertEquals(BigInteger.valueOf(110), LateFeeBound.largestFee(fees, DUE, DUE.plus(1, ChronoUnit.MILLIS)));
        assertEquals(BigInteger.valueOf(110), LateFeeBound.largestFee(fees, DUE, DUE.plusDays(1)));
        assertEquals(BigInteger.valueOf(120),
            LateFeeBound.largestFee(fees, DUE, DUE.plusDays(1).plus(1, ChronoUnit.MILLIS)));
        assertEquals(BigInteger.valueOf(130), LateFeeBound.largestFee(fees, DUE, VALID_UNTIL));
    }

    /**
     * <b>X9-AMT-114</b> — the bill plus its largest late fee may reach 18 nines exactly; one more
     * is refused at creation, naming {@code bill.amountDue.amount}.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.5.1 and §13.5.3.2 — amount fields have a maximum length
     * of 18. Conformance; the bound at creation is ours (ADR-0005).
     *
     * <p><b>Why:</b> The fee grows every day, so a bill that fits on the day it is issued can stop
     * fitting before it expires: the payload would then carry an amount no conforming payer can hold.
     */
    @Test
    void theBillPlusItsLargestFeeMayReachEighteenNinesAndNotOneMore() {
        long fixed = 1_000;
        long perDay = 7;
        long fee = fixed + perDay * DAYS;

        assertDoesNotThrow(() -> create(MAX - fee, fixed, perDay, VALID_UNTIL, MAX - fee));

        assertRefusedNaming("bill.amountDue.amount",
            () -> create(MAX - fee + 1, fixed, perDay, VALID_UNTIL, MAX - fee));
        assertRefusedNaming("bill.amountDue.amount",
            () -> create(MAX - fee, fixed + 1, perDay, VALID_UNTIL, MAX - fee));
        assertRefusedNaming("bill.amountDue.amount",
            () -> create(MAX - fee, fixed, perDay, VALID_UNTIL.plusDays(1), MAX - fee));
    }

    /**
     * <b>X9-AMT-115</b> — every payment method's amount is bounded too, as the payload would show
     * it, the bill's ratio {@code m × (B + largest fee) / B} rounded half up, and only so; refused
     * naming {@code paymentMethods[i].amount}.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §14.3 — maximum length 18. Conformance; the bound is ours
     * (ADR-0005).
     *
     * <p><b>Why:</b> The payload carries the late fee into each method by ratio, so a method on a
     * finer scale than the bill (USDC micro-units beside USD cents) grows faster than the bill does.
     */
    @Test
    void everyMethodIsBoundedAsThePayloadWouldShowIt() {
        long fixed = 1_000;
        long fee = fixed;

        // Same amount as the bill: the pro-rated amount is exactly amount + fee.
        assertDoesNotThrow(() -> create(MAX - fee, fixed, 0, VALID_UNTIL, MAX - fee, 1_000));
        assertRefusedNaming("paymentMethods[1].amount",
            () -> create(1_000_000, fixed, 0, VALID_UNTIL, 1_000_000, MAX - fee + 1));

        // A bill of 1000 and a fee of 1000 double every method. Half of 18 nines, rounded down,
        // doubles to 999999999999999998 and fits; one more doubles to 10^18 and does not, though
        // amount + fee alone would have fitted.
        long half = MAX / 2;
        assertDoesNotThrow(() -> create(1_000, fixed, 0, VALID_UNTIL, half));
        assertRefusedNaming("paymentMethods[0].amount", () -> create(1_000, fixed, 0, VALID_UNTIL, half + 1));
    }

    /**
     * <b>X9-AMT-116</b> — inputs whose fee overflows a 64-bit integer are refused, never wrapped.
     *
     * <p><b>Source:</b> Mechanism. {@code perDay × days} on a {@code long} wraps silently.
     *
     * <p><b>Why:</b> A wrapped fee is negative or small, so the bound would pass it and the payload
     * would carry a debt nobody asked for. Each input is legal alone (18 digits at most).
     */
    @Test
    void aFeeThatWouldOverflowALongIsRefusedNotWrapped() {
        OffsetDateTime centuriesLater = DUE.plusYears(300);

        assertEquals(BigInteger.valueOf(MAX).multiply(BigInteger.valueOf(
                LateFeeBound.daysLate(DUE, centuriesLater))).add(BigInteger.valueOf(MAX)),
            LateFeeBound.largestFee(new LateFeesVO(MAX, MAX, "late"), DUE, centuriesLater));

        assertRefusedNaming("bill.amountDue.amount", () -> create(1, MAX, MAX, centuriesLater, 1));
    }

    /**
     * <b>X9-AMT-117</b> — PATCH is held to the same bound against the merged state.
     *
     * <p><b>Source:</b> Ours. ADR-0005.
     *
     * <p><b>Why:</b> A later {@code validUntil}, a larger fee or a larger amount each raise the
     * largest amount; a bound kept only at creation is undone by the next edit.
     */
    @Test
    void theBoundIsCheckedAgainstTheMergedStateOnPatch() {
        long fixed = 1_000;
        long fee = fixed;
        QRCodeEntity qr = create(MAX - fee, fixed, 0, VALID_UNTIL, MAX - fee);

        assertDoesNotThrow(qr::requireLateFeeWithinEighteenDigits);

        qr.updateBill(bill(MAX - fee, fixed + 1, 0));

        assertRefusedNaming("bill.amountDue.amount", qr::requireLateFeeWithinEighteenDigits);
    }

    /** A bill with no late-fee adjustment is not this rule's business, at any amount. */
    @Test
    void aBillWithNoAdjustmentIsNotBounded() {
        BillVO noAdjustment = new BillVO(new DescriptionVO("plain"), null, null, TipVO.noTip(),
            new AmountDueVO(new CurrencyAmountVO(MAX, "USDC"), null), PaymentTimingEnum.IMMEDIATE);

        assertDoesNotThrow(() -> QRCodeEntity.create(
            UUID::randomUUID, null, VALID_UNTIL, CREDITOR_FIXTURE.creditor(), noAdjustment,
            new UnstructuredVO("plain"), List.of(),
            new PaymentNotificationVO(NotificationKindEnum.DEFAULT, null, null), methods(VALID_UNTIL, MAX)));
    }

}
