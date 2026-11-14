/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.paymentnotification;

import com.matera.x9qrcode.app.dto.PaymentNotificationDataDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationPayerDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationPaymentDTO;
import com.matera.x9qrcode.app.exception.QRCodeConcurrentModificationException;
import com.matera.x9qrcode.app.repository.QRCodeRepository;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.QRCodeStatusConflictException;
import com.matera.x9qrcode.domain.service.PaymentNotificationAcceptancePolicy;
import com.matera.x9qrcode.domain.vo.enumerated.QRCodeStatusEnum;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentNotificationRaceExhaustedTest {

    /**
     * <b>X9-HOLD-221</b> — losing the save race after every re-run answers 409 busy with the current status, never 500.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (owner 2026-11-14), as ADR-0025 says for Go.
     *
     * <p><b>Why:</b> A notification that keeps losing to concurrent writers has done nothing wrong;
     * a 500 would read as a fault in this service, while 409 with the status tells the payer to
     * come back.
     */
    @Test
    void losingTheRaceEveryTimeIsABusyConflictNotAServerError() {
        QRCodeRepository repository = mock(QRCodeRepository.class);
        QRCodeEntity qrCode = mock(QRCodeEntity.class);
        when(repository.findById(any())).thenReturn(qrCode);
        when(qrCode.effectiveStatus()).thenReturn(QRCodeStatusEnum.PAYMENT_INITIATED);
        when(repository.save(any())).thenThrow(new QRCodeConcurrentModificationException("lost", null));

        PaymentNotificationQRCodeUseCase useCase = new PaymentNotificationQRCodeUseCase(
            repository, mock(PaymentNotificationAcceptancePolicy.class), Duration.ofSeconds(90));

        PaymentNotificationQRCodeInput input = new PaymentNotificationQRCodeInput(
            new PaymentNotificationDataDTO(UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                new PaymentNotificationPaymentDTO(1_000L, null, "USD", "FedNow", null),
                new PaymentNotificationPayerDTO("payer-a"),
                OffsetDateTime.parse("2030-10-08T06:59:59Z"),
                null),
            "CN=race-test-psp.example.com");

        QRCodeStatusConflictException busy = assertThrows(QRCodeStatusConflictException.class,
            () -> useCase.execute(input));

        assertEquals(QRCodeStatusEnum.PAYMENT_INITIATED, busy.getCurrentStatus());
        verify(repository, times(PaymentNotificationQRCodeUseCase.MAX_ATTEMPTS)).save(qrCode);
    }

}
