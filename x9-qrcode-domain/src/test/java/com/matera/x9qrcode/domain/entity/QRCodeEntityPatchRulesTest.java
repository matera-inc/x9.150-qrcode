/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.entity;

import com.matera.x9qrcode.domain.AbstractTest;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.vo.AdjustmentParametersVO;
import com.matera.x9qrcode.domain.vo.AdjustmentVO;
import com.matera.x9qrcode.domain.vo.AmountDueVO;
import com.matera.x9qrcode.domain.vo.AmountVO;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.CurrencyAmountVO;
import com.matera.x9qrcode.domain.vo.DescriptionVO;
import com.matera.x9qrcode.domain.vo.DiscountVO;
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
import com.matera.x9qrcode.domain.utils.BusinessClock;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The owner's PATCH rules of 2026-11-15, on the entity: a reservation is cut back to an earlier
 * {@code validUntil}; a date a PATCH changed is not already past; the merged tip is held to the
 * create's rule; a currency named twice is refused; and {@code sentAt} is never before
 * {@code revisedAt}. The Go build has the same rules (internal/paymentrequest, internal/billing).
 */
class QRCodeEntityPatchRulesTest extends AbstractTest {

    private static final String WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";

    @AfterEach
    void resetClock() {
        BusinessClock.reset();
    }

    private static OffsetDateTime now() {
        return DateTimeUtils.nowUTC().truncatedTo(ChronoUnit.MILLIS);
    }

    private static BillVO bill(TipVO tip, InvoiceVO invoice, AdjustmentVO adjustment) {
        return new BillVO(new DescriptionVO("patch rules"), null, invoice, tip,
            new AmountDueVO(new CurrencyAmountVO(1_000L, "USDC"), adjustment), PaymentTimingEnum.IMMEDIATE);
    }

    private static InvoiceVO invoice(OffsetDateTime dueDate) {
        return new InvoiceVO("INV-1", LocalDate.of(2020, 1, 1), dueDate, null);
    }

    private static AdjustmentVO discounts(int... daysBefore) {
        List<DiscountVO> list = Arrays.stream(daysBefore).mapToObj(days -> new DiscountVO(days, 1L, "d")).toList();
        return new AdjustmentVO(FormulaEnum.FIXED_DISCOUNT_LATE_FEE_LINEAR_INTEREST,
            new AdjustmentParametersVO(list, new LateFeesVO(0L, 0L, "none")));
    }

    private static PaymentMethodVO method(OffsetDateTime validUntil) {
        return new PaymentMethodVO("USDC", validUntil, new AmountVO(1_000L), null,
            new NetworksVO(null, null, null, new SolanaPaymentAddressVO(WALLET, null), Map.of()));
    }

    private QRCodeEntity qrCode(OffsetDateTime validUntil, BillVO bill) {
        return QRCodeEntity.create(UUID::randomUUID, null, validUntil, CREDITOR_FIXTURE.creditor(), bill,
            new UnstructuredVO("patch rules"), List.of(),
            new PaymentNotificationVO(NotificationKindEnum.DEFAULT, null, null), List.of(method(validUntil)));
    }

    private QRCodeEntity qrCode(OffsetDateTime validUntil) {
        return qrCode(validUntil, bill(TipVO.noTip(), null, null));
    }

    private static void assertRefused(Runnable patch, String field, String message) {
        BusinessRuleException refused = assertThrows(BusinessRuleException.class, patch::run);
        assertEquals(field, refused.field());
        assertEquals(message, refused.getMessage());
    }

    /**
     * <b>X9-PATCH-044</b> — a PATCH that moves {@code validUntil} before the end of a running
     * reservation cuts the reservation back to it; a later {@code validUntil} never lengthens it.
     *
     * <p><b>Source:</b> Ours. ADR-0019 ("a reservation cannot outlive the thing reserved"), applied
     * to PATCH by the owner on 2026-11-15.
     *
     * <p><b>Why:</b> The window was capped by the {@code validUntil} it was stamped under. Moved
     * earlier, the QR Code would read {@code PAYMENT_INITIATED} after its own expiry, and a payee
     * reconciling then would wait for a payment that can no longer come.
     */
    @Test
    void anEarlierValidUntilCutsTheReservationBack() {
        QRCodeEntity qrCode = qrCode(now().plusDays(30));
        qrCode.initiatePayment(null, Duration.ofSeconds(90));
        OffsetDateTime window = qrCode.getInitiatedExpiresAt();

        qrCode.updateValidUntil(window.plusHours(1));
        assertEquals(window, qrCode.getInitiatedExpiresAt(), "a later validUntil never lengthens the window");

        OffsetDateTime cut = window.minusSeconds(60);
        qrCode.updateValidUntil(cut);
        assertEquals(cut, qrCode.getInitiatedExpiresAt());
    }

    /**
     * <b>X9-PATCH-045</b> — a reservation that never lapses (a hash registered, or no window) is
     * left alone by a PATCH that moves {@code validUntil} earlier.
     *
     * <p><b>Source:</b> Ours. ADR-0036: once the window has stopped, only the payment system ends
     * the reservation.
     *
     * <p><b>Why:</b> Cutting the sentinel would let the reservation lapse on time alone, handing a
     * QR Code whose money may already have moved to the next payer.
     */
    @Test
    void aReservationThatNeverLapsesIsLeftAlone() {
        QRCodeEntity qrCode = qrCode(now().plusDays(30));
        qrCode.initiatePayment(null, Duration.ZERO);

        qrCode.updateValidUntil(now().plusMinutes(1));

        assertEquals(QRCodeEntity.NO_LAPSE, qrCode.getInitiatedExpiresAt());
    }

    /**
     * <b>X9-PATCH-046</b> — {@code sentAt} is the instant the JSON is generated, but never earlier
     * than {@code revisedAt}.
     *
     * <p><b>Source:</b> X9.150 §6: {@code sentAt} SHALL be greater than or equal to
     * {@code revisedAt}. The instant is ours (INTERPRETATION I-17, owner 2026-11-15).
     *
     * <p><b>Why:</b> A clock behind the instant of the last revision (a test clock moved back, or
     * a replica whose clock lags) would otherwise sign a payload that breaks the standard.
     */
    @Test
    void sentAtIsNowButNeverBeforeRevisedAt() {
        QRCodeEntity qrCode = qrCode(now().plusDays(30));
        OffsetDateTime revisedAt = qrCode.getRevisedAt();

        assertEquals(revisedAt.plusMinutes(1), qrCode.sentAt(revisedAt.plusMinutes(1)));
        assertEquals(revisedAt, qrCode.sentAt(revisedAt));
        assertEquals(revisedAt, qrCode.sentAt(revisedAt.minusHours(1)));
    }

    /**
     * <b>X9-PATCH-047</b> — a date the PATCH changed must not be past on the payee's clock, in the
     * create's words, the edge included; a date left as it was is not judged again.
     *
     * <p><b>Source:</b> Ours. The create's rules (X9-LIFE-420 family), applied to what a PATCH
     * changes by the owner on 2026-11-15.
     *
     * <p><b>Why:</b> A PATCH could set a {@code validUntil}, a due date or a discount already in the
     * past, which a create refuses; but re-judging an untouched date would stop a biller from
     * extending a QR Code that has expired.
     */
    @Test
    void aDateThePatchChangedIsNotPast() {
        OffsetDateTime start = now();
        BusinessClock.setAt(start.toInstant());
        OffsetDateTime due = start.plusDays(10);
        QRCodeEntity qrCode = qrCode(start.plusDays(30), bill(TipVO.noTip(), invoice(due), discounts(5)));
        QRCodeEntity expired = qrCode(start.plusDays(30), bill(TipVO.noTip(), invoice(due), discounts(5)));
        OffsetDateTime validUntil = qrCode.getValidUntil();
        BillVO stored = qrCode.getBill();

        BusinessClock.setAt(start.plusDays(6).toInstant());
        // The stored discount's edge (due − 5 days) is past now and left alone: not judged again.
        assertDoesNotThrow(() -> qrCode.requireChangedDatesNotPast(validUntil, stored));

        OffsetDateTime at = DateTimeUtils.nowUTC();
        qrCode.updateBill(bill(TipVO.noTip(), invoice(due), discounts(5, 9)));
        assertRefused(() -> qrCode.requireChangedDatesNotPast(validUntil, stored),
            "bill.amountDue.adjustments.discounts[1]", "target date must be after the current date.");

        qrCode.updateBill(bill(TipVO.noTip(), invoice(at.minusSeconds(1)), null));
        assertRefused(() -> qrCode.requireChangedDatesNotPast(validUntil, stored),
            "bill.invoice.dueDate", "must be after or equal to actual date.");

        qrCode.updateBill(stored);
        qrCode.updateValidUntil(at.minusSeconds(1));
        assertRefused(() -> qrCode.requireChangedDatesNotPast(validUntil, stored),
            "validUntil", "must not be less than the current date.");

        // Past everything: the dates left as they were are not judged again.
        BusinessClock.setAt(start.plusDays(40).toInstant());
        assertDoesNotThrow(() -> expired.requireChangedDatesNotPast(expired.getValidUntil(), expired.getBill()));
    }

    /**
     * <b>X9-PATCH-048</b> — the merged tip is held to the create's rule: allowed with neither a
     * range nor presets is refused, in the create's words.
     *
     * <p><b>Source:</b> Ours. ADR-0017, INTERPRETATION I-10; applied to PATCH by the owner on
     * 2026-11-15.
     *
     * <p><b>Why:</b> A tip "allowed" with nothing to choose from is an offer no payer's app can
     * show; a create refused it while a PATCH let it in.
     */
    @Test
    void theMergedTipIsHeldToTheCreatesRule() {
        QRCodeEntity qrCode = qrCode(now().plusDays(30));

        assertRefused(() -> qrCode.updateBill(bill(TipVO.of(true, null), null, null)),
            "tip.allow", "should inform range or presets when tip is allowed");
        assertDoesNotThrow(() -> qrCode.updateBill(bill(TipVO.of(true, List.of(10)), null, null)));
    }

    /**
     * <b>X9-PATCH-049</b> — a PATCH that names a currency twice is refused naming it, in the
     * create's words.
     *
     * <p><b>Source:</b> Ours (ADR-0018 family). Story 9.7 found it; the owner chose Go's answer.
     *
     * <p><b>Why:</b> The last of the two was kept in silence, and the caller could not know which
     * amount the QR Code now asks for.
     */
    @Test
    void aCurrencyNamedTwiceIsRefused() {
        QRCodeEntity qrCode = qrCode(now().plusDays(30));

        assertRefused(() -> qrCode.requirePaymentMethodCurrencies(List.of("USDC", "USDC")),
            "paymentMethods", "must not contain duplicated currency: USDC");
    }
}
