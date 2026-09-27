/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The emitted network key is configuration, and this is the test that proves it.
 *
 * <p>ANSI X9.150-2026 contradicts itself on case — §14.5's normative paths are lower-case
 * ({@code networks.fednow}) while §2.4 calls its values "all-uppercase" and then lists {@code FedNow}
 * — so implementers will read one or the other and a partner may well expect the spelling we do not
 * emit. That has to be fixable by a deploy, not a release.
 *
 * <p>The override moves both halves at once, which is the property worth pinning: the configured
 * key is what we emit <em>and</em> what our own API requires on input, so a caller always sends what
 * they will read back. Note the request body below — it writes {@code FedNow} and {@code ACH}
 * because that is what this deployment is configured to use, and {@code rtp} because that rail has
 * no override.
 *
 * <p>{@link NetworkNamingApiTest} covers the default. Without this file the property could go on
 * existing and doing nothing, which is exactly what it did before.
 */
@TestPropertySource(properties = {
    "x9.networks.emitted-keys.fednow=FedNow",
    "x9.networks.emitted-keys.ach=ACH"
})
class ConfiguredNetworkKeyApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";

    private static String qrCodeBody() {
        return """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Configured Key Test",
                "phone": "+14155550100",
                "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "configured key", "amountDue": { "amount": 5000, "currency": "USD" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 5000,
                  "networks": {
                    "FedNow": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" },
                    "rtp":    { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" },
                    "ACH":    { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" }
                  } }
              ]
            }
            """;
    }

    private JsonPath createAndRead() {
        String id = given().contentType("application/json").body(qrCodeBody())
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");

        return given().when().get(CREATE + "/" + id)
                .then().statusCode(HttpStatus.OK.value())
                .extract().jsonPath();
    }

    @Test
    void anOverriddenRailIsEmittedUnderTheConfiguredKey() {
        JsonPath qrCode = createAndRead();

        assertEquals("021000021", qrCode.getString("paymentMethods[0].networks.FedNow.routingNumber"),
                "fednow was configured to emit as FedNow: " + qrCode.prettify());
        assertEquals("021000021", qrCode.getString("paymentMethods[0].networks.ACH.routingNumber"),
                "ach was configured to emit as ACH: " + qrCode.prettify());
    }

    /** One spelling out, not two — the override replaces the default, it does not add to it. */
    @Test
    void theDefaultSpellingIsNotAlsoEmitted() {
        JsonPath qrCode = createAndRead();

        assertNull(qrCode.get("paymentMethods[0].networks.fednow"), qrCode.prettify());
        assertNull(qrCode.get("paymentMethods[0].networks.ach"), qrCode.prettify());
    }

    /** A rail with no override keeps the normative lower-case spelling. */
    @Test
    void aRailWithoutAnOverrideIsUnaffected() {
        JsonPath qrCode = createAndRead();

        assertEquals("021000021", qrCode.getString("paymentMethods[0].networks.rtp.routingNumber"),
                "rtp had no override, so it stays lower-case: " + qrCode.prettify());
    }

    /**
     * The rule cuts both ways. With {@code FedNow} configured, the otherwise-normative
     * {@code fednow} is the wrong spelling for this deployment and is refused like any other.
     */
    @Test
    void theDefaultSpellingIsRefusedOnceOverridden() {
        String body = qrCodeBody().replace("\"FedNow\":", "\"fednow\":");

        String response = given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.BAD_REQUEST.value())
                .extract().asString();

        assertTrue(response.contains("FedNow"),
                "the refusal must name the spelling this deployment expects: " + response);
    }

}
