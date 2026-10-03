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

    /**
     * <b>X9-LIFE-010</b> — a QR Code is created from valid data.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13 — the payload's required elements. Conformance.
     *
     * <p><b>Why:</b> The acceptance case for creation. Every refusal below is only meaningful against it.
     */
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

            // A new payment request is version 0 of itself, not "no version". It used to be null
            // here only because `revision` doubled as the store's optimistic-lock token, which the
            // store filled in on first save; it is now the request's own version and starts at 0.
            assertEquals(0, qrCodeEntity.getRevision());
            assertEquals(expectedActiveStatus, qrCodeEntity.getStatus());
            assertEquals(expectedPaymentMethodListSize, qrCodeEntity.getPaymentMethods().size());
        });
    }

    /**
     * <b>X9-LIFE-011</b> — a stored QR Code is restored without re-running creation rules.
     *
     * <p><b>Source:</b> Mechanism. create() and restore() are deliberately different doors.
     *
     * <p><b>Why:</b> A document already in MongoDB was valid when written. Re-applying creation rules on read
     * would make a rule change retroactively unreadable — the database becomes unloadable because
     * today's policy disagrees with yesterday's data.
     */
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
                    qrCodeEntity.getInitiatedExpiresAt(),
                    qrCodeEntity.getReservedBy(),
                    qrCodeEntity.getStatus(),
                    qrCodeEntity.getCreditor(),
                    qrCodeEntity.getBill(),
                    qrCodeEntity.getUnstructured(),
                    qrCodeEntity.getAdditionalInformation(),
                    qrCodeEntity.getPaymentNotification(),
                    qrCodeEntity.getPaymentMethods(),
                    qrCodeEntity.getPaymentDetails(),
                    qrCodeEntity.getQrcodeContent()
                ,
            null);

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

    /**
     * <b>X9-LIFE-012</b> — creation without an id generator is a programming error, not a business one.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> IllegalArgumentException rather than BusinessRuleException on purpose: no caller supplied bad
     * data, the service was wired wrong. Reporting it as a business rule would surface a deployment
     * fault to a payer as if they had done something wrong.
     */
    @Test
    void shouldThrowIllegalArgumentExceptionWhenCreateQRCodeEntityWithoutIdGenerator() {
        NullPointerException nullPointerException = assertThrows(NullPointerException.class,
            () -> QRCodeEntity.create(null, null, null, null, null, null, null, null, null));

        assertEquals("IdGenerator must not be null.", nullPointerException.getMessage());
    }

    /**
     * <b>X9-LIFE-013</b> — a QR Code cannot be created without a creditor.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §12 — creditor is mandatory in the payload. Conformance.
     *
     * <p><b>Why:</b> A payer cannot be asked to pay without being told who they are paying.
     */
    @Test
    void shouldThrowBusinessRuleExceptionWhenCreateQRCodeEntityWithoutCreditor() {
        BusinessRuleException businessRuleException = assertThrows(BusinessRuleException.class,
            () -> QRCodeEntity.create(QR_CODE_ENTITY_FIXTURE.uuidGenerator(), QR_CODE_ENTITY_FIXTURE.location(), QR_CODE_ENTITY_FIXTURE.validUntil(), null, null, null, null, null, null));

        assertEquals("creditor", businessRuleException.field());
        assertEquals("must not be null.", businessRuleException.getMessage());
    }

    /**
     * <b>X9-LIFE-014</b> — a QR Code cannot be created without a bill.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13 — bill is mandatory. Conformance.
     *
     * <p><b>Why:</b> Without a bill there is no amount and nothing to settle.
     */
    @Test
    void shouldThrowBusinessRuleExceptionWhenCreateQRCodeEntityWithoutBill() {
        BusinessRuleException businessRuleException = assertThrows(BusinessRuleException.class,
            () -> QRCodeEntity.create(QR_CODE_ENTITY_FIXTURE.uuidGenerator(), QR_CODE_ENTITY_FIXTURE.location(), QR_CODE_ENTITY_FIXTURE.validUntil(), CREDITOR_FIXTURE.creditor(), null, null, null, null, null));

        assertEquals("bill", businessRuleException.field());
        assertEquals("must not be null.", businessRuleException.getMessage());
    }

    /**
     * <b>X9-LIFE-015</b> — a QR Code cannot be created with no payment method.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §14 — paymentMethods is mandatory. Conformance.
     *
     * <p><b>Why:</b> Null and empty both, because an empty list is the likelier mistake: it serialises cleanly,
     * passes a null check, and produces a QR Code nobody can pay by any route.
     */
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