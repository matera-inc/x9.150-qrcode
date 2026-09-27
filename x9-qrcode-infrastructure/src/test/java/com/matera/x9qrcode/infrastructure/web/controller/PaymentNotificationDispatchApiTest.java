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

import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Notification dispatch, now that Solana is the only interpreted blockchain (ADR-0010).
 *
 * <p>The dispatch switch used to list four blockchains with no {@code default}, so a notification on
 * a rail it had forgotten fell straight through — never validated, never applied, answered
 * <b>200 OK</b> for a payment it ignored. Both halves of that fix are pinned here: an unsupported
 * rail is refused rather than accepted, and every refusal leaves the QR Code untouched.
 *
 * <p>Note the assertions check the resulting STATUS, not just the response code. A bare 200 check
 * would not have caught the original bug, because the original bug returned 200.
 */
class PaymentNotificationDispatchApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String NOTIFY = "/pub/api/v1/payment-notification";
    private static final String APPLICATION_JOSE = "application/jose";

    private static final String SOLANA_WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";

    private static String solanaQRCode() {
        return """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Dispatch Test",
                "phone": "+14155550100",
                "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "dispatch test", "amountDue": { "amount": 25000000, "currency": "USDC" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 25000000,
                  "networks": { "Solana": { "walletAddress": "%s" } } }
              ]
            }
            """.formatted(SOLANA_WALLET);
    }

    private static String bankOnlyQRCode() {
        return """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Dispatch Test",
                "phone": "+14155550100",
                "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "bank only", "amountDue": { "amount": 5000, "currency": "USD" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 5000,
                  "networks": { "FedNow": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" } } }
              ]
            }
            """;
    }

    private static String notification(String qrCodeId, String network) {
        return """
            {
              "payment": {
                "qrcodeId": "%s",
                "amount": 25000000,
                "currency": "USDC",
                "network": "%s"
              },
              "expectedDate": "2030-10-08T06:59:59Z",
              "blockchain": { "action": "PAYMENT_INITIATED", "to": "%s", "from": "%s" }
            }
            """.formatted(qrCodeId, network, SOLANA_WALLET, SOLANA_WALLET);
    }

    private String create(String body) {
        return given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");
    }

    private String sign(String payload) {
        return given().contentType("application/json")
                .header("Correlation-Id", UUID.randomUUID().toString())
                .header("TTL-Seconds", "300")
                .body(payload)
                .when().post("/api/v1/signature/generate")
                .then().statusCode(HttpStatus.OK.value())
                .extract().body().asString();
    }

    private MockMvcResponse notify(String qrCodeId, String network) {
        return given().contentType(APPLICATION_JOSE)
                .body(sign(notification(qrCodeId, network)))
                .when().post(NOTIFY);
    }

    private String statusOf(String qrCodeId) {
        return given().when().get(CREATE + "/" + qrCodeId)
                .then().statusCode(HttpStatus.OK.value())
                .extract().path("status");
    }

    @Test
    void solanaPreCommitNotificationInitiatesPayment() {
        String qrCodeId = create(solanaQRCode());
        assertEquals("ACTIVE", statusOf(qrCodeId));

        MockMvcResponse response = notify(qrCodeId, "Solana");

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId),
                "a pre-commit notification must take the QR Code out of circulation");
    }

    /**
     * A rail the QR Code does not offer must be refused. The old check asked only whether the QR
     * Code supported <em>some</em> blockchain, so a bank-only QR Code was the one case it caught —
     * a chain mismatch between two blockchains slipped through.
     */
    @Test
    void aBankOnlyQRCodeRefusesASolanaNotification() {
        String qrCodeId = create(bankOnlyQRCode());

        MockMvcResponse response = notify(qrCodeId, "Solana");

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(),
                "a QR Code without Solana must not accept a Solana payment: " + response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId), "a refused notification must change nothing");
    }

    /**
     * Chains X9.150 does not interpret cannot even be named in {@code payment.network} — they travel
     * in the networks object's additionalProperties instead (ADR-0010). A notification naming one is
     * rejected at the contract boundary rather than silently accepted.
     */
    @Test
    void anUninterpretedChainIsRejectedNotIgnored() {
        String qrCodeId = create(solanaQRCode());

        MockMvcResponse response = notify(qrCodeId, "Ethereum");

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(),
                "Ethereum is not an interpreted rail: " + response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

}
