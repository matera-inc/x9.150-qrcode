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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Story 2.5: what the reservation's window does once it passes, and what stops it (ADR-0019,
 * ADR-0036).
 *
 * <p><b>The window is two seconds here</b>, so a lapse costs a three-second wait rather than the
 * shipped ninety. Every assertion that something still holds is made after waiting well past it.
 */
@TestPropertySource(properties = "x9.reservation.ttl-seconds=2")
class ReservationWindowApiTest extends NotificationMatrixApiSupport {

    private static final String HASH_A = "5VfydnLu4XwV2FquzyFbENPBqbCyJLxq5wRhtZ8rLraBdHfDDmWXnc8nBtCqPU4f";
    private static final String HASH_B = "3kXz8YtWdq1uQvBmn9Lr2HfP6sJcEgTaZo4NyRbVi7Dw5MKeUhGpQjAxCs8tLoWn";
    private static final long PAST_THE_WINDOW_MILLIS = 3_500L;

    /**
     * <b>X9-HOLD-311</b> — winning the pre-commit on a lapsed reservation emits payment.initiated for the new holder.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC5), ADR-0019.
     *
     * <p><b>Why:</b> The stored status still says PAYMENT_INITIATED after a lapse; a transition
     * decided on it emitted nothing, so the stream never learned that B now held the QR Code.
     */
    @Test
    void aTakeoverOfALapsedReservationIsEvented() throws InterruptedException {
        String qrCodeId = createQRCode();
        assertEquals(HttpStatus.OK.value(), bank(qrCodeId, "FedNow", null, PAYER_A).statusCode());

        Thread.sleep(PAST_THE_WINDOW_MILLIS);

        assertEquals(HttpStatus.OK.value(), bank(qrCodeId, "FedNow", null, PAYER_B).statusCode());

        List<Map<String, Object>> events = eventsFor(qrCodeId);
        assertEquals(List.of("payment.initiated", "payment.initiated"),
            events.stream().map(event -> event.get("type")).toList());
        assertEquals(PAYER_B, events.get(1).get("payerInfo"));
        assertEquals("ACTIVE", events.get(1).get("receivedInStatus"), "decided on the effective status");
    }

    /**
     * <b>X9-HOLD-312</b> — the holder's hash stops the window.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC6).
     *
     * <p><b>Why:</b> A reservation that lapsed after a hash let a second payer be approved for a QR
     * Code already paid.
     */
    @Test
    void theHoldersHashStopsTheWindow() throws InterruptedException {
        String qrCodeId = createQRCode();
        solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_A);
        solana(qrCodeId, "SENT", HASH_A, PAYER_A);

        Thread.sleep(PAST_THE_WINDOW_MILLIS);

        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId), "long after the window would have passed");
        assertEquals(HttpStatus.CONFLICT.value(), solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_B).statusCode());
    }

    /**
     * <b>X9-HOLD-313</b> — another payer's hash stops the window too, and the holder does not change.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC6).
     *
     * <p><b>Why:</b> Whoever reported it, money has moved for this QR Code, so it stays out of
     * circulation until the payment system decides.
     */
    @Test
    void anotherPayersHashStopsTheWindowAndTheHolderStays() throws InterruptedException {
        String qrCodeId = createQRCode();
        solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_A);
        solana(qrCodeId, "SENT", HASH_B, PAYER_B);

        Thread.sleep(PAST_THE_WINDOW_MILLIS);

        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
        assertEquals(HttpStatus.OK.value(), solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_A).statusCode(),
            "A is still the holder");
        assertEquals(HttpStatus.CONFLICT.value(), solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_B).statusCode());
    }

    /**
     * <b>X9-HOLD-314</b> — a bank-rail hash stops the window as well.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC6).
     *
     * <p><b>Why:</b> The rule is about money having moved, not about which rail reported it.
     */
    @Test
    void aBankRailHashStopsTheWindowAsWell() throws InterruptedException {
        String qrCodeId = createQRCode();
        bank(qrCodeId, "RTP", null, PAYER_A);
        bank(qrCodeId, "RTP", "20261114021000021P1BRJQ0000000009", PAYER_A);

        Thread.sleep(PAST_THE_WINDOW_MILLIS);

        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
    }

    /**
     * <b>X9-HOLD-315</b> — without a hash, the same wait releases the reservation.
     *
     * <p><b>Source:</b> Ours. ADR-0019.
     *
     * <p><b>Why:</b> The negative control: without it the tests above pass against a build whose
     * reservations never lapse at all.
     */
    @Test
    void withoutAHashTheSameWaitReleasesIt() throws InterruptedException {
        String qrCodeId = createQRCode();
        solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_A);

        Thread.sleep(PAST_THE_WINDOW_MILLIS);

        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

    /**
     * <b>X9-HOLD-316</b> — the holder of a lapsed reservation may still give it back with NOT_SENT.
     *
     * <p><b>Source:</b> Ours. ADR-0036, the matrix's NOT_SENT x ACTIVE cell.
     *
     * <p><b>Why:</b> Nobody has taken the QR Code since, so the courtesy of saying the money is not
     * coming is still the holder's to give, and the stream should hear it.
     */
    @Test
    void theHolderOfALapsedReservationMayStillGiveItBack() throws InterruptedException {
        String qrCodeId = createQRCode();
        solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_A);

        Thread.sleep(PAST_THE_WINDOW_MILLIS);

        assertEquals(HttpStatus.BAD_REQUEST.value(), solana(qrCodeId, "NOT_SENT", null, PAYER_B).statusCode(),
            "nothing of B's to give back");
        assertEquals(HttpStatus.OK.value(), solana(qrCodeId, "NOT_SENT", null, PAYER_A).statusCode());
        assertEquals(List.of("payment.initiated", "payment.failed"), eventTypesFor(qrCodeId));
    }

}
