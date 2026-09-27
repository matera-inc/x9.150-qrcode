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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How network names are read and written.
 *
 * <p>ANSI X9.150-2026 contradicts itself on case. §14.5's normative JSON paths are lowercase —
 * {@code $.paymentMethods[].networks.fednow} — and the standard's own example payload agrees. But
 * §2.4, defining the notification's network value, calls it "all-uppercase" and then lists
 * {@code FedNow}, which is not. Implementers will read one or the other.
 *
 * <p>So: accept every spelling, emit one. And since the emitted form is what a partner's parser sees,
 * it is configurable — an interoperability problem should be a config change, not a release.
 */
class NetworkNamingApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";

    private static String qrCodeWithNetworkKey(String fedNowKey) {
        return """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Naming Test",
                "phone": "+14155550100",
                "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "naming", "amountDue": { "amount": 5000, "currency": "USD" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 5000,
                  "networks": { "%s": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" } } }
              ]
            }
            """.formatted(fedNowKey);
    }

    private JsonPath createAndRead(String body) {
        String id = given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");

        return given().when().get(CREATE + "/" + id)
                .then().statusCode(HttpStatus.OK.value())
                .extract().jsonPath();
    }

    /**
     * Every spelling a conformant implementer might send, reading either half of the standard.
     * All of them mean the same rail, and all must be recognised <em>as</em> that rail — not carried
     * off into additionalProperties as an unknown network.
     */
    @ParameterizedTest(name = "a QR Code sent with networks.{0} is understood")
    @ValueSource(strings = {"fednow", "FedNow", "FEDNOW", "fedNow"})
    void aStandardRailIsAcceptedUnderAnySpelling(String spelling) {
        JsonPath qrCode = createAndRead(qrCodeWithNetworkKey(spelling));

        assertNotNull(qrCode.get("paymentMethods[0].networks.fednow"),
                "%s must be read as the FedNow rail: %s".formatted(spelling, qrCode.prettify()));
        assertEquals("021000021", qrCode.getString("paymentMethods[0].networks.fednow.routingNumber"));
    }

    /** Whatever came in, one form goes out — the normative lowercase path. */
    @ParameterizedTest(name = "networks.{0} is emitted as networks.fednow")
    @ValueSource(strings = {"fednow", "FedNow", "FEDNOW"})
    void theEmittedKeyIsAlwaysTheConfiguredOne(String spelling) {
        JsonPath qrCode = createAndRead(qrCodeWithNetworkKey(spelling));

        assertNotNull(qrCode.get("paymentMethods[0].networks.fednow"));
        assertNull(qrCode.get("paymentMethods[0].networks.FedNow"),
                "only the configured spelling should be emitted: " + qrCode.prettify());
    }

    /**
     * A rail sent under an odd spelling must not ALSO appear as an uninterpreted network — that
     * would advertise the same account twice, once validated and once not.
     */
    @Test
    void aPromotedRailIsNotAlsoCarriedAsAnUnknownNetwork() {
        JsonPath qrCode = createAndRead(qrCodeWithNetworkKey("FEDNOW"));

        assertNull(qrCode.get("paymentMethods[0].networks.FEDNOW"),
                "the rail was promoted, so it must not linger in additionalProperties: " + qrCode.prettify());
    }

    /**
     * The standard defines three rails. Everything else — a chain whose owner published an
     * embedding, Pix, Zelle — is carried verbatim with no configuration at all, which is what makes
     * the open set work (§2.4: the notification "MAY also carry a network not listed above").
     */
    @Test
    void anUnknownNetworkIsCarriedVerbatim() {
        String body = qrCodeWithNetworkKey("fednow").replace(
                "\"networks\": {",
                "\"networks\": { \"Pix\": { \"pixKey\": \"carlos@example.com\" },");

        JsonPath qrCode = createAndRead(body);

        assertNotNull(qrCode.get("paymentMethods[0].networks.fednow"), "the standard rail still works");
        assertEquals("carlos@example.com", qrCode.getString("paymentMethods[0].networks.Pix.pixKey"),
                "Pix must survive round-trip unchanged, key and value: " + qrCode.prettify());
    }

    @Test
    void anUnknownNetworkKeepsItsOwnCase() {
        String body = qrCodeWithNetworkKey("fednow").replace(
                "\"networks\": {",
                "\"networks\": { \"SomeChain\": { \"walletAddress\": \"9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM\" },");

        JsonPath qrCode = createAndRead(body);

        // We normalise only what the standard names. Everything else belongs to its owner, including
        // how it is spelled.
        assertTrue(qrCode.getMap("paymentMethods[0].networks").containsKey("SomeChain"),
                "an unknown network's key is not ours to rewrite: " + qrCode.prettify());
    }

}
