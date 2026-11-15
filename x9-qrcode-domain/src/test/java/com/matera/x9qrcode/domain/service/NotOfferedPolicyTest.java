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
import com.matera.x9qrcode.domain.vo.BankPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.BlockchainVO;
import com.matera.x9qrcode.domain.vo.CryptoWalletPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.CurrencyAmountVO;
import com.matera.x9qrcode.domain.vo.DescriptionVO;
import com.matera.x9qrcode.domain.vo.ExpectedDateVO;
import com.matera.x9qrcode.domain.vo.NetworksVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationDataVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationPayerVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationPaymentVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationVO;
import com.matera.x9qrcode.domain.vo.SolanaPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.TipVO;
import com.matera.x9qrcode.domain.vo.UnstructuredVO;
import com.matera.x9qrcode.domain.vo.enumerated.ActionEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationDeviationEnum;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationKindEnum;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
 * A notification naming a rail or a currency the QR Code does not offer (ADR-0036, amended
 * 2026-11-15 by the owner): which codes it earns, the words of the refusal, and the pre-commit
 * refusal at the acceptance gate.
 */
class NotOfferedPolicyTest extends AbstractTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);
    private static final String WALLET = "7EcDhSYGxXyscszYEp35KHN8vvw3svAuLKTzXwCFLtV";
    private static final String PAYER_WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final BankPaymentAddressVO BANK = new BankPaymentAddressVO("021000021", "1234567890");

    private final PaymentNotificationAcceptancePolicy gate =
        new PaymentNotificationAcceptancePolicy(new FormulaFactory(Map.of()));

    /** A QR Code offering USD on exactly one bank rail. */
    private QRCodeEntity offeringOnly(String rail) {
        NetworksVO networks = switch (rail) {
            case "fednow" -> new NetworksVO(BANK, null, null, null, Map.of());
            case "ach" -> new NetworksVO(null, BANK, null, null, Map.of());
            case "rtp" -> new NetworksVO(null, null, BANK, null, Map.of());
            default -> throw new IllegalArgumentException(rail);
        };

        OffsetDateTime validUntil = NOW.plusDays(30);

        return QRCodeEntity.create(
            UUID::randomUUID, null, validUntil, CREDITOR_FIXTURE.creditor(),
            new BillVO(new DescriptionVO("not offered"), null, null, TipVO.noTip(),
                new AmountDueVO(new CurrencyAmountVO(1_000L, "USD"), null), PaymentTimingEnum.IMMEDIATE),
            new UnstructuredVO("not offered"), List.of(),
            new PaymentNotificationVO(NotificationKindEnum.DEFAULT, null, null),
            List.of(new PaymentMethodVO("USD", validUntil, new AmountVO(1_000L), null, networks)));
    }

    private static PaymentNotificationDataVO bank(String network, String currency) {
        return new PaymentNotificationDataVO(
            new PaymentNotificationPaymentVO(new AmountVO(1_000L), null, currency, network, null),
            new PaymentNotificationPayerVO("payer-a"),
            new ExpectedDateVO(NOW.plusDays(1)),
            null);
    }

    /**
     * <b>X9-RAIL-130</b> — each bank rail is offered on its own: a QR Code publishing one rail does
     * not offer the other two, FedNow and RTP included.
     *
     * <p><b>Source:</b> Ours. ADR-0036, amended 2026-11-15 (owner). ANSI X9.150-2026 §14.5 gives
     * each rail its own object.
     *
     * <p><b>Why:</b> Java grouped FedNow and RTP as "instant payments", so a report on RTP was
     * recorded against a QR Code that published only FedNow details.
     */
    @ParameterizedTest
    @CsvSource({"fednow, RTP", "fednow, ACH", "rtp, FedNow", "rtp, ACH", "ach, FedNow", "ach, RTP"})
    void eachRailIsOfferedOnItsOwn(String published, String notified) {
        assertEquals(List.of(NotificationDeviationEnum.NOT_OFFERED_RAIL),
            NotOfferedPolicy.notOffered(offeringOnly(published).getPaymentMethods(), bank(notified, "USD")));
    }

    /**
     * <b>X9-RAIL-131</b> — the published rail in the published currency earns no code, whatever
     * the case of either.
     *
     * <p><b>Source:</b> Ours. ADR-0036, amended 2026-11-15; INTERPRETATION I-1 (lenient in what we
     * accept).
     *
     * <p><b>Why:</b> The rule refuses what was never offered, not a payer's spelling.
     */
    @ParameterizedTest
    @CsvSource({"fednow, FedNow, USD", "fednow, FEDNOW, usd", "rtp, rtp, USD", "ach, ACH, Usd"})
    void anOfferedRailAndCurrencyEarnNoCode(String published, String notified, String currency) {
        assertEquals(List.of(),
            NotOfferedPolicy.notOffered(offeringOnly(published).getPaymentMethods(), bank(notified, currency)));
    }

    /**
     * <b>X9-CUR-090</b> — a currency no payment method offers earns {@code NOT_OFFERED_CURRENCY};
     * with an unoffered rail too, both codes, rail first.
     *
     * <p><b>Source:</b> Ours. ADR-0036, amended 2026-11-15 (owner).
     *
     * <p><b>Why:</b> The payment system reads the codes to decide what to do with the money; it must
     * be told everything that was wrong, in an order it can rely on.
     */
    @Test
    void anUnofferedCurrencyEarnsItsCodeAndBothCanAppear() {
        List<PaymentMethodVO> methods = offeringOnly("fednow").getPaymentMethods();

        assertEquals(List.of(NotificationDeviationEnum.NOT_OFFERED_CURRENCY),
            NotOfferedPolicy.notOffered(methods, bank("FedNow", "EUR")));
        assertEquals(List.of(NotificationDeviationEnum.NOT_OFFERED_RAIL, NotificationDeviationEnum.NOT_OFFERED_CURRENCY),
            NotOfferedPolicy.notOffered(methods, bank("RTP", "EUR")));
    }

    /**
     * <b>X9-RAIL-132</b> — the refusal names the rail, the currency, or both, in the field it is
     * about.
     *
     * <p><b>Source:</b> Ours. ADR-0036, amended 2026-11-15 (owner): the same words for a pre-commit
     * and a post-commit.
     *
     * <p><b>Why:</b> A payer told only "400" cannot fix its side.
     */
    @Test
    void theRefusalNamesWhatIsNotOffered() {
        PaymentNotificationDataVO data = bank("RTP", "EUR");
        List<PaymentMethodVO> methods = offeringOnly("fednow").getPaymentMethods();

        BusinessRuleException rail = NotOfferedPolicy.refusal(methods, List.of(NotificationDeviationEnum.NOT_OFFERED_RAIL), data);
        assertEquals("paymentNotification.data.payment.network", rail.field());
        assertEquals("This QR Code does not offer the RTP rail.", rail.getMessage());

        BusinessRuleException currency =
            NotOfferedPolicy.refusal(methods, List.of(NotificationDeviationEnum.NOT_OFFERED_CURRENCY), data);
        assertEquals("paymentNotification.data.payment.currency", currency.field());
        assertEquals("This QR Code does not offer the EUR currency.", currency.getMessage());

        BusinessRuleException both = NotOfferedPolicy.refusal(methods,
            List.of(NotificationDeviationEnum.NOT_OFFERED_RAIL, NotificationDeviationEnum.NOT_OFFERED_CURRENCY), data);
        assertEquals("paymentNotification.data.payment", both.field());
        assertEquals("This QR Code does not offer the RTP rail nor the EUR currency.", both.getMessage());
    }

    /**
     * <b>X9-RAIL-133</b> — the acceptance gate refuses a pre-commit on a rail the QR Code does not
     * offer, RTP on a FedNow-only QR Code included, naming the rail.
     *
     * <p><b>Source:</b> Ours. ADR-0036, amended 2026-11-15 (owner).
     *
     * <p><b>Why:</b> A pre-commit asks "may I pay this way?"; the answer for a way the biller never
     * published is no, before any money moves.
     */
    @ParameterizedTest
    @CsvSource({"fednow, RTP", "fednow, ACH", "rtp, FedNow", "ach, FedNow"})
    void theGateRefusesAPreCommitOnAnUnofferedRail(String published, String notified) {
        BusinessRuleException refused = assertThrows(BusinessRuleException.class,
            () -> gate.accept(offeringOnly(published), bank(notified, "USD"), NOW));

        assertEquals("This QR Code does not offer the %s rail.".formatted(notified), refused.getMessage());
        assertDoesNotThrow(() -> gate.accept(offeringOnly(published), bank(published, "USD"), NOW));
    }

    /**
     * <b>X9-CUR-091</b> — the acceptance gate refuses a pre-commit naming both an unoffered rail and
     * an unoffered currency with both names.
     *
     * <p><b>Source:</b> Ours. ADR-0036, amended 2026-11-15 (owner).
     *
     * <p><b>Why:</b> Naming only the first fault sends the payer back twice.
     */
    @Test
    void theGateNamesBothWhenBothAreNotOffered() {
        BusinessRuleException refused = assertThrows(BusinessRuleException.class,
            () -> gate.accept(offeringOnly("fednow"), bank("RTP", "EUR"), NOW));

        assertTrue(refused.getMessage().contains("RTP") && refused.getMessage().contains("EUR"), refused.getMessage());
    }

    /** USD on FedNow and USDC on Solana: each rail and each currency offered, but not every pair. */
    private QRCodeEntity usdOnFedNowAndUsdcOnSolana() {
        OffsetDateTime validUntil = NOW.plusDays(30);

        return QRCodeEntity.create(
            UUID::randomUUID, null, validUntil, CREDITOR_FIXTURE.creditor(),
            new BillVO(new DescriptionVO("not offered together"), null, null, TipVO.noTip(),
                new AmountDueVO(new CurrencyAmountVO(1_000L, "USD"), null), PaymentTimingEnum.IMMEDIATE),
            new UnstructuredVO("not offered together"), List.of(),
            new PaymentNotificationVO(NotificationKindEnum.DEFAULT, null, null),
            List.of(
                new PaymentMethodVO("USD", validUntil, new AmountVO(1_000L), null,
                    new NetworksVO(BANK, null, null, null, Map.of())),
                new PaymentMethodVO("USDC", validUntil, new AmountVO(1_000L), null,
                    new NetworksVO(null, null, null, new SolanaPaymentAddressVO(WALLET, null), Map.of()))));
    }

    private static PaymentNotificationDataVO solana(String network, String currency) {
        return new PaymentNotificationDataVO(
            new PaymentNotificationPaymentVO(new AmountVO(1_000L), null, currency, network, null),
            new PaymentNotificationPayerVO("payer-a"),
            null,
            new BlockchainVO(ActionEnum.PAYMENT_INITIATED, new CryptoWalletPaymentAddressVO(PAYER_WALLET),
                new CryptoWalletPaymentAddressVO(WALLET)));
    }

    /**
     * <b>X9-CUR-092</b> — a rail and a currency each offered, but not on the same payment method,
     * earn {@code NOT_OFFERED_CURRENCY}, matched case-insensitively; the pairs a method offers earn
     * nothing.
     *
     * <p><b>Source:</b> Ours. Owner decision of 2026-11-15, amending ADR-0036.
     *
     * <p><b>Why:</b> Offering USD somewhere and Solana somewhere is not offering USD on Solana; the
     * rail is offered, the currency is not offered on it.
     */
    @Test
    void aRailAndACurrencyOnDifferentMethodsAreNotOffered() {
        List<PaymentMethodVO> methods = usdOnFedNowAndUsdcOnSolana().getPaymentMethods();

        assertEquals(List.of(NotificationDeviationEnum.NOT_OFFERED_CURRENCY),
            NotOfferedPolicy.notOffered(methods, solana("Solana", "USD")));
        assertEquals(List.of(NotificationDeviationEnum.NOT_OFFERED_CURRENCY),
            NotOfferedPolicy.notOffered(methods, solana("SOLANA", "usd")));
        assertEquals(List.of(NotificationDeviationEnum.NOT_OFFERED_CURRENCY),
            NotOfferedPolicy.notOffered(methods, bank("fednow", "USDC")));
        assertEquals(List.of(), NotOfferedPolicy.notOffered(methods, solana("solana", "usdc")));
        assertEquals(List.of(), NotOfferedPolicy.notOffered(methods, bank("FedNow", "usd")));
    }

    /**
     * <b>X9-CUR-093</b> — the refusal for a currency offered only on another rail names both, as
     * the payer spelled them: "This QR Code does not offer the USD currency on the Solana rail."
     * The gate refuses such a pre-commit with those words.
     *
     * <p><b>Source:</b> Ours. Owner decision of 2026-11-15, amending ADR-0036.
     *
     * <p><b>Why:</b> "does not offer the USD currency" would be false: the QR Code offers USD, on
     * another rail.
     */
    @Test
    void theRefusalForACurrencyOfferedOnAnotherRailNamesBoth() {
        QRCodeEntity qrCode = usdOnFedNowAndUsdcOnSolana();
        PaymentNotificationDataVO usdOnSolana = solana("Solana", "USD");

        BusinessRuleException refusal = NotOfferedPolicy.refusal(qrCode.getPaymentMethods(),
            NotOfferedPolicy.notOffered(qrCode.getPaymentMethods(), usdOnSolana), usdOnSolana);
        assertEquals("paymentNotification.data.payment.currency", refusal.field());
        assertEquals("This QR Code does not offer the USD currency on the Solana rail.", refusal.getMessage());

        BusinessRuleException refused = assertThrows(BusinessRuleException.class,
            () -> gate.accept(qrCode, usdOnSolana, NOW));
        assertEquals("This QR Code does not offer the USD currency on the Solana rail.", refused.getMessage());

        BusinessRuleException bankRail = assertThrows(BusinessRuleException.class,
            () -> gate.accept(qrCode, bank("FedNow", "usdc"), NOW));
        assertEquals("This QR Code does not offer the usdc currency on the FedNow rail.", bankRail.getMessage());
    }

}
