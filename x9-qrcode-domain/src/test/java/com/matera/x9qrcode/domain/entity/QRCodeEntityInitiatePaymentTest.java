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

    @Test
    void shouldInitiatePaymentWhenQRCodeIsActive() {
        QRCodeEntity qrCodeEntity = QR_CODE_ENTITY_FIXTURE.qrCodeEntity();
        assertEquals(QRCodeStatusEnum.ACTIVE, qrCodeEntity.getStatus());

        qrCodeEntity.initiatePayment(null);

        assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, qrCodeEntity.getStatus());
    }

    @Test
    void shouldBumpRevisedAtWhenInitiatingPayment() {
        QRCodeEntity qrCodeEntity = QR_CODE_ENTITY_FIXTURE.qrCodeEntity();

        qrCodeEntity.initiatePayment(null);

        assertNotEquals(qrCodeEntity.getCreatedAt(), qrCodeEntity.getRevisedAt());
    }

    @Test
    void shouldThrowConflictWhenPaymentIsAlreadyInitiated() {
        QRCodeEntity qrCodeEntity = QR_CODE_ENTITY_FIXTURE.qrCodeEntity();
        qrCodeEntity.initiatePayment(null);

        QRCodeStatusConflictException exception =
            assertThrows(QRCodeStatusConflictException.class, () -> qrCodeEntity.initiatePayment(null));

        assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, exception.getCurrentStatus());
    }

    @Test
    void shouldThrowConflictWhenQRCodeIsAlreadyPaid() {
        QRCodeEntity qrCodeEntity = QR_CODE_ENTITY_FIXTURE.qrCodeEntity();
        qrCodeEntity.pay(PAYMENT_DETAILS);

        QRCodeStatusConflictException exception =
            assertThrows(QRCodeStatusConflictException.class, () -> qrCodeEntity.initiatePayment(null));

        assertEquals(QRCodeStatusEnum.PAID, exception.getCurrentStatus());
    }

    @Test
    void shouldThrowConflictWhenQRCodeIsCancelled() {
        QRCodeEntity qrCodeEntity = QR_CODE_ENTITY_FIXTURE.qrCodeEntity();
        qrCodeEntity.cancel(null);

        QRCodeStatusConflictException exception =
            assertThrows(QRCodeStatusConflictException.class, () -> qrCodeEntity.initiatePayment(null));

        assertEquals(QRCodeStatusEnum.CANCELLED, exception.getCurrentStatus());
    }

    @Test
    void shouldThrowBusinessRuleExceptionWhenPaymentDetailsAreInformed() {
        QRCodeEntity qrCodeEntity = QR_CODE_ENTITY_FIXTURE.qrCodeEntity();

        assertThrows(BusinessRuleException.class, () -> qrCodeEntity.initiatePayment(PAYMENT_DETAILS));

        assertEquals(QRCodeStatusEnum.ACTIVE, qrCodeEntity.getStatus());
    }

}
