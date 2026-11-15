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
 * Three fields the contract gives a default: {@code bill.paymentTiming} and {@code bill.tip.allowed}
 * on create, {@code bill.tip.allowed} on PATCH.
 *
 * <p>They are optional: absent, the default is taken. They are not nullable: an explicit
 * {@code null} is not "absent" and is refused, so a caller who sends one learns it rather than
 * having it silently read as the default (or, on PATCH, as "no tips").
 */
class DefaultedBillFieldsApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";

    /** A create body whose bill carries {@code billExtra} (a JSON fragment, may be empty). */
    private static String createBody(String billExtra) {
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
                %s
                "description": "defaults",
                "invoice": { "number": "INV-D-1", "date": "2030-01-10", "dueDate": "2030-12-31T23:59:59Z" },
                "amountDue": { "amount": 22500, "currency": "USDC" }
              },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 22500,
                  "networks": { "solana": { "recipient": "%s" } } }
              ]
            }
            """.formatted(billExtra, RECIPIENT);
    }

    /** A PATCH restating the bill with {@code tip} (a JSON value). */
    private static String patchBody(String tip) {
        return """
            {
              "bill": {
                "description": "defaults",
                "paymentTiming": "immediate",
                "invoice": { "number": "INV-D-1", "date": "2030-01-10", "dueDate": "2030-12-31T23:59:59Z" },
                "amountDue": { "amount": 22500 },
                "tip": %s
              },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 22500,
                  "networks": { "solana": { "recipient": "%s" } } }
              ]
            }
            """.formatted(tip, RECIPIENT);
    }

    private MockMvcResponse create(String billExtra) {
        return given().contentType("application/json").body(createBody(billExtra)).when().post(CREATE);
    }

    private String created(String billExtra) {
        MockMvcResponse response = create(billExtra);
        assertEquals(HttpStatus.CREATED.value(), response.statusCode(), response.asString());
        return response.jsonPath().getString("id");
    }

    private MockMvcResponse patch(String id, String tip) {
        return given().contentType("application/json").body(patchBody(tip)).when().patch(CREATE + "/" + id);
    }

    private String field(String id, String path) {
        return given().when().get(CREATE + "/" + id)
                .then().statusCode(HttpStatus.OK.value())
                .extract().jsonPath().getString(path);
    }

    /**
     * <b>X9-LIFE-140</b> — an absent {@code bill.paymentTiming} on create defaults to {@code immediate}.
     *
     * <p><b>Source:</b> Ours (openapi.yaml {@code BillBase.paymentTiming}, default {@code immediate}).
     *
     * <p><b>Why:</b> The contract once marked it required as well as defaulted, and the service has
     * always taken the default. The contract now says what the service does; this pins it.
     */
    @Test
    void anAbsentPaymentTimingDefaultsToImmediate() {
        String id = created("");

        assertEquals("immediate", field(id, "bill.paymentTiming"));
    }

    /**
     * <b>X9-LIFE-141</b> — an explicit {@code "paymentTiming": null} on create is refused.
     *
     * <p><b>Source:</b> Ours. Optional is not nullable.
     *
     * <p><b>Why:</b> Absent and null are different requests; reading a null as the default would hide
     * a caller's bug behind a value they did not choose.
     */
    @Test
    void aNullPaymentTimingIsRefused() {
        MockMvcResponse response = create("\"paymentTiming\": null,");

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
    }

    /**
     * <b>X9-TIP-130</b> — a {@code tip} without {@code allowed} on create allows tips.
     *
     * <p><b>Source:</b> Ours (openapi.yaml {@code BillBase.tip.allowed}, default {@code true}).
     *
     * <p><b>Why:</b> As LIFE-140: the default the service has always applied, now stated by the
     * contract instead of contradicted by a {@code required}.
     */
    @Test
    void aTipWithoutAllowedOnCreateAllowsTips() {
        String id = created("\"tip\": { \"range\": { \"min\": 0, \"max\": 500 } },");

        assertEquals("true", field(id, "bill.tip.allowed"));
    }

    /**
     * <b>X9-TIP-131</b> — an explicit {@code "allowed": null} in a create's {@code tip} is refused.
     *
     * <p><b>Source:</b> Ours. Optional is not nullable.
     *
     * <p><b>Why:</b> Whether a payer may add money to a bill is not something to guess from a null.
     */
    @Test
    void aNullAllowedOnCreateIsRefused() {
        MockMvcResponse response = create("\"tip\": { \"allowed\": null },");

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
    }

    /**
     * <b>X9-TIP-132</b> — a PATCH {@code tip} without {@code allowed} allows tips.
     *
     * <p><b>Source:</b> Ours (openapi.yaml {@code PatchTip.allowed}, default {@code true}).
     *
     * <p><b>Why:</b> As TIP-130, on the other path that writes a tip.
     */
    @Test
    void aTipWithoutAllowedOnPatchAllowsTips() {
        String id = created("\"tip\": { \"allowed\": false },");

        MockMvcResponse response = patch(id, "{ \"range\": { \"min\": 0, \"max\": 500 } }");

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("true", field(id, "bill.tip.allowed"));
    }

    /**
     * <b>X9-TIP-133</b> — an explicit {@code "allowed": null} in a PATCH {@code tip} is refused.
     *
     * <p><b>Source:</b> Ours. Optional is not nullable.
     *
     * <p><b>Why:</b> On PATCH a null {@code allowed} would otherwise read as "no tips" and quietly
     * withdraw them from a live bill.
     */
    @Test
    void aNullAllowedOnPatchIsRefused() {
        String id = created("");

        MockMvcResponse response = patch(id, "{ \"allowed\": null }");

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
    }
}
