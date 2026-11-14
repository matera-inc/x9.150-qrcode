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

import java.util.List;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amounts carry at most 18 digits, on every endpoint that takes one (ADR-0005).
 *
 * <p>ANSI X9.150-2026 gives every amount field a maximum length of 18: the bill (§13.5.1), the
 * adjustment (§13.5.3.2), the payment method and its editable range (§14.3, §14.4.1), and the
 * notification's amount and tip amount (§2.1, §2.2). A 64-bit integer holds 19 digits, so the
 * bound is the standard's, not the type's. The contract states it as {@code maximum} on each
 * amount schema, and the refusal names the field.
 */
class AmountEighteenDigitsApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final long EIGHTEEN_NINES = 999_999_999_999_999_999L;
    private static final long NINETEEN_DIGITS = 1_000_000_000_000_000_000L;
    /** Nothing listens here: a request that got past validation would fail to deliver, not 400. */
    private static final String DEAD_PAYEE = "http://127.0.0.1:9/pub/api/v1/payment-notification";

    private static String createBody(long billAmount, long methodAmount) {
        return """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Acme", "phone": "+15552223333", "email": "billing@example.com",
                "address": { "line1": "1 A St", "city": "Los Angeles", "state": "CA",
                             "postalCode": "90012", "country": "US" },
                "MCC": "4900"
              },
              "bill": {
                "description": "eighteen digits",
                "amountDue": { "amount": %d, "currency": "USDC" }
              },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "solana": { "recipient": "%s" } } }
              ]
            }
            """.formatted(billAmount, methodAmount, RECIPIENT);
    }

    private static MockMvcResponse create(String body) {
        return given().contentType("application/json").body(body).when().post(CREATE);
    }

    private static void assertRefusedNaming(MockMvcResponse response, String field) {
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        List<String> violations = response.jsonPath().getList("violations");
        assertTrue(violations.stream().anyMatch(v -> v.startsWith(field + ":")),
            "the refusal must name " + field + ": " + response.asString());
        assertNull(response.jsonPath().get("id"), "a refused request must not create a QR Code");
    }

    /**
     * <b>X9-AMT-120</b> — 18 nines is accepted at creation and read back to the unit.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.5.1 and §14.3 — maximum length 18. Conformance.
     *
     * <p><b>Why:</b> The control for X9-AMT-121: the largest amount the standard allows must still
     * get through, exactly, or the bound is one digit too tight.
     */
    @Test
    void eighteenNinesIsAcceptedAndKeptExactly() {
        String id = create(createBody(EIGHTEEN_NINES, EIGHTEEN_NINES))
            .then().statusCode(HttpStatus.CREATED.value()).extract().path("id");

        MockMvcResponse read = given().when().get(CREATE + "/" + id);

        assertEquals(String.valueOf(EIGHTEEN_NINES),
            read.jsonPath().getString("bill.amountDue.amount"));
        assertEquals(String.valueOf(EIGHTEEN_NINES),
            read.jsonPath().getString("paymentMethods[0].amount"));
    }

    /**
     * <b>X9-AMT-121</b> — a 19-digit amount is refused at creation, naming the field.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.5.1 and §14.3 — maximum length 18. Conformance.
     * Before 2026-11-14 Java accepted it (ADR-0005, "conformance gap found").
     *
     * <p><b>Why:</b> A QR Code asking for an amount no conforming payer can hold is a QR Code nobody
     * can pay, issued as if it were valid.
     */
    @Test
    void a19DigitAmountIsRefusedAtCreation() {
        assertRefusedNaming(create(createBody(NINETEEN_DIGITS, EIGHTEEN_NINES)), "bill.amountDue.amount");
        assertRefusedNaming(create(createBody(EIGHTEEN_NINES, NINETEEN_DIGITS)), "paymentMethods[0].amount");
    }

    /**
     * <b>X9-AMT-122</b> — a 19-digit amount is refused by PATCH, naming the field, and nothing moves.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §14.3 — maximum length 18. Conformance.
     *
     * <p><b>Why:</b> A bound enforced at creation and not on PATCH is not a bound: the next edit
     * would put the 19-digit amount in the payload anyway.
     */
    @Test
    void a19DigitAmountIsRefusedByPatch() {
        String id = create(createBody(1000, 1000))
            .then().statusCode(HttpStatus.CREATED.value()).extract().path("id");
        String before = given().when().get(CREATE + "/" + id).jsonPath().getString("revision");

        MockMvcResponse response = given().contentType("application/json").body("""
            { "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "solana": { "recipient": "%s" } } } ] }
            """.formatted(NINETEEN_DIGITS, RECIPIENT)).when().patch(CREATE + "/" + id);

        assertRefusedNaming(response, "paymentMethods[0].amount");
        assertEquals(before, given().when().get(CREATE + "/" + id).jsonPath().getString("revision"));
    }

    /**
     * <b>X9-AMT-123</b> — a payer cannot send a 19-digit amount or tip amount to a payee.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §2.1 and §2.2 — maximum length 18. Conformance.
     *
     * <p><b>Why:</b> This service also acts for the payer. A notification it would itself refuse as
     * a payee must not leave it as a payer: the payee would refuse it anyway, after a round trip.
     */
    @Test
    void aPayerCannotSendA19DigitAmount() {
        for (String payment : List.of(
            "\"amount\": %d".formatted(NINETEEN_DIGITS),
            "\"amount\": 25000, \"tipAmount\": %d".formatted(NINETEEN_DIGITS))) {
            MockMvcResponse response = given().contentType("application/json").body("""
                {
                  "endpoint": "%s",
                  "notification": {
                    "payment": { "qrcodeId": "01A0E3A12805CB382EF4687F18CDC43A", %s,
                                 "currency": "USD", "network": "ACH" },
                    "payer": { "info": "Jane Payer" },
                    "expectedDate": "2030-10-08T06:59:59Z"
                  }
                }
                """.formatted(DEAD_PAYEE, payment)).when().post("/api/v1/payment-notification/pre-payment");

            String field = payment.contains("tipAmount") ? "notification.payment.tipAmount" : "notification.payment.amount";
            assertRefusedNaming(response, field);
        }
    }

}
