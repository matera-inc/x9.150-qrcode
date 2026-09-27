/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import io.restassured.module.mockmvc.response.MockMvcResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three independent validity windows on a payload, and what each one refuses.
 *
 * <p>A payload carries more than one clock, and they expire for different reasons:
 *
 * <table>
 *   <tr><th>Window</th><th>Meaning</th></tr>
 *   <tr><td>{@code $.validUntil}</td><td>The payload itself. Past it, nothing is payable.</td></tr>
 *   <tr><td>{@code $.bill.amountDue.adjustments[].validUntil}</td>
 *       <td>The discount or late-fee window. Past it the <b>amount changes</b>, so a quote taken
 *           under the old window is no longer the right amount.</td></tr>
 *   <tr><td>{@code $.paymentMethods[].validUntil}</td>
 *       <td>The per-currency window — a rate or settlement window. Past it that currency is not
 *           payable even though the payload still is.</td></tr>
 * </table>
 *
 * <p>Each call to the loc URL may build a fresh payload, so the payer's remedy in every case is to
 * fetch again. These tests assert that a stale quote is refused rather than honoured.
 */
class PaymentNotificationValidityWindowApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String NOTIFY = "/pub/api/v1/payment-notification";
    private static final String APPLICATION_JOSE = "application/jose";

    private static final String WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final long FACE_AMOUNT = 10_000_000L;
    private static final long DISCOUNT = 1_000_000L;
    private static final long LATE_FEE_FIXED = 500_000L;

    private static String utc(OffsetDateTime moment) {
        return moment.withNano(0).toString().replace("+00:00", "Z");
    }

    /**
     * @param dueDate            drives which adjustment branch applies: a future due date can earn a
     *                           discount, a past one accrues a late fee
     * @param methodValidUntil   the per-currency window, independent of the payload's own
     */
    private static String qrCodeBody(String validUntil, String dueDate, String methodValidUntil, int daysBefore) {
        // A discount whose target date (dueDate - daysBefore) is already past cannot be CREATED, so
        // a closed discount window is only reachable by letting time pass. daysBefore <= 0 means no
        // discount at all, which is how the late-fee cases avoid that constraint entirely.
        String discounts = daysBefore <= 0
            ? "[]"
            : "[ { \"daysBefore\": %d, \"discount\": %d, \"explanation\": \"early payment\" } ]"
                  .formatted(daysBefore, DISCOUNT);

        return """
            {
              "validUntil": "%s",
              "creditor": {
                "name": "Validity Window Test",
                "phone": "+14155550100",
                "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": {
                "description": "validity windows",
                "invoice": { "number": "INV-1", "date": "%s", "dueDate": "%s" },
                "amountDue": {
                  "amount": %d,
                  "currency": "USDC",
                  "adjustments": {
                    "formula": "FixedDiscountLateFeeLinearInterest",
                    "parameters": {
                      "discounts": %s,
                      "lateFees": { "fixed": %d, "perDay": 0, "explanation": "late" }
                    }
                  }
                }
              },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "%s", "amount": %d,
                  "networks": { "Solana": { "walletAddress": "%s" } } }
              ]
            }
            """.formatted(validUntil, LocalDate.now(ZoneOffset.UTC), dueDate, FACE_AMOUNT, discounts,
                          LATE_FEE_FIXED, methodValidUntil, FACE_AMOUNT, WALLET);
    }

    private String create(String validUntil, String dueDate, String methodValidUntil, int daysBefore) {
        return given().contentType("application/json")
                .body(qrCodeBody(validUntil, dueDate, methodValidUntil, daysBefore))
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");
    }

    /** A QR Code whose invoice falls due in a moment, so the late fee accrues during the test. */
    private String createDueImminently() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // No discount: with the due date seconds away, any daysBefore would put the discount's own
        // target date in the past, which creation refuses.
        return create(utc(now.plusDays(30)), utc(now.plusSeconds(3)), utc(now.plusDays(30)), 0);
    }

    private String sign(String payload) {
        return given().contentType("application/json")
                .header("Correlation-Id", UUID.randomUUID().toString())
                .header("TTL-Seconds", "300")
                .body(payload)
                .when().post("/api/v1/signature/generate")
                .then().statusCode(HttpStatus.OK.value())
                .extract().body().asString();
    }

    private MockMvcResponse notify(String qrCodeId, long amount) {
        String body = """
            {
              "payment": { "qrcodeId": "%s", "amount": %d, "currency": "USDC", "network": "Solana" },
              "expectedDate": "2030-10-08T06:59:59Z",
              "blockchain": { "action": "PAYMENT_INITIATED", "to": "%s", "from": "%s" }
            }
            """.formatted(qrCodeId, amount, WALLET, WALLET);

        return given().contentType(APPLICATION_JOSE).body(sign(body)).when().post(NOTIFY);
    }

    private String statusOf(String qrCodeId) {
        return given().when().get(CREATE + "/" + qrCodeId)
                .then().statusCode(HttpStatus.OK.value())
                .extract().path("status");
    }

    // ------------------------------------------- window 2: the adjustment (discount / late fee)

    /**
     * The discount is live, so the discounted amount is what a payer would be quoted — and what they
     * may pay.
     */
    @Test
    void aDiscountedAmountIsAcceptedWhileTheDiscountWindowIsOpen() {
        OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(120);
        String qrCodeId = create(utc(dueDate.plusDays(1)), utc(dueDate), utc(dueDate), 60);

        MockMvcResponse response = notify(qrCodeId, FACE_AMOUNT - DISCOUNT);

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
    }

    /**
     * The scenario worth having: a payer scanned earlier, was quoted a discounted amount, and comes
     * back after the discount window closed. The amount they hold is no longer the amount owed.
     */
    @Test
    void anAmountCalculatedWithAnExpiredDiscountIsRefused() throws InterruptedException {
        // The discount earns until (dueDate - 1 day), which is three seconds away. The bill itself
        // is not due for another day, so when the window shuts the amount returns to face value
        // rather than accruing a late fee — isolating the discount window from the due date.
        OffsetDateTime dueDate = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).plusSeconds(3);
        String qrCodeId = create(utc(dueDate.plusDays(1)), utc(dueDate), utc(dueDate.plusDays(1)), 1);

        Thread.sleep(3500);

        MockMvcResponse response = notify(qrCodeId, FACE_AMOUNT - DISCOUNT);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(),
                "a discount that is no longer earnable must not be honoured: " + response.asString());
        assertTrue(response.asString().contains("amount"), response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId));

        // The amount actually owed is still payable — the QR Code is not poisoned, only the stale quote.
        assertEquals(HttpStatus.OK.value(), notify(qrCodeId, FACE_AMOUNT).statusCode());
    }

    /**
     * Past the due date a late fee accrues, so the face amount is now an <b>underpayment</b>. A quote
     * taken before the due date does not entitle the payer to pay less than is owed.
     */
    @Test
    void theFaceAmountIsRefusedOnceALateFeeHasAccrued() throws InterruptedException {
        // A past due date cannot be created — the invoice refuses it — so the only honest way to
        // reach the late-fee branch is to create a due date moments away and let it pass, exactly as
        // a real bill does.
        String qrCodeId = createDueImminently();

        Thread.sleep(3500);

        MockMvcResponse response = notify(qrCodeId, FACE_AMOUNT);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(),
                "paying the face amount after a late fee accrued is an underpayment: " + response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

    @Test
    void theAmountIncludingTheLateFeeIsAccepted() throws InterruptedException {
        String qrCodeId = createDueImminently();

        Thread.sleep(3500);

        MockMvcResponse response = notify(qrCodeId, FACE_AMOUNT + LATE_FEE_FIXED);

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
    }

    // ------------------------------------------------- window 3: the per-currency payment method

    /**
     * The per-currency window is independent of the payload's. It exists for rate and settlement
     * windows — "I will take this currency for a few minutes, after which you must fetch again" —
     * so a payload that is still perfectly valid can carry a currency that is not.
     *
     * <p>Today's 1:1 currencies rarely need it, and a QR Code often reuses the payload's own
     * validUntil. The rule still has to hold for the day an exchanged currency does need it.
     */
    @Test
    void aCurrencyWhoseWindowClosedIsRefusedEvenThoughThePayloadIsStillValid() throws InterruptedException {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String methodExpiresSoon = utc(now.plusSeconds(3));
        String payloadValidForAges = utc(now.plusDays(30));

        String qrCodeId = create(payloadValidForAges, utc(now.plusDays(20)), methodExpiresSoon, 0);

        Thread.sleep(3500);

        MockMvcResponse response = notify(qrCodeId, FACE_AMOUNT);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(),
                "the currency's own window closed, so it is not payable: " + response.asString());
        assertTrue(response.asString().contains("expired") || response.asString().contains("validUntil"),
                "the reason should point at the payment method's window: " + response.asString());

        // The payload itself is untouched and still valid — only that currency lapsed.
        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

}
