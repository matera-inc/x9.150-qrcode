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

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reducing what a QR Code asks for, which is the natural response to a partial payment.
 *
 * <p>A bill for 22500 receives 10000; the QR Code should keep its location — so the code already in
 * the payer's hands still works — and ask for the 12500 still owed. The amount is the only thing
 * that changes: creditor, rails and memo all stay exactly as they were.
 *
 * <p>That was the one edit PATCH could not express. The entity compares the old payment methods with
 * the new ones to decide whether anything changed, and {@code AmountVO} inherited a Lombok
 * {@code @EqualsAndHashCode} that compared no fields at all — so two different amounts were equal,
 * the change looked like a no-op, and the request was refused with "can not find any paymentMethod
 * to be updated". The only way through was to alter the networks as well, which meant dropping the
 * QRCD memo and leaving a window where an unannounced on-chain payment could not be matched.
 *
 * <p>Reported by an adopter implementing partial payments. See {@code ValueObjectEqualityTest} for
 * the root cause, which reached all fourteen value objects rather than just this one.
 */
class PatchAmountOnlyApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final String MEMO = "{QRCD:\\\"PARTIAL-1\\\"}";

    private String createQRCode() {
        String body = """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Acme", "phone": "+15552223333", "email": "billing@example.com",
                "address": { "line1": "1 A St", "city": "Los Angeles", "state": "CA",
                             "postalCode": "90012", "country": "US" },
                "MCC": "4900"
              },
              "bill": {
                "description": "partial payment",
                "invoice": { "number": "INV-P-1", "date": "2030-01-10", "dueDate": "2030-12-31T23:59:59Z" },
                "amountDue": { "amount": 22500, "currency": "USDC" }
              },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 22500,
                  "networks": { "solana": { "recipient": "%s", "memo": "%s" } } }
              ]
            }
            """.formatted(RECIPIENT, MEMO);

        return given().contentType("application/json").body(body)
                .when().post(CREATE).then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");
    }

    /** Everything identical except the amount — the shape a partial payment produces. */
    private MockMvcResponse patchAmountTo(String id, long amount) {
        String body = """
            { "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "solana": { "recipient": "%s", "memo": "%s" } } } ] }
            """.formatted(amount, RECIPIENT, MEMO);

        return given().contentType("application/json").body(body)
                .when().patch(CREATE + "/" + id);
    }

    private String field(String id, String path) {
        return given().when().get(CREATE + "/" + id)
                .then().statusCode(HttpStatus.OK.value())
                .extract().path(path).toString();
    }

    @Test
    void theAmountAloneCanBeReduced() {
        String id = createQRCode();

        MockMvcResponse response = patchAmountTo(id, 12500L);

        assertEquals(HttpStatus.OK.value(), response.statusCode(),
                "a smaller amount is a change, not a no-op: " + response.asString());
        assertEquals("12500", field(id, "paymentMethods[0].amount"));
    }

    /** The point of using PATCH at all: the code already printed must keep working. */
    @Test
    void theMemoAndRecipientSurviveAnAmountOnlyPatch() {
        String id = createQRCode();

        patchAmountTo(id, 12500L);

        assertEquals(RECIPIENT, field(id, "paymentMethods[0].networks.solana.recipient"));
        assertEquals("{QRCD:\"PARTIAL-1\"}", field(id, "paymentMethods[0].networks.solana.memo"),
                "the memo must not have to be dropped to get an amount change through");
    }

    /** A second reduction, because the first one only proves the guard can be passed once. */
    @Test
    void theAmountCanBeReducedAgain() {
        String id = createQRCode();

        patchAmountTo(id, 12500L);
        MockMvcResponse second = patchAmountTo(id, 11000L);

        assertEquals(HttpStatus.OK.value(), second.statusCode(), second.asString());
        assertEquals("11000", field(id, "paymentMethods[0].amount"));
    }

    /** The guard still has a job: a PATCH that genuinely changes nothing is still refused. */
    @Test
    void aPatchThatChangesNothingIsStillRefused() {
        String id = createQRCode();

        MockMvcResponse response = patchAmountTo(id, 22500L);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(),
                "identical methods are still a no-op and must still be rejected: " + response.asString());
    }

}
