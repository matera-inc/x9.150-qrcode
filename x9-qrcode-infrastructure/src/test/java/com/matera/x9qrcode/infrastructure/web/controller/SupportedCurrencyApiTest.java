/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The currency gate, end to end.
 *
 * <p>The X9.150 payload format is currency-agnostic — it carries any ISO 4217 code or digital-asset
 * ticker verbatim and leaves interpretation to the paying PSP. What a <em>deployment</em> can settle
 * is a different question, answered by the rails it supports: this build interprets FedNow, RTP and
 * ACH, which move USD. A QR Code denominated in EUR or USDC would advertise an amount no supported
 * rail can pay, so it is refused at creation, where the biller can still fix it, rather than
 * accepted and discovered unpayable by a payer. See official-spec/INTERPRETATION.md (I-3).
 *
 * <p>The list is configuration ({@code supported-currencies.json}), not a constant: adding a rail
 * that settles another currency is what should make that currency acceptable.
 *
 * <p>Distinct from the peg-mixing rule, which asks whether the currencies on one request may appear
 * <em>together</em>. With one currency supported that rule has nothing left to decide here, so it is
 * exercised where the allow list cannot shadow it — {@code PeggedCurrencyMixPolicyTest}.
 */
class SupportedCurrencyApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";

    // No locationId -> the server mints a fresh one, so these never collide with existing QRs.
    private static String body(String currency) {
        return """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Currency Test",
                "phone": "+14155550100",
                "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "currency test", "amountDue": { "amount": 1000, "currency": "%s" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "%s", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
                  "networks": { "fednow": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" } } }
              ]
            }
            """.formatted(currency, currency);
    }

    @Test
    void theSettledCurrencyIsAccepted() {
        given().contentType("application/json").body(body("USD"))
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value());
    }

    /**
     * USDC is dollar-pegged and BTC is not, but neither is settled by FedNow, RTP or ACH — so the
     * peg is beside the point. The allow list is about what this deployment can pay out, not about
     * what the format can carry.
     */
    @ParameterizedTest(name = "a {0} QR Code is refused")
    @ValueSource(strings = {"EUR", "BRL", "USDC", "BTC"})
    void aCurrencyNoSupportedRailSettlesIsRefused(String currency) {
        String response = given().contentType("application/json").body(body(currency))
                .when().post(CREATE)
                .then().statusCode(HttpStatus.BAD_REQUEST.value())
                .extract().asString();

        assertTrue(response.contains(currency),
                "the refusal must name the currency it refused: " + response);
        assertTrue(response.contains("USD"),
                "and say what is supported, so the biller knows what to send: " + response);
    }

    /** Case is not the biller's problem: {@code usd} is USD. */
    @ParameterizedTest(name = "{0} is read as USD")
    @ValueSource(strings = {"usd", "Usd"})
    void theAllowListIsCaseInsensitive(String spelling) {
        given().contentType("application/json").body(body(spelling))
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value());
    }

}
