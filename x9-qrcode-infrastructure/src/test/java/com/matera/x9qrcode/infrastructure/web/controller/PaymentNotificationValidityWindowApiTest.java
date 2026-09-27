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
 * The validity windows a payer meets through the API.
 *
 * <p>The rules that depend on <em>when</em> a notification arrives — a discount window closing, a
 * late fee accruing, a currency lapsing — live in {@code PaymentNotificationAcceptancePolicyTest}
 * instead, where the instant is a parameter. Driving those through HTTP means building a QR Code
 * whose window shuts seconds later and then sleeping, which is slow and genuinely flaky: a loaded
 * runner can spend those seconds inside the create request and invalidate the fixture before the
 * test has begun. That is a test measuring the CI machine, not the rule.
 *
 * <p>What remains here is what HTTP actually adds: the status code, and that a refusal changes
 * nothing.
 *
 * <p>The three independent validity windows on a payload, and what each one refuses.
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

    private static final String ACH = "\"ach\": { \"routingNumber\": \"021000021\", \"accountNumber\": \"1234567890\", \"protectionType\": \"tokenized\" }";
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
                  "currency": "USD",
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
                { "currency": "USD", "validUntil": "%s", "amount": %d, "networks": { %s } }
              ]
            }
            """.formatted(validUntil, LocalDate.now(ZoneOffset.UTC), dueDate, FACE_AMOUNT, discounts,
                          LATE_FEE_FIXED, methodValidUntil, FACE_AMOUNT, ACH);
    }

    private String create(String validUntil, String dueDate, String methodValidUntil, int daysBefore) {
        return given().contentType("application/json")
                .body(qrCodeBody(validUntil, dueDate, methodValidUntil, daysBefore))
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");
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
              "payment": { "qrcodeId": "%s", "amount": %d, "currency": "USD", "network": "ACH",
                           "transactionId": "021000021.0000001" },
              "payer": { "info": "Jane Payer, Springfield Savings" },
              "expectedDate": "2030-10-08T06:59:59Z"
            }
            """.formatted(qrCodeId, amount);

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




    // ------------------------------------------------------------- the payer-chosen amount

    private String createEditable(long min, long max) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String body = """
            {
              "validUntil": "%s",
              "creditor": {
                "name": "Editable Test",
                "phone": "+14155550100",
                "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "donation", "amountDue": { "amount": %d, "currency": "USD" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "%s", "amount": %d,
                  "editable": { "range": { "min": %d, "max": %d } },
                  "networks": { %s } }
              ]
            }
            """.formatted(utc(now.plusDays(30)), FACE_AMOUNT, utc(now.plusDays(30)), FACE_AMOUNT, min, max, ACH);

        return given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");
    }

    /**
     * When the payload advertises {@code editable.range}, the payer picks the amount — a donation, a
     * top-up, an open tab. Demanding the face amount back would refuse every legitimate use of the
     * feature.
     */
    @Test
    void anAmountThePayerChoseWithinThePublishedRangeIsAccepted() {
        String qrCodeId = createEditable(1_000_000L, 50_000_000L);

        MockMvcResponse response = notify(qrCodeId, 7_777_777L);

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
    }

    @Test
    void anAmountBelowThePublishedRangeIsRefused() {
        String qrCodeId = createEditable(1_000_000L, 50_000_000L);

        MockMvcResponse response = notify(qrCodeId, 999_999L);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertTrue(response.asString().contains("editable"), response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

    @Test
    void anAmountAboveThePublishedRangeIsRefused() {
        String qrCodeId = createEditable(1_000_000L, 50_000_000L);

        MockMvcResponse response = notify(qrCodeId, 50_000_001L);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

    // ------------------------------------------------- window 3: the per-currency payment method


}
