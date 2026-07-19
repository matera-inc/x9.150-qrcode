/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks of the v1.0 currency-mixing rule via the create endpoint:
 * currencies on one request must all sit in the same pegged group (per pegged-currencies.json),
 * or the request must use a single currency. Non-pegged currencies (e.g. BTC) can't be mixed at all.
 */
class CurrencyMixRejectionApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";

    // No locationId -> the server mints a fresh one, so these never collide with existing QRs.
    private static String body(String secondCurrency, String secondNetwork) {
        return """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Currency Mix Test",
                "phone": "+14155550100",
                "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "currency mix test", "amountDue": { "amount": 1000, "currency": "USD" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
                  "networks": { "FedNow": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" } } },
                %s
              ]
            }
            """.formatted(secondCurrency == null ? "" : secondMethod(secondCurrency, secondNetwork));
    }

    private static String secondMethod(String currency, String network) {
        String net = switch (network) {
            case "Solana"  -> "\"Solana\": { \"walletAddress\": \"9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM\" }";
            case "Bitcoin" -> "\"Bitcoin\": { \"walletAddress\": \"bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq\" }";
            default        -> "\"FedNow\": { \"routingNumber\": \"021000021\", \"accountNumber\": \"9999999999\", \"protectionType\": \"tokenized\" }";
        };
        return """
            { "currency": "%s", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000000,
              "networks": { %s } }""".formatted(currency, net);
    }

    private String postExpectingBadRequest(String json) {
        return given().contentType("application/json").body(json)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.BAD_REQUEST.value())
                .extract().asString();
    }

    @Test
    void shouldAcceptTwoCurrenciesInTheSamePeggedGroup() {
        // USD + USDC are both in the dollar-pegged group -> allowed
        given().contentType("application/json").body(body("USDC", "Solana"))
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value());
    }

    @Test
    void shouldRejectNonPeggedCurrencyMixedWithAPeggedOne() {
        // USD (pegged) + BTC (non-pegged) -> rejected
        String response = postExpectingBadRequest(body("BTC", "Bitcoin"));
        assertTrue(response.contains("currency"), response);
        assertTrue(response.contains("BTC"), response);
    }

    @Test
    void shouldRejectCurrenciesFromDifferentPeggedGroups() {
        // USD (dollar group) + BRL (real group) -> different groups -> rejected
        String response = postExpectingBadRequest(body("BRL", "FedNow"));
        assertTrue(response.contains("currency"), response);
        assertTrue(response.contains("BRL"), response);
    }
}
