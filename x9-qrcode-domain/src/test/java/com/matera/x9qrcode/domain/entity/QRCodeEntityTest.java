/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.entity;

import com.matera.x9qrcode.domain.AbstractTest;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.vo.PaymentMethodVO;
import com.matera.x9qrcode.domain.vo.enumerated.QRCodeStatusEnum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QRCodeEntityTest extends AbstractTest {

    @Test
    void shouldCreateQRCodeEntityWithValidData() {
        QRCodeStatusEnum expectedActiveStatus = QRCodeStatusEnum.ACTIVE;
        int expectedPaymentMethodListSize = 3;

        assertDoesNotThrow(() -> {
            QRCodeEntity qrCodeEntity = QR_CODE_ENTITY_FIXTURE.qrCodeEntity();

            assertNotNull(qrCodeEntity);
            assertNotNull(qrCodeEntity.getId());
            assertNotNull(qrCodeEntity.getLocationId());
            assertNotNull(qrCodeEntity.getValidUntil());
            assertNotNull(qrCodeEntity.getBill());
            assertNotNull(qrCodeEntity.getCreditor());
            assertNotNull(qrCodeEntity.getCreatedAt());
            assertNotNull(qrCodeEntity.getRevisedAt());
            assertNotNull(qrCodeEntity.getStatus());
            assertNotNull(qrCodeEntity.getPaymentMethods());

            assertNull(qrCodeEntity.getPaymentDetails());
            assertNull(qrCodeEntity.getQrcodeContent());

            assertNull(qrCodeEntity.getRevision());
            assertEquals(expectedActiveStatus, qrCodeEntity.getStatus());
            assertEquals(expectedPaymentMethodListSize, qrCodeEntity.getPaymentMethods().size());
        });
    }

    @Test
    void shouldRestoreQRCodeEntityWithValidData() {
        assertDoesNotThrow(() -> {
            QRCodeEntity qrCodeEntity = QR_CODE_ENTITY_FIXTURE.qrCodeEntity();

            QRCodeEntity restoredQRCodeEntity =
                QRCodeEntity.restore(
                    qrCodeEntity.getId().value(),
                    qrCodeEntity.getLocationId().value(),
                    qrCodeEntity.getRevision(),
                    qrCodeEntity.getCreatedAt(),
                    qrCodeEntity.getRevisedAt(),
                    qrCodeEntity.getValidUntil(),
                    qrCodeEntity.getStatus(),
                    qrCodeEntity.getCreditor(),
                    qrCodeEntity.getBill(),
                    qrCodeEntity.getUnstructured(),
                    qrCodeEntity.getAdditionalInformation(),
                    qrCodeEntity.getPaymentNotification(),
                    qrCodeEntity.getPaymentMethods(),
                    qrCodeEntity.getPaymentDetails(),
                    qrCodeEntity.getQrcodeContent()
                );

            assertNotNull(restoredQRCodeEntity);

            assertEquals(qrCodeEntity.getId(), restoredQRCodeEntity.getId());
            assertEquals(qrCodeEntity.getLocationId(), restoredQRCodeEntity.getLocationId());
            assertEquals(qrCodeEntity.getValidUntil(), restoredQRCodeEntity.getValidUntil());
            assertEquals(qrCodeEntity.getBill(), restoredQRCodeEntity.getBill());
            assertEquals(qrCodeEntity.getCreditor(), restoredQRCodeEntity.getCreditor());
            assertEquals(qrCodeEntity.getRevision(), restoredQRCodeEntity.getRevision());
            assertEquals(qrCodeEntity.getCreatedAt(), restoredQRCodeEntity.getCreatedAt());
            assertEquals(qrCodeEntity.getRevisedAt(), restoredQRCodeEntity.getRevisedAt());
            assertEquals(qrCodeEntity.getStatus(), restoredQRCodeEntity.getStatus());
            assertEquals(qrCodeEntity.getPaymentMethods(), restoredQRCodeEntity.getPaymentMethods());
            assertEquals(qrCodeEntity.getQrcodeContent(), restoredQRCodeEntity.getQrcodeContent());
            assertEquals(qrCodeEntity.getPaymentDetails(), restoredQRCodeEntity.getPaymentDetails());
        });
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenCreateQRCodeEntityWithoutIdGenerator() {
        NullPointerException nullPointerException = assertThrows(NullPointerException.class,
            () -> QRCodeEntity.create(null, null, null, null, null, null, null, null, null));

        assertEquals("IdGenerator must not be null.", nullPointerException.getMessage());
    }

    @Test
    void shouldThrowBusinessRuleExceptionWhenCreateQRCodeEntityWithoutCreditor() {
        BusinessRuleException businessRuleException = assertThrows(BusinessRuleException.class,
            () -> QRCodeEntity.create(QR_CODE_ENTITY_FIXTURE.uuidGenerator(), QR_CODE_ENTITY_FIXTURE.location(), QR_CODE_ENTITY_FIXTURE.validUntil(), null, null, null, null, null, null));

        assertEquals("creditor", businessRuleException.field());
        assertEquals("must not be null.", businessRuleException.getMessage());
    }

    @Test
    void shouldThrowBusinessRuleExceptionWhenCreateQRCodeEntityWithoutBill() {
        BusinessRuleException businessRuleException = assertThrows(BusinessRuleException.class,
            () -> QRCodeEntity.create(QR_CODE_ENTITY_FIXTURE.uuidGenerator(), QR_CODE_ENTITY_FIXTURE.location(), QR_CODE_ENTITY_FIXTURE.validUntil(), CREDITOR_FIXTURE.creditor(), null, null, null, null, null));

        assertEquals("bill", businessRuleException.field());
        assertEquals("must not be null.", businessRuleException.getMessage());
    }

    @ParameterizedTest
    @MethodSource("invalidPaymentMethods")
    void shouldThrowBusinessRuleExceptionWhenCreateQRCodeEntityWithoutPaymentMethods(List<PaymentMethodVO> paymentMethods) {
        BusinessRuleException businessRuleException = assertThrows(BusinessRuleException.class,
            () -> QRCodeEntity.create(QR_CODE_ENTITY_FIXTURE.uuidGenerator(), QR_CODE_ENTITY_FIXTURE.location(), QR_CODE_ENTITY_FIXTURE.validUntil(), CREDITOR_FIXTURE.creditor(), BILL_FIXTURE.bill(), null, null, null, paymentMethods));

        assertEquals("paymentMethods", businessRuleException.field());
        assertEquals("must not be null or empty.", businessRuleException.getMessage());
    }

    public static Stream<Arguments> invalidPaymentMethods() {
        return Stream.of(
            Arguments.of((List<PaymentMethodVO>) null),
            Arguments.of(List.of())
        );
    }

}