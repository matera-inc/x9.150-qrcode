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
 * A bill whose late fee could push an amount past 18 digits before the QR Code expires is refused,
 * at creation and on PATCH (ADR-0005).
 *
 * <p>The due date is one day before {@code validUntil}, so the largest late fee the bill can reach is
 * {@code fixed + perDay × 1}. With {@code fixed = 900} and {@code perDay = 100} that is 1000.
 */
class LateFeeEighteenDigitsApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final long EIGHTEEN_NINES = 999_999_999_999_999_999L;
    private static final long FEE = 1_000;
    private static final String DUE = "2030-12-30T23:59:59Z";
    private static final String VALID_UNTIL = "2030-12-31T23:59:59Z";

    private static String bill(long amount, long fixed) {
        return """
            {
              "description": "late fee bound", "paymentTiming": "immediate",
              "invoice": { "number": "INV-1", "date": "2030-01-10", "dueDate": "%s" },
              "amountDue": { "amount": %d, "currency": "USDC", "adjustments": {
                "formula": "FixedDiscountLateFeeLinearInterest",
                "parameters": { "lateFees": { "fixed": %d, "perDay": 100, "explanation": "late" } } } }
            }
            """.formatted(DUE, amount, fixed);
    }

    private static String method(long amount) {
        return """
            { "currency": "USDC", "validUntil": "%s", "amount": %d,
              "networks": { "solana": { "recipient": "%s" } } }
            """.formatted(VALID_UNTIL, amount, RECIPIENT);
    }

    private static String createBody(long billAmount, long fixed, long methodAmount) {
        return """
            {
              "validUntil": "%s",
              "creditor": {
                "name": "Acme", "phone": "+15552223333", "email": "billing@example.com",
                "address": { "line1": "1 A St", "city": "Los Angeles", "state": "CA",
                             "postalCode": "90012", "country": "US" },
                "MCC": "4900"
              },
              "bill": %s,
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [ %s ]
            }
            """.formatted(VALID_UNTIL, bill(billAmount, fixed), method(methodAmount));
    }

    private static MockMvcResponse create(String body) {
        return given().contentType("application/json").body(body).when().post(CREATE);
    }

    private static String createAccepted(long billAmount, long methodAmount) {
        return create(createBody(billAmount, 900, methodAmount))
            .then().statusCode(HttpStatus.CREATED.value()).extract().path("id");
    }

    private static void assertRefusedNaming(MockMvcResponse response, String field) {
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        List<String> violations = response.jsonPath().getList("violations");
        assertTrue(violations.stream().anyMatch(v -> v.startsWith(field + ":") && v.contains("late fee")),
            "the refusal must name " + field + " and the late fee: " + response.asString());
        assertNull(response.jsonPath().get("id"), "a refused request must not create a QR Code");
    }

    private static String revision(String id) {
        return given().when().get(CREATE + "/" + id).jsonPath().getString("revision");
    }

    /**
     * <b>X9-AMT-124</b> — at creation, the bill plus its largest late fee may reach 18 nines exactly;
     * one more is refused with 400 naming {@code bill.amountDue.amount}, nothing created.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.5.1, §13.5.3.2 — maximum length 18. Conformance; the
     * bound at creation is ours (ADR-0005, owner decision 2026-11-14).
     *
     * <p><b>Why:</b> Each input fits 18 digits alone. Only their sum on the last day does not, and a
     * QR Code whose payload will carry that sum is a QR Code nobody can pay on its last day.
     */
    @Test
    void theBillPlusItsLargestLateFeeIsBoundedAtCreation() {
        createAccepted(EIGHTEEN_NINES - FEE, 1_000);

        assertRefusedNaming(create(createBody(EIGHTEEN_NINES - FEE + 1, 900, 1_000)), "bill.amountDue.amount");
        assertRefusedNaming(create(createBody(EIGHTEEN_NINES - FEE, 901, 1_000)), "bill.amountDue.amount");
    }

    /**
     * <b>X9-AMT-125</b> — at creation, each payment method's amount is bounded as the payload would
     * show it with the largest late fee; refused naming {@code paymentMethods[0].amount}.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §14.3 — maximum length 18. Conformance; the bound is ours
     * (ADR-0005).
     *
     * <p><b>Why:</b> The payload carries the bill's adjustment into each method by ratio, so a
     * method can overflow while the bill does not.
     */
    @Test
    void eachMethodIsBoundedAtCreation() {
        createAccepted(EIGHTEEN_NINES - FEE, EIGHTEEN_NINES - FEE);

        assertRefusedNaming(create(createBody(EIGHTEEN_NINES - FEE, 900, EIGHTEEN_NINES - FEE + 1)),
            "paymentMethods[0].amount");
        // A bill of 1000 with a fee of 1000 doubles every method: half of 10^18 does not fit.
        assertRefusedNaming(create(createBody(1_000, 900, 500_000_000_000_000_000L)), "paymentMethods[0].amount");
    }

    /**
     * <b>X9-AMT-126</b> — PATCH is held to the same bound against the merged state: a later
     * {@code validUntil}, a larger fee or a larger amount is refused with 400 naming the field, and
     * the revision does not move.
     *
     * <p><b>Source:</b> Ours. ADR-0005.
     *
     * <p><b>Why:</b> Each of the three raises the largest amount the bill can show. A bound kept only
     * at creation is undone by the next edit.
     */
    @Test
    void patchIsBoundedAgainstTheMergedState() {
        String id = createAccepted(EIGHTEEN_NINES - FEE, EIGHTEEN_NINES - FEE);
        String before = revision(id);

        String unchangedMethod = method(EIGHTEEN_NINES - FEE);

        MockMvcResponse laterValidUntil = given().contentType("application/json")
            .body("{ \"validUntil\": \"2031-01-01T23:59:59Z\", \"paymentMethods\": [ %s ] }".formatted(unchangedMethod))
            .when().patch(CREATE + "/" + id);
        assertRefusedNaming(laterValidUntil, "bill.amountDue.amount");

        MockMvcResponse largerFee = given().contentType("application/json")
            .body("{ \"bill\": %s, \"paymentMethods\": [ %s ] }".formatted(bill(EIGHTEEN_NINES - FEE, 901), unchangedMethod))
            .when().patch(CREATE + "/" + id);
        assertRefusedNaming(largerFee, "bill.amountDue.amount");

        MockMvcResponse largerMethod = given().contentType("application/json")
            .body("{ \"paymentMethods\": [ %s ] }".formatted(method(EIGHTEEN_NINES - FEE + 1)))
            .when().patch(CREATE + "/" + id);
        assertRefusedNaming(largerMethod, "paymentMethods[0].amount");

        assertEquals(before, revision(id), "a refused PATCH must not move the revision");

        given().contentType("application/json")
            .body("{ \"paymentMethods\": [ %s ] }".formatted(method(EIGHTEEN_NINES - FEE - 1)))
            .when().patch(CREATE + "/" + id)
            .then().statusCode(HttpStatus.OK.value());
    }

}
