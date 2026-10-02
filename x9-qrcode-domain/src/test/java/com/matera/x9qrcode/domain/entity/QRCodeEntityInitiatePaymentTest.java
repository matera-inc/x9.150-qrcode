/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.entity;

import com.matera.x9qrcode.domain.AbstractTest;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.exception.QRCodeStatusConflictException;
import com.matera.x9qrcode.domain.vo.PaymentDetailsVO;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;
import com.matera.x9qrcode.domain.vo.enumerated.QRCodeStatusEnum;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ACTIVE -> PAYMENT_INITIATED, the transition ANSI X9.150-2026 §A.9 asks the payee's PSP to make on
 * initial network acceptance of a credit transfer request, "to prevent duplicate payment". The
 * guard is the point: it must be refused for any QR Code that is not ACTIVE.
 */
class QRCodeEntityInitiatePaymentTest extends AbstractTest {

    private static final PaymentDetailsVO PAYMENT_DETAILS =
        new PaymentDetailsVO("XYZ.USBK.X9aTf72qLm.1", NetworkEnum.FEDNOW.value());

    /**
     * <b>X9-LIFE-020</b> — a payment can be initiated on an ACTIVE QR Code.
     *
     * <p><b>Source:</b> Ours — the reservation is this implementation's two-phase step. ADR-0002.
     *
     * <p><b>Why:</b> The acceptance case for reservation.
     */
    @Test
    void shouldInitiatePaymentWhenQRCodeIsActive() {
        QRCodeEntity qrCodeEntity = QR_CODE_ENTITY_FIXTURE.qrCodeEntity();
        assertEquals(QRCodeStatusEnum.ACTIVE, qrCodeEntity.getStatus());

        qrCodeEntity.initiatePayment(null);

        assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, qrCodeEntity.getStatus());
    }

    /**
     * <b>X9-LIFE-021</b> — initiating a payment moves revisedAt but not the revision.
     *
     * <p><b>Source:</b> Ours. ADR-0016 — a revision is a version of the request, not of its status.
     *
     * <p><b>Why:</b> Something happened to the QR Code, so revisedAt moves; nobody changed what is being asked
     * for, so the revision does not. Before ADR-0016 both moved, and a bill nobody had edited was
     * reported as version 2, 3, 4 — one per thing that had happened to it.
     */
    @Test
    void shouldBumpRevisedAtWhenInitiatingPayment() {
        QRCodeEntity qrCodeEntity = QR_CODE_ENTITY_FIXTURE.qrCodeEntity();

        qrCodeEntity.initiatePayment(null);

        assertNotEquals(qrCodeEntity.getCreatedAt(), qrCodeEntity.getRevisedAt());
    }

    /**
     * <b>X9-LIFE-022</b> — a second initiation on a reserved QR Code is a conflict.
     *
     * <p><b>Source:</b> Ours. ADR-0002.
     *
     * <p><b>Why:</b> Two payers must not both believe they hold the same QR Code. The first reservation wins.
     */
    @Test
    void shouldThrowConflictWhenPaymentIsAlreadyInitiated() {
        QRCodeEntity qrCodeEntity = QR_CODE_ENTITY_FIXTURE.qrCodeEntity();
        qrCodeEntity.initiatePayment(null);

        QRCodeStatusConflictException exception =
            assertThrows(QRCodeStatusConflictException.class, () -> qrCodeEntity.initiatePayment(null));

        assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, exception.getCurrentStatus());
    }

    /**
     * <b>X9-LIFE-023</b> — a paid QR Code cannot be initiated again.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §9 — status governs what may still happen. Conformance.
     *
     * <p><b>Why:</b> The bill is settled. A second payment against it is money taken for nothing owed.
     */
    @Test
    void shouldThrowConflictWhenQRCodeIsAlreadyPaid() {
        QRCodeEntity qrCodeEntity = QR_CODE_ENTITY_FIXTURE.qrCodeEntity();
        qrCodeEntity.pay(PAYMENT_DETAILS);

        QRCodeStatusConflictException exception =
            assertThrows(QRCodeStatusConflictException.class, () -> qrCodeEntity.initiatePayment(null));

        assertEquals(QRCodeStatusEnum.PAID, exception.getCurrentStatus());
    }

    /**
     * <b>X9-LIFE-024</b> — a cancelled QR Code cannot be initiated.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §9. Conformance.
     *
     * <p><b>Why:</b> A withdrawn bill must not accept new payers.
     */
    @Test
    void shouldThrowConflictWhenQRCodeIsCancelled() {
        QRCodeEntity qrCodeEntity = QR_CODE_ENTITY_FIXTURE.qrCodeEntity();
        qrCodeEntity.cancel(null);

        QRCodeStatusConflictException exception =
            assertThrows(QRCodeStatusConflictException.class, () -> qrCodeEntity.initiatePayment(null));

        assertEquals(QRCodeStatusEnum.CANCELLED, exception.getCurrentStatus());
    }

    /**
     * <b>X9-LIFE-025</b> — a pre-payment may not carry settlement details.
     *
     * <p><b>Source:</b> Ours. ADR-0002 — the two phases carry different evidence.
     *
     * <p><b>Why:</b> A transaction hash in a PRE-payment claims money has already moved, which is the opposite of
     * what the phase means. Accepting it would record settlement evidence for a payment that has
     * not happened.
     */
    @Test
    void shouldThrowBusinessRuleExceptionWhenPaymentDetailsAreInformed() {
        QRCodeEntity qrCodeEntity = QR_CODE_ENTITY_FIXTURE.qrCodeEntity();

        assertThrows(BusinessRuleException.class, () -> qrCodeEntity.initiatePayment(PAYMENT_DETAILS));

        assertEquals(QRCodeStatusEnum.ACTIVE, qrCodeEntity.getStatus());
    }

}
