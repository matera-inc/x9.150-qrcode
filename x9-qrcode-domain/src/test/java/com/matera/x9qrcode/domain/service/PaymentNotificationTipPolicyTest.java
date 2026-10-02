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
import com.matera.x9qrcode.domain.vo.AmountDueVO;
import com.matera.x9qrcode.domain.vo.AmountVO;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.BlockchainVO;
import com.matera.x9qrcode.domain.vo.CryptoWalletPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.CurrencyAmountVO;
import com.matera.x9qrcode.domain.vo.AmountRangeVO;
import com.matera.x9qrcode.domain.vo.EditableAmountVO;
import com.matera.x9qrcode.domain.vo.DescriptionVO;
import com.matera.x9qrcode.domain.vo.NetworksVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationPaymentVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationVO;
import com.matera.x9qrcode.domain.vo.SolanaPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.TipVO;
import com.matera.x9qrcode.domain.vo.UnstructuredVO;
import com.matera.x9qrcode.domain.vo.enumerated.ActionEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationKindEnum;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tip is money ON TOP of the bill, never a slice of it.
 *
 * <p>ANSI X9.150-2026 §13.6.2: <i>"The computed total (amount + tip) SHALL apply only to the payment
 * instruction sent to the payment network and related payment notification"</i>. So
 * {@code payment.amount} is the whole transfer and the merchant's share is {@code amount - tipAmount}.
 *
 * <p>Before these rules existed the bill was compared against the total, which failed in both
 * directions at once: a payer who correctly tipped ON TOP was refused, and a payer who paid the tip
 * OUT OF the merchant's share was accepted and the QR Code marked as fully paid while the merchant
 * was short by exactly the tip.
 */
class PaymentNotificationTipPolicyTest extends AbstractTest {

    private static final String WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final String MEMO = "{QRCD:\"01A0E3A12805CB382EF4687F18CDC43A\"}";
    private static final long BILL = 1_000L;

    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);
    private static final OffsetDateTime VALID_UNTIL = NOW.plusDays(30);

    private final PaymentNotificationAcceptancePolicy policy =
        new PaymentNotificationAcceptancePolicy(new FormulaFactory(Map.of()));

    private QRCodeEntity qrCodeTipping(TipVO tip) {
        BillVO bill = new BillVO(
            new DescriptionVO("dinner"), null, null, tip,
            new AmountDueVO(new CurrencyAmountVO(BILL, "USDC"), null),
            PaymentTimingEnum.IMMEDIATE);

        return QRCodeEntity.create(
            UUID::randomUUID, null, VALID_UNTIL, CREDITOR_FIXTURE.creditor(), bill,
            new UnstructuredVO("tip test"), List.of(),
            new PaymentNotificationVO(NotificationKindEnum.DEFAULT, null, null),
            List.of(new PaymentMethodVO(
                "USDC", VALID_UNTIL, new AmountVO(BILL), null,
                new NetworksVO(null, null, null, new SolanaPaymentAddressVO(WALLET, MEMO), Map.of()))));
    }

    private static PaymentNotificationDataVO notification(long amount, Long tipAmount) {
        CryptoWalletPaymentAddressVO wallet = new CryptoWalletPaymentAddressVO(WALLET);

        return new PaymentNotificationDataVO(
            new PaymentNotificationPaymentVO(
                new AmountVO(amount),
                tipAmount == null ? null : new AmountVO(tipAmount),
                "USDC", "solana", null),
            null, null,
            new BlockchainVO(ActionEnum.PAYMENT_INITIATED, wallet, wallet));
    }

    // ------------------------------------------------------------------ the tip is extra

    /**
     * <b>X9-TIP-001</b> — the bill plus a tip is accepted.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §2.1 — <i>"Total amount sent for payment"</i> — with §13.6.2 defining that
     * total as <i>amount + tip</i>. Conformance.
     *
     * <p><b>Why:</b> 1000 owed, 200 tipped, 1200 transferred. This deployment used to compare the TOTAL against
     * the bill and refuse it, so a payer who tipped exactly as the bill invited them to was turned
     * away.
     */
    @Test
    void theBillPlusTheTipIsAccepted() {
        // The whole point: 1000 owed, 200 tipped, 1200 transferred.
        assertDoesNotThrow(() -> policy.accept(
            qrCodeTipping(TipVO.of(true, 0, 50, null)), notification(BILL + 200, 200L), NOW));
    }

    /**
     * <b>X9-TIP-002</b> — a tip paid out of the merchant's share is refused.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §2.1 with §13.6.2 — the notification carries the total, tip included, so the
     * merchant's share is <code>amount - tipAmount</code>. Conformance.
     *
     * <p><b>Why:</b> 1000 arrives carrying a 200 tip, so the merchant receives 800 on a 1000 bill. Before this
     * rule the QR Code was marked paid in full and nothing anywhere recorded that the merchant was
     * short by exactly the tip. A consumer totalling payments across the QR Codes of one payment
     * request counts that bill as settled.
     */
    @Test
    void aTipPaidOutOfTheMerchantsShareIsRefused() {
        // The money-losing case. 1000 arrives carrying a 200 tip, so the merchant gets 800 on a
        // 1000 bill — and before this rule the QR Code was marked paid in full.
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCodeTipping(TipVO.of(true, 0, 50, null)), notification(BILL, 200L), NOW));

        assertTrue(exception.field().contains("amount"), exception.field());
        assertTrue(exception.getMessage().contains("800"),
            "the reason should show the merchant's actual share: " + exception.getMessage());
    }

    /**
     * <b>X9-TIP-003</b> — a bill with no tip is unaffected.
     *
     * <p><b>Source:</b> Mechanism. Nothing in the standard is at stake; this is the control.
     *
     * <p><b>Why:</b> Without it, every refusal in this class would still pass if the policy refused all payments
     * outright. A suite of refusals with no acceptance case cannot tell "correct" from "broken shut".
     */
    @Test
    void aBillWithNoTipIsUnaffected() {
        assertDoesNotThrow(() -> policy.accept(
            qrCodeTipping(TipVO.noTip()), notification(BILL, null), NOW));
    }

    /**
     * <b>X9-TIP-004</b> — a tip larger than the transfer carrying it is refused.
     *
     * <p><b>Source:</b> Ours. The standard does not contemplate it. See ADR-0017.
     *
     * <p><b>Why:</b> The merchant's share would be negative, which is not a payment at all. Refusing it here means
     * the arithmetic downstream never has to ask what a negative settlement means.
     */
    @Test
    void aTipLargerThanTheWholeTransferIsRefused() {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCodeTipping(TipVO.of(true, 0, 999, null)),
                notification(BILL, BILL + 1), NOW));

        assertTrue(exception.field().contains("tipAmount"), exception.field());
    }

    // --------------------------------------------------------- the bill decides if tips are taken

    /**
     * <b>X9-TIP-005</b> — a tip on a bill that refuses tips is refused.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.6.1 — <i>"If false, the payment application SHOULD NOT allow the Payer
     * to add a tip."</i> That SHOULD is addressed to the PAYER's application. Enforcing it again at
     * the payee is <b>ours</b> — INTERPRETATION I-10.
     *
     * <p><b>Why:</b> A rule that lives only in the counterparty's client is not a rule. Before this, a tip on a
     * bill that refused tipping was accepted in silence and the money kept.
     */
    @Test
    void aTipIsRefusedWhenTheBillDoesNotAllowOne() {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCodeTipping(TipVO.noTip()), notification(BILL + 200, 200L), NOW));

        assertTrue(exception.field().contains("tipAmount"), exception.field());
        assertTrue(exception.getMessage().contains("does not accept tips"), exception.getMessage());
    }

    /**
     * <b>X9-TIP-006</b> — a bill cannot exist without tip information.
     *
     * <p><b>Source:</b> Mechanism. BillVO refuses a null tip, so a bill created without one is stored as
     * TipVO.noTip().
     *
     * <p><b>Why:</b> It is why the tip gate never has to answer "what if there is no tip block", and why the
     * payload can promise that omitting <code>tip</code> and sending <code>{"allowed": false}</code>
     * are the same state.
     */
    @Test
    void aBillCannotBeBuiltWithoutTipInformationAtAll() {
        // Why the tip gate never has to answer "what if there is no tip block": BillVO refuses one.
        // A bill created without a tip is stored as TipVO.noTip(), so "tips not offered" and "tips
        // refused" really are the same state, which is what the payload documentation promises.
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> qrCodeTipping(null));

        assertTrue(exception.field().contains("tip"), exception.field());
    }

    // ----------------------------------------------------------------- the range is the rule

    /**
     * <b>X9-TIP-007</b> — a tip inside the published range is accepted.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 A.10 — <i>"Payer-facing applications SHOULD validate any user-entered or
     * preset-selected tip such that min ≤ tip ≤ max"</i>. Enforcing it at the payee is <b>ours</b>,
     * I-10.
     *
     * <p><b>Why:</b> The acceptance case for the range. Without it the two refusals below would pass against an
     * implementation that refused every tip.
     */
    @Test
    void aTipInsideThePublishedRangeIsAccepted() {
        // 0..25% of 1000 = 0..250
        assertDoesNotThrow(() -> policy.accept(
            qrCodeTipping(TipVO.of(true, 0, 25, List.of(10, 15, 20))), notification(BILL + 250, 250L), NOW));
    }

    /**
     * <b>X9-TIP-008</b> — a tip above the published maximum is refused.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 A.10 — <i>"Payer-facing applications SHOULD validate any
     * user-entered or preset-selected tip such that min ≤ tip ≤ max"</i>. That SHOULD is addressed
     * to the payer's application; enforcing it at the payee is <b>ours</b> — INTERPRETATION I-10.
     *
     * <p><b>Why:</b> 30% on a bill offering 0..25%. The reason names the published range and the
     * figure received, so a payer app can show the payer what it may send rather than guessing.
     */
    @Test
    void aTipAboveThePublishedRangeIsRefused() {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCodeTipping(TipVO.of(true, 0, 25, null)),
                notification(BILL + 300, 300L), NOW));

        assertTrue(exception.field().contains("tipAmount"), exception.field());
        assertTrue(exception.getMessage().contains("25%"),
            "the reason should name the published range: " + exception.getMessage());
    }

    /**
     * <b>X9-TIP-009</b> — a tip below the published minimum is refused.
     *
     * <p><b>Source:</b> A.10 as above; enforcement at the payee is ours (I-10).
     *
     * <p><b>Why:</b> The range binds in both directions. A biller publishing a 10% floor means it.
     */
    @Test
    void aTipBelowThePublishedMinimumIsRefused() {
        // min 10% of 1000 = 100
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCodeTipping(TipVO.of(true, 10, 25, null)),
                notification(BILL + 50, 50L), NOW));

        assertTrue(exception.field().contains("tipAmount"), exception.field());
    }

    /**
     * <b>X9-TIP-010</b> — an absurd tip is refused rather than accepted.
     *
     * <p><b>Source:</b> A.10 as above; enforcement at the payee is ours (I-10).
     *
     * <p><b>Why:</b> A tip of 999999 on a 1000 bill was accepted before the range was enforced. The shape of
     * mistake this catches is a payer app sending minor units where it meant percent.
     */
    @Test
    void anAbsurdTipIsRefusedRatherThanAccepted() {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCodeTipping(TipVO.of(true, 0, 25, null)),
                notification(BILL + 999_999L, 999_999L), NOW));

        assertTrue(exception.field().contains("tipAmount"), exception.field());
    }

    // ------------------------------------------------- the percentage is of what is being paid

    /**
     * <b>X9-TIP-013</b> — on an editable amount the percentage is of what is being paid.
     *
     * <p><b>Source:</b> Ours. ADR-0017. §13.6.2's note is a MAY addressed to the payer's application,
     * and for an editable amount {@code amountDue} is a figure the payer is invited to override.
     *
     * <p><b>Why:</b> An invoice of 1000 settleable from 400, offering 10%. Taking the percentage
     * against the FACE amount let a payer settling 400 tip 100 — a quarter of what they were actually
     * paying. The payer computes the tip from the amount they chose, so that is the basis, and it is
     * the same number the bill is judged on.
     */
    @Test
    void onAnEditableAmountThePercentageIsOfWhatIsBeingPaid() {
        QRCodeEntity qrCode = qrCodeTipping(TipVO.of(true, 0, 10, List.of(10)));
        qrCode.updatePaymentMethods(List.of(new PaymentMethodVO(
            "USDC", VALID_UNTIL, new AmountVO(BILL),
            new EditableAmountVO(new AmountRangeVO(400L, BILL)),
            new NetworksVO(null, null, null, new SolanaPaymentAddressVO(WALLET, MEMO), Map.of()))));

        assertDoesNotThrow(() -> policy.accept(qrCode, notification(440, 40L), NOW));

        QRCodeEntity other = qrCodeTipping(TipVO.of(true, 0, 10, List.of(10)));
        other.updatePaymentMethods(List.of(new PaymentMethodVO(
            "USDC", VALID_UNTIL, new AmountVO(BILL),
            new EditableAmountVO(new AmountRangeVO(400L, BILL)),
            new NetworksVO(null, null, null, new SolanaPaymentAddressVO(WALLET, MEMO), Map.of()))));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(other, notification(500, 100L), NOW));

        assertTrue(exception.field().contains("tipAmount"), exception.field());
    }

    /**
     * <b>X9-TIP-014</b> — a tip one minor unit outside its bound is tolerated.
     *
     * <p><b>Source:</b> Ours. ADR-0017.
     *
     * <p><b>Why:</b> The bound is a percentage of an integer amount, so it is a rounded product. A
     * payer computing the same percentage — possibly after converting from another currency, rounding
     * again, under no obligation to round as we do — can land one unit either side having done nothing
     * wrong. Refusing that declines a correct payment over a cent nobody could have avoided.
     */
    @Test
    void aTipOneMinorUnitOutsideItsBoundIsTolerated() {
        // 10% of 1000 is 100; 101 is one unit over.
        assertDoesNotThrow(() -> policy.accept(
            qrCodeTipping(TipVO.of(true, 0, 10, List.of(10))), notification(BILL + 101, 101L), NOW));
    }

    /**
     * <b>X9-TIP-015</b> — a tip two minor units outside its bound is refused.
     *
     * <p><b>Source:</b> Ours. ADR-0017.
     *
     * <p><b>Why:</b> The limit of X9-TIP-014. The tolerance absorbs rounding, which is one unit; it is
     * not a licence to exceed the range the biller published. Without this the tolerance could be
     * widened later and nothing would notice.
     */
    @Test
    void aTipTwoMinorUnitsOutsideItsBoundIsRefused() {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCodeTipping(TipVO.of(true, 0, 10, List.of(10))),
                notification(BILL + 102, 102L), NOW));

        assertTrue(exception.field().contains("tipAmount"), exception.field());
    }

    // ------------------------------------------------------------- presets suggest, they do not bind

    /**
     * <b>X9-TIP-011</b> — presets do not bind when no range is published.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 A.10 validates <i>"any user-entered or <b>preset-selected</b> tip"</i>
     * against min..max — so a preset is itself checked against the range, never an independent
     * whitelist. Reading presets as advisory is <b>ours</b>, I-10.
     *
     * <p><b>Why:</b> 13% is offered by no preset and is a legitimate thing for a payer to type. The consequence,
     * stated plainly in I-10: a bill publishing presets and NO range accepts a tip of any size, so a
     * biller who wants a ceiling must publish a range.
     */
    @Test
    void aTipThatMatchesNoPresetIsAcceptedWhenNoRangeIsPublished() {
        // A.10 validates even a preset-selected tip against min..max, so the range is the rule and
        // the presets are the buttons. 13% is not offered, and is still a legitimate tip.
        assertDoesNotThrow(() -> policy.accept(
            qrCodeTipping(TipVO.of(true, List.of(10, 15, 20))), notification(BILL + 130, 130L), NOW));
    }

    /**
     * <b>X9-TIP-012</b> — a tip matching no preset is still bound by the range.
     *
     * <p><b>Source:</b> A.10 as above (ours, I-10).
     *
     * <p><b>Why:</b> The other half of TIP-011: presets being advisory does not make the range advisory. 13% is
     * accepted because it is inside 0..25%, not because presets were ignored.
     */
    @Test
    void aTipThatMatchesNoPresetIsStillBoundByTheRange() {
        assertDoesNotThrow(() -> policy.accept(
            qrCodeTipping(TipVO.of(true, 0, 25, List.of(10, 15, 20))), notification(BILL + 130, 130L), NOW));
    }
}
