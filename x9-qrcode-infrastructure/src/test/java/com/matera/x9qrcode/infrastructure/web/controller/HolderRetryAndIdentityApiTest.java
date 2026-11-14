/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.domain.utils.BusinessClock;

import io.restassured.module.mockmvc.response.MockMvcResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Story 2.5: the holder's retry is judged again, a refused retry frees the QR Code, identity is
 * {@code payer.info} plus the certificate subject, and only the payment system ends a reservation
 * whose window a hash stopped (ADR-0036, ADR-0019, ADR-0021).
 *
 * <p>Runs with the shipped 90-second window. The lapse itself is exercised by
 * {@code ReservationWindowApiTest}, with a shorter one.
 */
class HolderRetryAndIdentityApiTest extends NotificationMatrixApiSupport {

    private static final String HASH = "5VfydnLu4XwV2FquzyFbENPBqbCyJLxq5wRhtZ8rLraBdHfDDmWXnc8nBtCqPU4f";

    /** Wide enough that creating the QR Code and the first pre-commit cannot consume it. */
    private static final int METHOD_SECONDS = 20;

    /** A Solana QR Code whose USDC method stops applying shortly, while the QR Code itself lives on. */
    private String createQRCodeWhoseMethodEndsAt(String methodValidUntil) {
        String body = """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Retry Test", "phone": "+14155550100", "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "retry", "amountDue": { "amount": %d, "currency": "USDC" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "%s", "amount": %d,
                  "networks": { "solana": { "recipient": "%s" } } }
              ]
            }
            """.formatted(AMOUNT, methodValidUntil, AMOUNT, RECIPIENT);

        return given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");
    }

    private static final long FACE = 10_000L;
    private static final long DISCOUNT = 1_000L;

    @AfterEach
    void resetTheClock() {
        BusinessClock.reset();
    }

    /** A FedNow QR Code whose discount ends at {@code dueDate - 10 days}, a whole midnight. */
    private String createDiscountedQRCode(OffsetDateTime dueDate) {
        String body = """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Discount Retry Test", "phone": "+14155550100", "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": {
                "description": "discount retry",
                "invoice": { "number": "INV-RETRY", "date": "%s", "dueDate": "%s" },
                "amountDue": {
                  "amount": %d, "currency": "USD",
                  "adjustments": {
                    "formula": "FixedDiscountLateFeeLinearInterest",
                    "parameters": {
                      "discounts": [ { "daysBefore": 10, "discount": %d, "explanation": "early payment" } ],
                      "lateFees": { "fixed": 0, "perDay": 0, "explanation": "late" }
                    }
                  }
                }
              },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "fednow": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" } } }
              ]
            }
            """.formatted(java.time.LocalDate.now(ZoneOffset.UTC), dueDate.toInstant(), FACE, DISCOUNT, FACE);

        return given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");
    }

    /**
     * <b>X9-HOLD-308</b> — approved at 23:59:50 with the discount, retried at 00:00:02: refused, and the QR Code is freed.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC1, S2.5-AC2), with Story 1.3's payee clock.
     *
     * <p><b>Why:</b> The retry must be judged at the payee's clock when it arrives, not on the
     * strength of the first answer; otherwise a payer keeps a discount the payee stopped granting
     * at midnight, and a refused holder keeps everyone else out until its window lapses.
     */
    @Test
    void aRetryAfterTheDiscountEndedIsRefusedAndReleases() {
        OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).plusDays(40);
        OffsetDateTime discountEnds = dueDate.minusDays(10);
        String qrCodeId = createDiscountedQRCode(dueDate);

        BusinessClock.setAt(discountEnds.minusSeconds(10).toInstant());
        assertEquals(HttpStatus.OK.value(), bank(qrCodeId, "FedNow", null, PAYER_A, FACE - DISCOUNT).statusCode(),
            "at 23:59:50 the discounted amount is accepted");

        BusinessClock.setAt(discountEnds.plusSeconds(2).toInstant());
        MockMvcResponse retry = bank(qrCodeId, "FedNow", null, PAYER_A, FACE - DISCOUNT);

        assertEquals(HttpStatus.BAD_REQUEST.value(), retry.statusCode(),
            "at 00:00:02 the discount has ended: " + retry.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId), "the refused retry released the reservation");
        assertEquals(HttpStatus.OK.value(), bank(qrCodeId, "FedNow", null, PAYER_A, FACE).statusCode(),
            "and the face amount is then accepted");
    }

    private static void sleepUntilAfter(String instant) throws InterruptedException {
        long remaining = Duration.between(OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.parse(instant)).toMillis();
        Thread.sleep(Math.max(remaining, 0) + 500);
    }

    /**
     * <b>X9-HOLD-301</b> — the holder's retry is judged again at the payee's clock, and its refusal frees the QR Code.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC1, S2.5-AC2). X9.150 defines no reservation.
     *
     * <p><b>Why:</b> A retry accepted on the strength of the first answer would let a payer lock in
     * terms the payee no longer grants (here, a payment method that has stopped applying), and a
     * refused holder used to keep everyone else out until its window lapsed. The exact
     * 23:59:50 / 00:00:02 discount scenario needs the movable clock of Story 2.1; this test drives
     * the same rule with a payment method that ends while the reservation holds.
     */
    @Test
    void theHoldersRetryIsJudgedAgainAndItsRefusalFreesTheQRCode() throws InterruptedException {
        // Seconds always present: toString() drops ":00" on a whole minute, which the API refuses.
        String methodEnds = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(METHOD_SECONDS)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
        String qrCodeId = createQRCodeWhoseMethodEndsAt(methodEnds);

        assertEquals(HttpStatus.OK.value(), solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_A).statusCode());

        sleepUntilAfter(methodEnds);
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId), "the 90 s reservation still holds");

        MockMvcResponse retry = solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_A);

        assertEquals(HttpStatus.BAD_REQUEST.value(), retry.statusCode(),
            "the payee's clock decides, on the retry as on the first ask: " + retry.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId), "the refused retry released the reservation");
        assertEquals(List.of("payment.initiated", "payment.failed"), eventTypesFor(qrCodeId),
            "on a chain a release emits payment.failed");
    }

    /**
     * <b>X9-HOLD-302</b> — after a refused retry on a bank rail, the same payer may pre-commit again with the right amount.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC2).
     *
     * <p><b>Why:</b> Releasing on refusal is what lets the payer correct itself (the face amount
     * after the discount ended) instead of waiting out its own reservation.
     */
    @Test
    void afterARefusedRetryTheSamePayerMayPreCommitAgain() {
        String qrCodeId = createQRCode();
        assertEquals(HttpStatus.OK.value(), bank(qrCodeId, "FedNow", null, PAYER_A).statusCode());

        assertEquals(HttpStatus.BAD_REQUEST.value(), bank(qrCodeId, "FedNow", null, PAYER_A, AMOUNT - 1).statusCode());
        assertEquals("ACTIVE", statusOf(qrCodeId));
        assertEquals(List.of("payment.initiated"), eventTypesFor(qrCodeId), "a bank rail releases silently");

        assertEquals(HttpStatus.OK.value(), bank(qrCodeId, "FedNow", null, PAYER_A).statusCode());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
        assertEquals(List.of("payment.initiated", "payment.initiated"), eventTypesFor(qrCodeId));
    }

    /**
     * <b>X9-HOLD-303</b> — a refused pre-commit from a non-holder, or on an ACTIVE QR Code, changes nothing.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC2).
     *
     * <p><b>Why:</b> Only the holder's own refusal releases; otherwise any payer could free another
     * payer's reservation by sending a bad amount.
     */
    @Test
    void aRefusedPreCommitFromANonHolderOrOnActiveChangesNothing() {
        String held = createQRCode();
        bank(held, "FedNow", null, PAYER_A);

        assertEquals(HttpStatus.CONFLICT.value(), bank(held, "FedNow", null, PAYER_B, AMOUNT - 1).statusCode());
        assertEquals("PAYMENT_INITIATED", statusOf(held));
        assertEquals(HttpStatus.OK.value(), bank(held, "FedNow", null, PAYER_A).statusCode(), "A still holds it");

        String active = createQRCode();
        assertEquals(HttpStatus.BAD_REQUEST.value(), bank(active, "FedNow", null, PAYER_A, AMOUNT - 1).statusCode());
        assertEquals("ACTIVE", statusOf(active));
        assertEquals(List.of(), eventTypesFor(active));
    }

    /**
     * <b>X9-HOLD-304</b> — an accepted retry answers 200 and emits nothing.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC3), ADR-0021.
     *
     * <p><b>Why:</b> A second payment.initiated would read as a second payer. That the window moves
     * is pinned by X9-HOLD-030.
     */
    @Test
    void anAcceptedRetryEmitsNothing() {
        String qrCodeId = createQRCode();
        solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_A);

        assertEquals(HttpStatus.OK.value(), solana(qrCodeId, "PAYMENT_INITIATED", null, PAYER_A).statusCode());

        assertEquals(List.of("payment.initiated"), eventTypesFor(qrCodeId));
    }

    /**
     * <b>X9-HOLD-305</b> — the same certificate with a different payer.info is a different payer.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC4).
     *
     * <p><b>Why:</b> Two customers of one PSP sign with one certificate; payer.info is what tells
     * them apart.
     */
    @Test
    void theSameCertificateWithADifferentPayerInfoIsADifferentPayer() {
        String qrCodeId = createQRCode();
        bank(qrCodeId, "RTP", null, PAYER_A);

        assertEquals(HttpStatus.CONFLICT.value(), bank(qrCodeId, "RTP", null, PAYER_B).statusCode());
    }

    /**
     * <b>X9-HOLD-306</b> — a holder without payer.info cannot retry, cannot release, and its post-commit changes nothing.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC4), replacing ADR-0021's certificate-only fallback.
     *
     * <p><b>Why:</b> Without payer.info nothing shows that a retry, a release or a report comes from
     * the payer holding the QR Code rather than from another customer of the same PSP.
     */
    @Test
    void aHolderWithoutPayerInfoCannotRetryReleaseOrReportAsTheHolder() {
        String qrCodeId = createQRCode();
        assertEquals(HttpStatus.OK.value(), solana(qrCodeId, "PAYMENT_INITIATED", null, null).statusCode(),
            "a payer without payer.info may still win a pre-commit");

        assertEquals(HttpStatus.CONFLICT.value(), solana(qrCodeId, "PAYMENT_INITIATED", null, null).statusCode(),
            "its retry");
        assertEquals(HttpStatus.CONFLICT.value(), solana(qrCodeId, "NOT_SENT", null, null).statusCode(),
            "its release");
        assertEquals(HttpStatus.OK.value(), solana(qrCodeId, "SENT", HASH, null).statusCode(), "its report");

        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
        Map<String, Object> sent = lastEventFor(qrCodeId);
        assertEquals("payment.sent", sent.get("type"));
        assertEquals(List.of("RECEIVED_IN_WRONG_STATUS"), sent.get("deviations"));
    }

    /**
     * <b>X9-HOLD-307</b> — once a hash is registered, only the status-update API ends the reservation.
     *
     * <p><b>Source:</b> Ours. ADR-0036 (S2.5-AC6).
     *
     * <p><b>Why:</b> Money has moved; the payment system is the only party that can see whether it
     * arrived, and ACTIVE through status-update is its control over the reservation.
     */
    @Test
    void onceAHashIsRegisteredOnlyTheStatusUpdateApiEndsTheReservation() {
        String reverted = createQRCode();
        solana(reverted, "PAYMENT_INITIATED", null, PAYER_A);
        solana(reverted, "SENT", HASH, PAYER_A);

        statusUpdate(reverted, "{\"status\":\"ACTIVE\"}").then().statusCode(HttpStatus.OK.value());
        assertEquals("ACTIVE", statusOf(reverted));
        assertEquals(HttpStatus.OK.value(), solana(reverted, "PAYMENT_INITIATED", null, PAYER_B).statusCode(),
            "the payment system's revert cleared the reservation");

        String paid = createQRCode();
        solana(paid, "PAYMENT_INITIATED", null, PAYER_A);
        solana(paid, "SENT", HASH, PAYER_A);
        markPaid(paid);
        assertEquals("PAID", statusOf(paid));
    }

}
