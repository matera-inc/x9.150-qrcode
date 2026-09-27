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
     * The standard names rails it does not define — Zelle and the card brands, each deferred to
     * "network documentation" that does not exist yet — and §2.4 adds that a notification "MAY also
     * carry a network not listed above". This build reads that as permission the <em>format</em>
     * grants, not an obligation the implementation carries: a network we cannot interpret is one we
     * cannot validate a payment against, so it is refused at creation rather than advertised on a QR
     * Code nobody can honour. See official-spec/INTERPRETATION.md (I-2).
     */
    @Test
    void anUnsupportedNetworkIsRefusedByName() {
        String body = qrCodeWithNetworkKey("fednow").replace(
                "\"networks\": {",
                "\"networks\": { \"Pix\": { \"pixKey\": \"carlos@example.com\" },");

        String response = given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.BAD_REQUEST.value())
                .extract().asString();

        assertTrue(response.contains("Pix"),
                "the refusal must name the network it refused, so the biller can fix it: " + response);
    }

    /**
     * Silence is the dangerous answer. This mapper runs behind an ObjectMapper with
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so an unsupported network would otherwise be
     * dropped without a word — and the biller would believe a rail was live that nothing here
     * understands, discovering otherwise only when a payer cannot pay.
     */
    @Test
    void anUnsupportedNetworkIsNotSilentlyDropped() {
        String body = qrCodeWithNetworkKey("fednow").replace(
                "\"networks\": {",
                "\"networks\": { \"SomeChain\": { \"walletAddress\": \"9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM\" },");

        given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.BAD_REQUEST.value());
    }

    /** A patch may not smuggle in what a create would have refused. */
    @Test
    void aPatchMayNotIntroduceAnUnsupportedNetwork() {
        String id = given().contentType("application/json").body(qrCodeWithNetworkKey("fednow"))
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");

        String patch = """
            {
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 5000,
                  "networks": { "Pix": { "pixKey": "carlos@example.com" } } }
              ]
            }
            """;

        String response = given().contentType("application/json").body(patch)
                .when().patch(CREATE + "/" + id)
                .then().statusCode(HttpStatus.BAD_REQUEST.value())
                .extract().asString();

        assertTrue(response.contains("Pix"), response);
    }

}
