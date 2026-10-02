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
 * <p>That contradiction is confined to §2.4, which governs the notification's {@code network}
 * <em>value</em> — a different field from these object keys, and one this PR does not touch. §14.5
 * spells the keys {@code fednow}/{@code rtp}/{@code ach} throughout, with no contradiction anywhere,
 * so there is exactly one spelling of a rail here: the one we emit. Any other is refused, naming
 * both.
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
     * <b>X9-RAIL-050</b> — the configured spelling is accepted.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §14.5 fixes the spelling of the network keys. INTERPRETATION I-1.
     *
     * <p><b>Why:</b> The acceptance case for naming.
     */
    @Test
    void theConfiguredSpellingIsAccepted() {
        JsonPath qrCode = createAndRead(qrCodeWithNetworkKey("fednow"));

        assertNotNull(qrCode.get("paymentMethods[0].networks.fednow"), qrCode.prettify());
        assertEquals("021000021", qrCode.getString("paymentMethods[0].networks.fednow.routingNumber"));
    }

    /**
     * <b>X9-RAIL-051</b> — a standard rail under any other spelling is refused at creation.
     *
     * <p><b>Source:</b> Ours. I-1 — strict in what we ISSUE, lenient in what we accept from a payer.
     *
     * <p><b>Why:</b> The asymmetry is deliberate and is the whole of I-1. A QR Code we mint must carry the spec's
     * spelling so every payer app can read it; a notification arriving from someone else's PSP is
     * read case-insensitively because refusing a real payment over a capital letter helps nobody.
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

    /**
     * <b>X9-RAIL-052</b> — an unsupported network is refused, by name.
     *
     * <p><b>Source:</b> Ours. ADR-0012.
     *
     * <p><b>Why:</b> Naming it means the biller fixes it in one attempt rather than guessing which of several
     * networks was the problem.
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
     * <b>X9-RAIL-053</b> — an unsupported network is not silently dropped.
     *
     * <p><b>Source:</b> Ours. ADR-0012.
     *
     * <p><b>Why:</b> Dropping it would mint a QR Code missing the rail the biller asked for, with a 201 saying all
     * was well. The biller discovers it when a payer cannot pay.
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

    /**
     * <b>X9-RAIL-054</b> — a patch may not introduce an unsupported network.
     *
     * <p><b>Source:</b> Ours. ADR-0012.
     *
     * <p><b>Why:</b> Creation and editing are different doors to the same field. A rule enforced on only one of
     * them is not enforced.
     */
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
