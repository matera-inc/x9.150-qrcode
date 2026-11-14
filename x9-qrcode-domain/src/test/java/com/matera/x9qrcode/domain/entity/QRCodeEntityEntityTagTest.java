/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.entity;

import com.matera.x9qrcode.domain.AbstractTest;
import com.matera.x9qrcode.domain.exception.QRCodePreconditionFailedException;
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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The entity tag is built from the effective status, and {@code If-Match} is compared against
 * that same tag (Story 2.2, ADR-0028).
 *
 * <p>Before this, GET built its ETag from the effective status while {@code If-Match} was compared
 * against a tag built from the stored one. Once a reservation lapsed the two disagreed, and every
 * conditional request carrying the tag the caller had just been given answered 412.
 *
 * <p>Time is a parameter: the reservation is stamped with a window and the tag is asked about an
 * instant inside it and an instant past it, so nothing here sleeps.
 */
class QRCodeEntityEntityTagTest extends AbstractTest {

    private static final String WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final Duration TTL = Duration.ofSeconds(90);

    private QRCodeEntity reserved() {
        OffsetDateTime validUntil = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30);
        BillVO bill = new BillVO(
            new DescriptionVO("etag"), null, null, TipVO.noTip(),
            new AmountDueVO(new CurrencyAmountVO(1_000L, "USDC"), null),
            PaymentTimingEnum.IMMEDIATE);

        QRCodeEntity qrCode = QRCodeEntity.create(
            UUID::randomUUID, null, validUntil, CREDITOR_FIXTURE.creditor(), bill,
            new UnstructuredVO("etag test"), List.of(),
            new PaymentNotificationVO(NotificationKindEnum.DEFAULT, null, null),
            List.of(new PaymentMethodVO(
                "USDC", validUntil, new AmountVO(1_000L), null,
                new NetworksVO(null, null, null, new SolanaPaymentAddressVO(WALLET, null), Map.of()))));
        qrCode.initiatePayment(null, TTL);
        return qrCode;
    }

    private static String revisionOf(QRCodeEntity qrCode) {
        Integer revision = qrCode.getRevision();
        return String.valueOf(revision == null ? 0 : revision);
    }

    /**
     * <b>X9-LIFE-128</b> — the entity tag of a lapsed reservation carries the effective status.
     *
     * <p><b>Source:</b> Ours. ADR-0016 (the tag covers revision and status), ADR-0028 (the ETag
     * fix). S2.2-AC2.
     *
     * <p><b>Why:</b> GET already reports a lapsed reservation as {@code ACTIVE}. A tag built from the
     * stored status would describe a state the caller was never shown.
     */
    @Test
    void theEntityTagOfALapsedReservationCarriesTheEffectiveStatus() {
        QRCodeEntity qrCode = reserved();
        String revision = revisionOf(qrCode);
        OffsetDateTime inside = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30);
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(91);

        assertEquals(revision + "-PAYMENT_INITIATED", qrCode.entityTag(inside));
        assertEquals(revision + "-ACTIVE", qrCode.entityTag(past));
    }

    /**
     * <b>X9-LIFE-129</b> — If-Match accepts the tag GET gave for a lapsed reservation.
     *
     * <p><b>Source:</b> Ours. ADR-0028. S2.2-AC1, S2.2-AC2.
     *
     * <p><b>Why:</b> The bug. The tag a caller was handed must be the tag the server compares
     * against; otherwise a conditional request fails only because time passed.
     */
    @Test
    void ifMatchAcceptsTheTagGetGaveForALapsedReservation() {
        QRCodeEntity qrCode = reserved();
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(91);

        assertDoesNotThrow(() -> qrCode.requireEntityTag(qrCode.entityTag(past), past));
        assertDoesNotThrow(() -> qrCode.requireEntityTag(revisionOf(qrCode) + "-ACTIVE", past));
    }

    /**
     * <b>X9-LIFE-130</b> — a tag naming another revision or the stored status is still refused.
     *
     * <p><b>Source:</b> Ours. ADR-0016, RFC 9110 If-Match. S2.2-AC3.
     *
     * <p><b>Why:</b> The fix must not loosen the check. A stale revision is a change the caller did
     * not see; a tag naming {@code PAYMENT_INITIATED} after the reservation lapsed describes a state
     * that is no longer the case.
     */
    @Test
    void aTagNamingAnotherRevisionOrTheStoredStatusIsStillRefused() {
        QRCodeEntity qrCode = reserved();
        OffsetDateTime past = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(91);
        String revision = revisionOf(qrCode);

        assertThrows(QRCodePreconditionFailedException.class,
            () -> qrCode.requireEntityTag("99-ACTIVE", past));
        assertThrows(QRCodePreconditionFailedException.class,
            () -> qrCode.requireEntityTag(revision + "-PAYMENT_INITIATED", past));
    }
}
