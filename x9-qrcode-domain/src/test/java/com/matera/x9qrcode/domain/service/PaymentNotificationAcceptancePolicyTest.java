/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.service;

import com.matera.x9qrcode.domain.AbstractTest;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.service.factory.FormulaFactory;
import com.matera.x9qrcode.domain.vo.AdjustmentParametersVO;
import com.matera.x9qrcode.domain.vo.BlockchainVO;
import com.matera.x9qrcode.domain.vo.CryptoWalletPaymentAddressVO;
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
import com.matera.x9qrcode.domain.vo.SolanaPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationPaymentVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationVO;
import com.matera.x9qrcode.domain.vo.TipVO;
import com.matera.x9qrcode.domain.vo.UnstructuredVO;
import com.matera.x9qrcode.domain.vo.enumerated.ActionEnum;
import com.matera.x9qrcode.domain.vo.enumerated.FormulaEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationKindEnum;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acceptance rules that depend on <em>when</em> a notification arrives.
 *
 * <p>Deliberately a domain test rather than an API one. These rules are a function of time, and the
 * policy takes the instant as a parameter precisely so it can be chosen. Driving them through HTTP
 * would mean creating a QR Code whose window closes seconds later and then sleeping — which is both
 * slow and flaky, since a loaded CI runner can spend those seconds inside the create request and
 * invalidate the fixture before the test has begun. Passing the instant in tests the same rule with
 * no clock to race.
 */
class PaymentNotificationAcceptancePolicyTest extends AbstractTest {

    private static final String WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final String MEMO = "{QRCD:\"01A0E3A12805CB382EF4687F18CDC43A\"}";
    private static final long FACE = 10_000_000L;
    private static final long DISCOUNT = 1_000_000L;
    private static final long LATE_FEE = 500_000L;

    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);
    private static final OffsetDateTime DUE_DATE = NOW.plusDays(90);

    private final PaymentNotificationAcceptancePolicy policy = new PaymentNotificationAcceptancePolicy(
        new FormulaFactory(Map.of(
            FormulaEnum.FIXED_DISCOUNT_LATE_FEE_LINEAR_INTEREST,
            new FixedDiscountLateFeeLinearInterestFormulaService())));

    /** Earns a {@value #DISCOUNT} discount up to 60 days before the due date, then a late fee after it. */
    private QRCodeEntity qrCode() {
        return qrCode(DUE_DATE.plusDays(1), new LateFeesVO(LATE_FEE, 0L, "late"));
    }

    /** The same bill, valid (QR Code and payment method) until {@code validUntil}, with these late fees. */
    private QRCodeEntity qrCode(OffsetDateTime validUntil, LateFeesVO lateFees) {
        BillVO bill = new BillVO(
            new DescriptionVO("late fee and discount"),
            null,
            new InvoiceVO("INV-1", NOW.toLocalDate(), DUE_DATE, null),
            TipVO.noTip(),
            new AmountDueVO(
                new CurrencyAmountVO(FACE, "USDC"),
                new AdjustmentVO(
                    FormulaEnum.FIXED_DISCOUNT_LATE_FEE_LINEAR_INTEREST,
                    new AdjustmentParametersVO(
                        List.of(new DiscountVO(60, DISCOUNT, "early payment")),
                        lateFees))),
            PaymentTimingEnum.DEFERRED);

        return QRCodeEntity.create(
            UUID::randomUUID,
            null,
            validUntil,
            CREDITOR_FIXTURE.creditor(),
            bill,
            new UnstructuredVO("late fee test"),
            List.of(),
            new PaymentNotificationVO(NotificationKindEnum.DEFAULT, null, null),
            List.of(new PaymentMethodVO(
                "USDC", validUntil, new AmountVO(FACE), null,
                new NetworksVO(null, null, null, new SolanaPaymentAddressVO(WALLET, MEMO), Map.of()))));
    }

    /** Same QR Code, but the USDC method lapses well before the payload does. */
    private QRCodeEntity qrCodeWithMethodExpiringBefore(OffsetDateTime methodValidUntil) {
        QRCodeEntity qrCode = qrCode();

        qrCode.updatePaymentMethods(List.of(new PaymentMethodVO(
            "USDC", methodValidUntil, new AmountVO(FACE), null,
            new NetworksVO(null, null, null, new SolanaPaymentAddressVO(WALLET, MEMO), Map.of()))));

        return qrCode;
    }

    private static PaymentNotificationDataVO notificationOf(long amount) {
        return notificationOf(amount, "USDC");
    }

    private static PaymentNotificationDataVO notificationOf(long amount, String currency) {
        CryptoWalletPaymentAddressVO wallet = new CryptoWalletPaymentAddressVO(WALLET);

        return new PaymentNotificationDataVO(
            new PaymentNotificationPaymentVO(new AmountVO(amount), null, currency, "solana", null),
            null, null,
            new BlockchainVO(ActionEnum.PAYMENT_INITIATED, wallet, wallet));
    }

    // ------------------------------------------------- while the discount window is open

    /**
     * <b>X9-AMT-040</b> — the discounted amount is accepted while the window is open.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.5.2 — adjustments to the amount due. Conformance; the formulas
     * themselves are ours.
     *
     * <p><b>Why:</b> The payer fetched the payload, it quoted a discount, they paid it. The acceptance case.
     */
    @Test
    void theDiscountedAmountIsAcceptedWhileTheWindowIsOpen() {
        assertDoesNotThrow(() -> policy.accept(qrCode(), notificationOf(FACE - DISCOUNT), NOW));
    }

    /**
     * <b>X9-AMT-041</b> — the face amount is accepted while a discount is available, because it overpays.
     *
     * <p><b>Source:</b> Ours. The standard does not say what a payee does with a stale quote.
     *
     * <p><b>Why:</b> A payer holding a quote fetched before the discount applied is offering MORE than is owed.
     * Refusing that would punish somebody who did everything right, and the biller is not harmed by
     * being paid in full.
     */
    @Test
    void theFaceAmountIsAcceptedWhileTheWindowIsOpenBecauseItOverpays() {
        // A payer who fetched before the discount applied owes less than they are offering; refusing
        // that would punish someone who did everything right.
        assertDoesNotThrow(() -> policy.accept(qrCode(), notificationOf(FACE), NOW));
    }

    // ------------------------------------------------ once the discount window has closed

    /**
     * <b>X9-AMT-042</b> — an amount calculated with an expired discount is refused.
     *
     * <p><b>Source:</b> Ours, the mirror of AMT-041.
     *
     * <p><b>Why:</b> Past the window the discount is no longer earnable, so the quote the payer holds is no longer
     * the amount owed. The asymmetry is the point: overpaying is harmless, underpaying is not, and
     * the payer's remedy is to fetch the payload again.
     */
    @Test
    void anAmountCalculatedWithAnExpiredDiscountIsRefused() {
        // 30 days before the due date: past the 60-day window, so the discount is no longer earnable
        // and the quote the payer is holding is no longer the amount owed.
        OffsetDateTime afterTheWindow = DUE_DATE.minusDays(30);

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCode(), notificationOf(FACE - DISCOUNT), afterTheWindow));

        assertTrue(exception.field().contains("amount"), exception.field());
        assertTrue(exception.getMessage().contains(String.valueOf(FACE)),
                "the reason should name the amount actually owed: " + exception.getMessage());
    }

    /**
     * <b>X9-AMT-043</b> — the face amount is accepted once the discount window has closed.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> With no discount earnable, face IS the amount owed. Confirms the window closing changes what
     * is owed rather than simply refusing everything.
     */
    @Test
    void theFaceAmountIsAcceptedOnceTheDiscountWindowHasClosed() {
        assertDoesNotThrow(() -> policy.accept(qrCode(), notificationOf(FACE), DUE_DATE.minusDays(30)));
    }

    // ------------------------------------------------------- once a late fee has accrued

    /**
     * <b>X9-AMT-044</b> — the face amount is refused once a late fee has accrued.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> Past the due date the face amount is an UNDERPAYMENT. A stale quote is not a licence to pay
     * less than is owed — the same asymmetry as AMT-042, in the other direction.
     */
    @Test
    void theFaceAmountIsRefusedOnceALateFeeHasAccrued() {
        // Past the due date the face amount is an UNDERPAYMENT. A stale quote is not a licence to
        // pay less than is owed; the payer's remedy is to fetch the payload again.
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCode(), notificationOf(FACE), DUE_DATE.plusHours(1)));

        assertTrue(exception.getMessage().contains("late fee"), exception.getMessage());
    }

    /**
     * <b>X9-AMT-045</b> — the amount including the late fee is accepted.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> The acceptance case for the late-fee branch, so AMT-044 cannot pass by refusing everything
     * after the due date.
     */
    @Test
    void theAmountIncludingTheLateFeeIsAccepted() {
        assertDoesNotThrow(() -> policy.accept(qrCode(), notificationOf(FACE + LATE_FEE), DUE_DATE.plusHours(1)));
    }

    // ------------------------------------------------------- the per-currency window

    /**
     * <b>X9-AMT-046</b> — a payment method whose own window closed is refused though the payload is still valid.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §14.2 — each payment method carries its own validUntil, distinct from the
     * payload's. Conformance.
     *
     * <p><b>Why:</b> Two windows, and the narrower one governs. A biller withdrawing one rail early must not have
     * that rail kept alive by the payload's longer life.
     */
    @Test
    void aCurrencyWhoseOwnWindowClosedIsRefusedEvenThoughThePayloadIsStillValid() {
        QRCodeEntity qrCode = qrCodeWithMethodExpiringBefore(DUE_DATE.minusDays(30));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCode, notificationOf(FACE - DISCOUNT), DUE_DATE.minusDays(20)));

        assertTrue(exception.field().contains("validUntil"), exception.field());
        assertTrue(exception.getMessage().contains("expired"), exception.getMessage());
    }

    // ---------------------------------------------------------------- the payload's own window

    /**
     * <b>X9-AMT-047</b> — an expired payload is refused whatever the amount.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §11 — the payload's validUntil. Conformance.
     *
     * <p><b>Why:</b> Checked before the amount, so an expired QR Code is told it expired rather than being told its
     * amount is wrong — which would send the payer off to fix the wrong thing.
     */
    @Test
    void anExpiredPayloadIsRefusedWhateverTheAmount() {
        OffsetDateTime afterTheQRCodeExpired = DUE_DATE.plusDays(2);

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCode(), notificationOf(FACE + LATE_FEE), afterTheQRCodeExpired));

        assertTrue(exception.getMessage().contains("expired"), exception.getMessage());
    }

    // ------------------------------------------- the payee's clock decides (Story 1.3)

    /**
     * S1.3-AC1 — a discount quoted before its window closed is refused once the request reaches the
     * payee after it, and the face amount is then accepted.
     *
     * <p><b>Why:</b> {@code at} is the payee's clock at receipt. What the payer's clock said when it
     * fetched the quote does not enter into it: skew must never lock in a discount no longer granted.
     */
    @Test
    void aDiscountFetchedBeforeTheWindowClosedIsRefusedWhenTheRequestArrivesAfterIt() {
        OffsetDateTime windowEnds = DUE_DATE.minusDays(60);
        OffsetDateTime arrivesAfter = windowEnds.plusNanos(1_000_000);

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCode(), notificationOf(FACE - DISCOUNT), arrivesAfter));

        assertEquals("paymentNotification.data.payment.amount", exception.field());
        assertTrue(exception.getMessage().contains(String.valueOf(FACE - DISCOUNT)),
            "the reason should name the stale discounted amount: " + exception.getMessage());
        assertTrue(exception.getMessage().contains(String.valueOf(FACE)),
            "the reason should name the face amount now owed: " + exception.getMessage());

        assertDoesNotThrow(() -> policy.accept(qrCode(), notificationOf(FACE), arrivesAfter));
    }

    /**
     * S1.3-AC2 — a payer whose clock runs behind still sees a request arriving after validUntil (on
     * the payee's clock) refused on {@code validUntil}.
     */
    @Test
    void aRequestArrivingAfterValidUntilOnThePayeesClockIsRefused() {
        QRCodeEntity qrCode = qrCode();
        OffsetDateTime afterValidUntil = qrCode.getValidUntil().plusNanos(1_000_000);

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCode, notificationOf(FACE + LATE_FEE), afterValidUntil));

        assertEquals("validUntil", exception.field());
    }

    /** S1.3-AC3 — validUntil is inclusive: a request arriving at exactly that instant is accepted. */
    @Test
    void aRequestArrivingAtExactlyValidUntilIsAccepted() {
        QRCodeEntity qrCode = qrCode();

        // validUntil is dueDate + 24 h: exactly one day late, so one day's fee is owed.
        assertDoesNotThrow(() -> policy.accept(qrCode, notificationOf(FACE + LATE_FEE), qrCode.getValidUntil()));
    }

    /** S1.3-AC4 — at exactly dueDate the full amount is owed, with no late fee and no exception. */
    @Test
    void aRequestArrivingAtExactlyTheDueDateOwesTheFullAmount() {
        assertDoesNotThrow(() -> policy.accept(qrCode(), notificationOf(FACE), DUE_DATE));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCode(), notificationOf(FACE + LATE_FEE), DUE_DATE));

        assertEquals("paymentNotification.data.payment.amount", exception.field());
    }

    // ---------------------------- the payee honours what it signed (Story 2.6)

    /**
     * S2.6-AC1 — a pre-commit with the discounted amount is accepted at exactly E, the instant the
     * discount was advertised until, and refused 1 ms later, when the face amount is still accepted.
     */
    @Test
    void theDiscountedAmountIsAcceptedAtItsAdvertisedEdgeAndRefusedOneMillisecondAfter() {
        OffsetDateTime eligibilityEnds = DUE_DATE.minusDays(60);
        OffsetDateTime oneMsAfter = eligibilityEnds.plusNanos(1_000_000);

        assertDoesNotThrow(() -> policy.accept(qrCode(), notificationOf(FACE - DISCOUNT), eligibilityEnds));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCode(), notificationOf(FACE - DISCOUNT), oneMsAfter));
        assertEquals("paymentNotification.data.payment.amount", exception.field());

        assertDoesNotThrow(() -> policy.accept(qrCode(), notificationOf(FACE), oneMsAfter));
    }

    /**
     * S2.6-AC2 — k days late, a pre-commit with the fee advertised until dueDate + k × 24 h is
     * accepted at that instant and refused 1 ms later, when one more day is owed.
     */
    @Test
    void theLateFeeAmountIsAcceptedAtItsAdvertisedBoundaryAndRefusedOneMillisecondAfter() {
        // A per-day fee and no fixed one, so each 24 h boundary changes the amount owed.
        QRCodeEntity perDay = qrCode(DUE_DATE.plusDays(10), new LateFeesVO(0L, LATE_FEE, "late"));
        OffsetDateTime secondBoundary = DUE_DATE.plusDays(2);
        OffsetDateTime oneMsAfter = secondBoundary.plusNanos(1_000_000);

        assertDoesNotThrow(() -> policy.accept(perDay, notificationOf(FACE + 2 * LATE_FEE), secondBoundary));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(perDay, notificationOf(FACE + 2 * LATE_FEE), oneMsAfter));
        assertEquals("paymentNotification.data.payment.amount", exception.field());

        assertDoesNotThrow(() -> policy.accept(perDay, notificationOf(FACE + 3 * LATE_FEE), oneMsAfter));
    }

    // ------------------------------------------------------- what a third-party payer may send

    /**
     * <b>X9-CUR-030</b> — a notified currency matches whatever its case.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §2.3 requires ISO 4217 codes in what we EMIT. How tolerant we are of a third
     * party's spelling is ours — INTERPRETATION I-1.
     *
     * <p><b>Why:</b> Refusing a real payment because the payer's PSP sent "usdc" would be strictness with no
     * beneficiary. Lenient in what we accept, strict in what we send.
     */
    @ParameterizedTest
    @ValueSource(strings = {"USDC", "usdc", "Usdc", "uSdC"})
    void aNotifiedCurrencyMatchesWhateverTheCase(String notifiedCurrency) {
        assertDoesNotThrow(
            () -> policy.accept(qrCode(), notificationOf(FACE - DISCOUNT, notifiedCurrency), NOW));
    }

    /**
     * <b>X9-CUR-031</b> — a currency this QR Code does not offer is still refused.
     *
     * <p><b>Source:</b> Conformance — the QR Code publishes what it accepts.
     *
     * <p><b>Why:</b> The limit of CUR-030's leniency. Forgiving CASE must not become forgiving CURRENCY; a payment
     * in something never offered has no agreed rate and no agreed rail.
     */
    @Test
    void aCurrencyThisQRCodeDoesNotOfferIsStillRefused() {
        BusinessRuleException thrown = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCode(), notificationOf(FACE - DISCOUNT, "EUR"), NOW));

        assertTrue(thrown.getMessage().contains("EUR"), thrown.getMessage());
    }

}
