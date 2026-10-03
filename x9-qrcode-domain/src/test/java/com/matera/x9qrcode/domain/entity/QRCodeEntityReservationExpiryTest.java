/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.entity;

import com.matera.x9qrcode.domain.AbstractTest;
import com.matera.x9qrcode.domain.vo.AmountDueVO;
import com.matera.x9qrcode.domain.vo.AmountVO;
import com.matera.x9qrcode.domain.vo.BillVO;
import com.matera.x9qrcode.domain.vo.CurrencyAmountVO;
import com.matera.x9qrcode.domain.vo.DescriptionVO;
import com.matera.x9qrcode.domain.vo.NetworksVO;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.PaymentNotificationVO;
import com.matera.x9qrcode.domain.vo.SolanaPaymentAddressVO;
import com.matera.x9qrcode.domain.vo.TipVO;
import com.matera.x9qrcode.domain.vo.UnstructuredVO;
import com.matera.x9qrcode.domain.vo.enumerated.NotificationKindEnum;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;
import com.matera.x9qrcode.domain.vo.enumerated.QRCodeStatusEnum;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A reservation stops counting after its window, and nothing sweeps to make that so.
 *
 * <p>Time is a parameter here rather than a wait. The rule is a comparison against a stamped
 * instant, so it can be asked about any moment — and a test that slept for the window would be slow
 * and, on a loaded CI runner, would sometimes sleep through more than it meant to.
 */
class QRCodeEntityReservationExpiryTest extends AbstractTest {

    private static final String WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final Duration TTL = Duration.ofSeconds(90);
    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

    private QRCodeEntity qrCode() {
        return qrCodeValidUntil(NOW.plusDays(30));
    }

    private QRCodeEntity qrCodeValidUntil(OffsetDateTime validUntil) {
        BillVO bill = new BillVO(
            new DescriptionVO("reservation"), null, null, TipVO.noTip(),
            new AmountDueVO(new CurrencyAmountVO(1_000L, "USDC"), null),
            PaymentTimingEnum.IMMEDIATE);

        return QRCodeEntity.create(
            UUID::randomUUID, null, validUntil, CREDITOR_FIXTURE.creditor(), bill,
            new UnstructuredVO("reservation test"), List.of(),
            new PaymentNotificationVO(NotificationKindEnum.DEFAULT, null, null),
            List.of(new PaymentMethodVO(
                "USDC", validUntil, new AmountVO(1_000L), null,
                new NetworksVO(null, null, null, new SolanaPaymentAddressVO(WALLET, null), Map.of()))));
    }

    /**
     * <b>X9-LIFE-110</b> — a reservation inside its window still holds.
     *
     * <p><b>Source:</b> Ours. X9.150 has no notion of holding a QR Code, so the reservation and its
     * window are both this implementation's. ADR-0002.
     *
     * <p><b>Why:</b> The acceptance case. Without it every expiry assertion below would pass against
     * an implementation that released reservations instantly, which would be worse than never
     * holding them at all.
     */
    @Test
    void aReservationInsideItsWindowStillHolds() {
        QRCodeEntity qrCode = qrCode();
        qrCode.initiatePayment(null, TTL);

        assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED,
            qrCode.effectiveStatus(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(89)));
    }

    /**
     * <b>X9-LIFE-111</b> — a reservation past its window reads as ACTIVE.
     *
     * <p><b>Source:</b> Ours. ADR-0002.
     *
     * <p><b>Why:</b> The whole feature. A payer announces and then vanishes — app closed, custody
     * provider refuses, phone dead — and nothing releases the QR Code. Before this the bill was
     * unpayable by anybody until {@code validUntil}: hours or days for a code held for seconds.
     */
    @Test
    void aReservationPastItsWindowReadsAsActive() {
        QRCodeEntity qrCode = qrCode();
        qrCode.initiatePayment(null, TTL);

        assertEquals(QRCodeStatusEnum.ACTIVE,
            qrCode.effectiveStatus(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(91)));
    }

    /**
     * <b>X9-LIFE-112</b> — the stored status is left alone when a reservation lapses.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> Nothing is swept and nothing is written. The stored value records what was last
     * REPORTED — a payer did announce, and that remains true — while {@code effectiveStatus} reports
     * what is now the CASE. A QR Code sitting untouched costs nothing, and a reservation lapsing at
     * 3am wakes nobody.
     */
    @Test
    void theStoredStatusIsLeftAloneWhenAReservationLapses() {
        QRCodeEntity qrCode = qrCode();
        qrCode.initiatePayment(null, TTL);

        OffsetDateTime later = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(91);

        assertEquals(QRCodeStatusEnum.ACTIVE, qrCode.effectiveStatus(later));
        assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, qrCode.getStatus());
    }

    /**
     * <b>X9-LIFE-113</b> — a lapsed reservation lets the next payer in.
     *
     * <p><b>Source:</b> Ours. ADR-0002.
     *
     * <p><b>Why:</b> The consequence that matters. Reading ACTIVE is worth nothing if the guard on
     * {@code initiatePayment} still reads the stored field — which is the single way this feature
     * can be half-applied, and the reason every status decision goes through one method.
     */
    @Test
    void aLapsedReservationLetsTheNextPayerIn() {
        QRCodeEntity qrCode = qrCode();
        qrCode.initiatePayment(null, Duration.ofSeconds(-1));

        assertDoesNotThrow(() -> qrCode.initiatePayment(null, TTL));
    }

    /**
     * <b>X9-LIFE-114</b> — a reservation inside its window keeps the next payer out.
     *
     * <p><b>Source:</b> Ours. ADR-0002.
     *
     * <p><b>Why:</b> The other half of LIFE-113. Two payers must not both believe they hold one QR
     * Code, which is the reason reservations exist; an expiry that released them immediately would
     * satisfy LIFE-113 and destroy the point.
     */
    @Test
    void aReservationInsideItsWindowKeepsTheNextPayerOut() {
        QRCodeEntity qrCode = qrCode();
        qrCode.initiatePayment(null, TTL);

        assertThrows(RuntimeException.class, () -> qrCode.initiatePayment(null, TTL));
    }

    /**
     * <b>X9-LIFE-115</b> — the window never outlives the QR Code.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> A reservation cannot outlive the thing reserved. A stamp past {@code validUntil}
     * would claim a hold over a code that is expired anyway, and would read as holding in the one
     * window where nothing can be paid.
     */
    @Test
    void theWindowNeverOutlivesTheQRCode() {
        OffsetDateTime soon = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(10);
        QRCodeEntity qrCode = qrCodeValidUntil(soon);

        qrCode.initiatePayment(null, Duration.ofHours(1));

        assertEquals(soon, qrCode.getInitiatedExpiresAt());
    }

    /**
     * <b>X9-LIFE-116</b> — the stamp is cleared when the reservation ends.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> Clearing matters as much as stamping. An instant left on a PAID QR Code means
     * nothing today and will be read by the next person as if it did.
     */
    @Test
    void theStampIsClearedWhenTheReservationEnds() {
        QRCodeEntity qrCode = qrCode();
        qrCode.initiatePayment(null, TTL);
        assertNotNull(qrCode.getInitiatedExpiresAt());

        qrCode.reactivate(null);

        assertNull(qrCode.getInitiatedExpiresAt());
        assertEquals(QRCodeStatusEnum.ACTIVE, qrCode.effectiveStatus());
    }

    /**
     * <b>X9-LIFE-117</b> — a terminal status is never reinterpreted.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> The rule applies to one state. PAID and CANCELLED are terminal and no amount of
     * waiting changes them — a bill that paid itself by expiry would be the worst bug in the file.
     */
    @Test
    void aTerminalStatusIsNeverReinterpreted() {
        QRCodeEntity cancelled = qrCode();
        cancelled.cancel(null);

        assertEquals(QRCodeStatusEnum.CANCELLED,
            cancelled.effectiveStatus(OffsetDateTime.now(ZoneOffset.UTC).plusDays(400)));
    }

    /**
     * <b>X9-LIFE-118</b> — a reservation with no stamp is treated as holding.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> Documents written before this field existed carry no instant. Releasing a
     * reservation we cannot date would be guessing against the payer who announced it, so the
     * unknown case fails towards keeping their claim rather than handing their QR Code away.
     */
    @Test
    void aReservationWithNoStampIsTreatedAsHolding() {
        QRCodeEntity restored = QRCodeEntity.restore(
            UUID.randomUUID(), UUID.randomUUID(), 0, NOW, NOW, NOW.plusDays(30),
            null,
            null,
            QRCodeStatusEnum.PAYMENT_INITIATED,
            CREDITOR_FIXTURE.creditor(),
            new BillVO(new DescriptionVO("legacy"), null, null, TipVO.noTip(),
                new AmountDueVO(new CurrencyAmountVO(1_000L, "USDC"), null), PaymentTimingEnum.IMMEDIATE),
            new UnstructuredVO("legacy"), List.of(),
            new PaymentNotificationVO(NotificationKindEnum.DEFAULT, null, null),
            List.of(new PaymentMethodVO("USDC", NOW.plusDays(30), new AmountVO(1_000L), null,
                new NetworksVO(null, null, null, new SolanaPaymentAddressVO(WALLET, null), Map.of()))),
            null, null, 0);

        assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED,
            restored.effectiveStatus(OffsetDateTime.now(ZoneOffset.UTC).plusDays(400)));
    }
}
