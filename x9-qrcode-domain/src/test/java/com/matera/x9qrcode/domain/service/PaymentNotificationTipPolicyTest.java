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

    @Test
    void theBillPlusTheTipIsAccepted() {
        // The whole point: 1000 owed, 200 tipped, 1200 transferred.
        assertDoesNotThrow(() -> policy.accept(
            qrCodeTipping(TipVO.of(true, 0, 50, null)), notification(BILL + 200, 200L), NOW));
    }

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

    @Test
    void aBillWithNoTipIsUnaffected() {
        assertDoesNotThrow(() -> policy.accept(
            qrCodeTipping(TipVO.noTip()), notification(BILL, null), NOW));
    }

    @Test
    void aTipLargerThanTheWholeTransferIsRefused() {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCodeTipping(TipVO.of(true, 0, 999, null)),
                notification(BILL, BILL + 1), NOW));

        assertTrue(exception.field().contains("tipAmount"), exception.field());
    }

    // --------------------------------------------------------- the bill decides if tips are taken

    @Test
    void aTipIsRefusedWhenTheBillDoesNotAllowOne() {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCodeTipping(TipVO.noTip()), notification(BILL + 200, 200L), NOW));

        assertTrue(exception.field().contains("tipAmount"), exception.field());
        assertTrue(exception.getMessage().contains("does not accept tips"), exception.getMessage());
    }

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

    @Test
    void aTipInsideThePublishedRangeIsAccepted() {
        // 0..25% of 1000 = 0..250
        assertDoesNotThrow(() -> policy.accept(
            qrCodeTipping(TipVO.of(true, 0, 25, List.of(10, 15, 20))), notification(BILL + 250, 250L), NOW));
    }

    @Test
    void aTipAboveThePublishedRangeIsRefused() {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCodeTipping(TipVO.of(true, 0, 25, null)),
                notification(BILL + 300, 300L), NOW));

        assertTrue(exception.field().contains("tipAmount"), exception.field());
        assertTrue(exception.getMessage().contains("25%"),
            "the reason should name the published range: " + exception.getMessage());
    }

    @Test
    void aTipBelowThePublishedMinimumIsRefused() {
        // min 10% of 1000 = 100
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCodeTipping(TipVO.of(true, 10, 25, null)),
                notification(BILL + 50, 50L), NOW));

        assertTrue(exception.field().contains("tipAmount"), exception.field());
    }

    @Test
    void anAbsurdTipIsRefusedRatherThanAccepted() {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
            () -> policy.accept(qrCodeTipping(TipVO.of(true, 0, 25, null)),
                notification(BILL + 999_999L, 999_999L), NOW));

        assertTrue(exception.field().contains("tipAmount"), exception.field());
    }

    // ------------------------------------------------------------- presets suggest, they do not bind

    @Test
    void aTipThatMatchesNoPresetIsAcceptedWhenNoRangeIsPublished() {
        // A.10 validates even a preset-selected tip against min..max, so the range is the rule and
        // the presets are the buttons. 13% is not offered, and is still a legitimate tip.
        assertDoesNotThrow(() -> policy.accept(
            qrCodeTipping(TipVO.of(true, List.of(10, 15, 20))), notification(BILL + 130, 130L), NOW));
    }

    @Test
    void aTipThatMatchesNoPresetIsStillBoundByTheRange() {
        assertDoesNotThrow(() -> policy.accept(
            qrCodeTipping(TipVO.of(true, 0, 25, List.of(10, 15, 20))), notification(BILL + 130, 130L), NOW));
    }
}
