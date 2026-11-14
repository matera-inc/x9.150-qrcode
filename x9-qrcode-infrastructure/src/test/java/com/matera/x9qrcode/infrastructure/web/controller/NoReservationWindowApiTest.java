/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Story 2.5: {@code x9.reservation.ttl-seconds: 0} hands the window to the bank (ADR-0036).
 */
@TestPropertySource(properties = "x9.reservation.ttl-seconds=0")
class NoReservationWindowApiTest extends NotificationMatrixApiSupport {

    /**
     * <b>X9-HOLD-321</b> — with ttl-seconds 0 a reservation never lapses, and still ends by NOT_SENT or the status-update API.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC7).
     *
     * <p><b>Why:</b> 0 used to compute "now + 0", so every reservation expired at once and a second
     * payer could take any QR Code the instant after the first was approved.
     */
    @Test
    void aZeroWindowNeverLapsesAndStillEndsByNotSentOrStatusUpdate() throws InterruptedException {
        String released = createQRCode();
        assertEquals(HttpStatus.OK.value(), solana(released, "PAYMENT_INITIATED", null, PAYER_A).statusCode());

        Thread.sleep(1_500L);

        assertEquals("PAYMENT_INITIATED", statusOf(released), "0 no longer means 'expire at once'");
        assertEquals(HttpStatus.CONFLICT.value(), solana(released, "PAYMENT_INITIATED", null, PAYER_B).statusCode());
        assertEquals(HttpStatus.OK.value(), solana(released, "NOT_SENT", null, PAYER_A).statusCode());
        assertEquals("ACTIVE", statusOf(released));

        String reverted = createQRCode();
        bank(reverted, "FedNow", null, PAYER_A);
        statusUpdate(reverted, "{\"status\":\"ACTIVE\"}").then().statusCode(HttpStatus.OK.value());
        assertEquals("ACTIVE", statusOf(reverted));
    }

    /**
     * <b>X9-HOLD-322</b> — with ttl-seconds 0 a refused retry still frees the QR Code.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC7, S2.5-AC2).
     *
     * <p><b>Why:</b> No window must not mean a refused payer keeps the QR Code forever.
     */
    @Test
    void aZeroWindowStillEndsByARefusedRetry() {
        String qrCodeId = createQRCode();
        bank(qrCodeId, "FedNow", null, PAYER_A);

        assertEquals(HttpStatus.BAD_REQUEST.value(), bank(qrCodeId, "FedNow", null, PAYER_A, AMOUNT + 1).statusCode());

        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

}
