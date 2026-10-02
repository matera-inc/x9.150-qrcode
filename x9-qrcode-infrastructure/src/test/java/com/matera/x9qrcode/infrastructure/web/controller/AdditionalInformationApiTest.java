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

/**
 * {@code additionalInformation} is a list of labelled lines, and every one of them survives.
 *
 * <p>ANSI X9.150 names the first element {@code key}, and reading that as a map key is what broke
 * it: the entries were stored in a {@code Map<String, String>}, so repeats were silently discarded.
 * A biller recording three partial payments under one label got one of them, with a 201 and nothing
 * to say the rest were gone — and the two paths did not even agree which survived, create keeping
 * the last and patch the first.
 *
 * <p>These lines are shown to the payer as the explanation of what is owed, so a dropped one leaves
 * a history that does not add up to the amount being asked for.
 */
class AdditionalInformationApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";

    private MockMvcResponse create(String additionalInformation) {
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
                "description": "balance after partial payments",
                "invoice": { "number": "INV-AI", "date": "2030-01-10", "dueDate": "2030-12-31T23:59:59Z" },
                "amountDue": { "amount": 12500, "currency": "USDC" }
              },
              "additionalInformation": %s,
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 12500,
                  "networks": { "solana": { "recipient": "%s" } } }
              ]
            }
            """.formatted(additionalInformation, RECIPIENT);

        return given().contentType("application/json").body(body).when().post(CREATE);
    }

    private List<String> keysOf(String id) {
        return given().when().get(CREATE + "/" + id)
                .then().statusCode(HttpStatus.OK.value())
                .extract().jsonPath().getList("additionalInformation.key");
    }

    private List<String> valuesOf(String id) {
        return given().when().get(CREATE + "/" + id)
                .then().statusCode(HttpStatus.OK.value())
                .extract().jsonPath().getList("additionalInformation.value");
    }

    // ------------------------------------------------------------------ repeats are legitimate

    /**
     * <b>X9-AMT-050</b> — the same additionalInformation label may appear more than once.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §16 — additionalInformation is a list of labelled lines. The field is named
     * "key", but it is a LABEL, not a unique index. Conformance.
     *
     * <p><b>Why:</b> Stored as a map once, so two rows sharing a label silently lost one. A bill legitimately
     * carries two "Partial Payment" lines, and losing one changes what the payer is told they owe.
     */
    @Test
    void theSameLabelMayAppearMoreThanOnce() {
        String id = create("""
            [ { "key": "Partial payment", "value": "2026-09-20: 5000 USDC" },
              { "key": "Partial payment", "value": "2026-09-25: 5000 USDC" } ]
            """).then().statusCode(HttpStatus.CREATED.value()).extract().path("id");

        assertEquals(List.of("Partial payment", "Partial payment"), keysOf(id),
                "a map kept one of these; they are two payments, not one");
        assertEquals(List.of("2026-09-20: 5000 USDC", "2026-09-25: 5000 USDC"), valuesOf(id));
    }

    /**
     * <b>X9-AMT-051</b> — the order sent is the order kept.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §16 — an ordered list. Conformance.
     *
     * <p><b>Why:</b> These lines are read by a human in sequence. A map lost the order as well as the duplicates.
     */
    @Test
    void theOrderSentIsTheOrderKept() {
        String id = create("""
            [ { "key": "Original amount", "value": "22500 USDC" },
              { "key": "Partial payment", "value": "5000 USDC" },
              { "key": "Partial payment", "value": "5000 USDC" },
              { "key": "Balance now due", "value": "12500 USDC" } ]
            """).then().statusCode(HttpStatus.CREATED.value()).extract().path("id");

        assertEquals(
                List.of("Original amount", "Partial payment", "Partial payment", "Balance now due"),
                keysOf(id));
    }

    /**
     * <b>X9-AMT-052</b> — every line reaches the QR Code as stored.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> Checks the payload a payer actually receives, not just the stored document. A round trip that
     * is correct in the database and lossy on the way out is still lossy.
     */
    @Test
    void everyLineReachesTheQRCodeAsStored() {
        String id = create("""
            [ { "key": "Partial payment", "value": "first" },
              { "key": "Partial payment", "value": "second" } ]
            """).then().statusCode(HttpStatus.CREATED.value()).extract().path("id");

        assertEquals(2, keysOf(id).size());
        assertEquals(List.of("first", "second"), valuesOf(id));
    }

    // ------------------------------------------------------------------------- patch keeps them

    /**
     * <b>X9-AMT-053</b> — a patch also keeps repeated labels.
     *
     * <p><b>Source:</b> Conformance, as AMT-050.
     *
     * <p><b>Why:</b> Creation and editing took different paths to the same field. Fixing only one would leave the
     * second partial payment overwriting the first — the exact case the adopter needed.
     */
    @Test
    void aPatchAlsoKeepsRepeats() {
        String id = create("""
            [ { "key": "Partial payment", "value": "first" } ]
            """).then().statusCode(HttpStatus.CREATED.value()).extract().path("id");

        String patch = """
            { "additionalInformation": [
                { "key": "Partial payment", "value": "first" },
                { "key": "Partial payment", "value": "second" } ],
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 7500,
                  "networks": { "solana": { "recipient": "%s" } } } ] }
            """.formatted(RECIPIENT);

        given().contentType("application/json").body(patch)
                .when().patch(CREATE + "/" + id).then().statusCode(HttpStatus.OK.value());

        assertEquals(List.of("first", "second"), valuesOf(id),
                "patch kept the FIRST duplicate where create kept the last; now both keep everything");
    }

}
