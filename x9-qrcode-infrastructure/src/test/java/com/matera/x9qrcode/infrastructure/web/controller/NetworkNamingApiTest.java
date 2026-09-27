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
 * <p>That ambiguity is handled at the boundary we do not control — a payment notification from a
 * third-party payer is read case-insensitively. It is <b>not</b> handled here. Our own API has one
 * spelling of a rail: the configured key, which is also the only one we emit, so what a caller sends
 * is what they read back. Any other spelling is refused, naming both.
 *
 * <p>The emitted (and therefore accepted) form is configurable — an interoperability problem should
 * be a config change, not a release. {@link ConfiguredNetworkKeyApiTest} covers an override; this
 * file covers the default.
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

    @Test
    void theConfiguredSpellingIsAccepted() {
        JsonPath qrCode = createAndRead(qrCodeWithNetworkKey("fednow"));

        assertNotNull(qrCode.get("paymentMethods[0].networks.fednow"), qrCode.prettify());
        assertEquals("021000021", qrCode.getString("paymentMethods[0].networks.fednow.routingNumber"));
    }

    /**
     * The spellings a conformant implementer might send, having read either half of the standard.
     * On a payment notification we accept all of them; on our own API we do not.
     *
     * <p>Not pedantry. The caller will read this QR Code back as {@code fednow}, so accepting
     * {@code FedNow} on the way in guarantees their request and our response disagree about the same
     * field. Refusing costs them one string. Accepting costs them a mismatch they discover somewhere
     * less forgiving than here.
     */
    @ParameterizedTest(name = "networks.{0} is refused, naming the spelling to use")
    @ValueSource(strings = {"FedNow", "FEDNOW", "fedNow"})
    void aStandardRailUnderAnyOtherSpellingIsRefused(String spelling) {
        String response = given().contentType("application/json").body(qrCodeWithNetworkKey(spelling))
                .when().post(CREATE)
                .then().statusCode(HttpStatus.BAD_REQUEST.value())
                .extract().asString();

        assertTrue(response.contains(spelling), "the refusal must quote what was sent: " + response);
        assertTrue(response.contains("fednow"), "and the spelling to use instead: " + response);
    }

    /** The payer's notification is the boundary we do not control, so there we stay lenient. */
    @ParameterizedTest(name = "a notification naming the rail as {0} is still understood")
    @ValueSource(strings = {"ACH", "ach", "Ach"})
    void aThirdPartyNotificationIsReadCaseInsensitively(String spelling) {
        String id = given().contentType("application/json").body(qrCodeWithNetworkKey("ach"))
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");

        String notification = """
            {
              "payment": { "qrcodeId": "%s", "amount": 5000, "currency": "USD", "network": "%s",
                           "transactionId": "021000021.0000001" },
              "payer": { "info": "Jane Payer" },
              "expectedDate": "2030-10-08T06:59:59Z"
            }
            """.formatted(id, spelling);

        String jws = given().contentType("application/json")
                .header("Correlation-Id", java.util.UUID.randomUUID().toString())
                .header("TTL-Seconds", "300")
                .body(notification)
                .when().post("/api/v1/signature/generate")
                .then().statusCode(HttpStatus.OK.value())
                .extract().body().asString();

        given().contentType("application/jose").body(jws)
                .when().post("/pub/api/v1/payment-notification")
                .then().statusCode(HttpStatus.OK.value());
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
