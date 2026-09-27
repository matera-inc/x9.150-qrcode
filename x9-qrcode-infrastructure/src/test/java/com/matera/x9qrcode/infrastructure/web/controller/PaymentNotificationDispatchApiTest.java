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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Notification dispatch across every blockchain rail.
 *
 * <p>The dispatch switch used to list only Solana, Polygon, Ethereum and Bitcoin, with no
 * {@code default}. A notification on Base, XRP or Arc therefore fell straight through: never
 * validated, never applied, and answered <b>200 OK</b> for a payment it ignored. These tests pin
 * both halves of the fix — the forgotten rails now work, and a rail the QR Code does not offer is
 * refused rather than accepted.
 */
class PaymentNotificationDispatchApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String NOTIFY = "/pub/api/v1/payment-notification";
    private static final String APPLICATION_JOSE = "application/jose";

    private static final String EVM_WALLET = "0x742d35Cc6634C0539Ff82c466ae367A6097dE123";
    private static final String SOLANA_WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final String XRP_WALLET = "rP5ZkexZgLXXkqfRRzuwSAmSjaWmHgD9Ha";

    private static String createBody(String network, String wallet) {
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
                  "networks": { "%s": { "walletAddress": "%s" } } }
              ]
            }
            """.formatted(network, wallet);
    }

    private static String notificationBody(String qrCodeId, String network, String wallet) {
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
            """.formatted(qrCodeId, network, wallet, wallet);
    }

    private String createQRCode(String network, String wallet) {
        return given().contentType("application/json").body(createBody(network, wallet))
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

    private MockMvcResponse notify(String qrCodeId, String network, String wallet) {
        return given().contentType(APPLICATION_JOSE)
                .body(sign(notificationBody(qrCodeId, network, wallet)))
                .when().post(NOTIFY);
    }

    private String statusOf(String qrCodeId) {
        return given().when().get(CREATE + "/" + qrCodeId)
                .then().statusCode(HttpStatus.OK.value())
                .extract().path("status");
    }

    /**
     * Base, XRP and Arc are the three rails the old switch forgot. Each must now be dispatched: a
     * pre-commit notification takes the QR Code out of circulation.
     */
    @ParameterizedTest(name = "{0} pre-commit notification initiates payment")
    @CsvSource({
            "Base,     " + EVM_WALLET,
            "XRP,      " + XRP_WALLET,
            "Arc,      " + EVM_WALLET,
            "Solana,   " + SOLANA_WALLET,
            "Ethereum, " + EVM_WALLET,
    })
    void preCommitNotificationInitiatesPaymentOnEveryBlockchainRail(String network, String wallet) {
        String qrCodeId = createQRCode(network, wallet);
        assertEquals("ACTIVE", statusOf(qrCodeId));

        MockMvcResponse response = notify(qrCodeId, network, wallet);

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId),
                "%s notification must move the QR Code, not be silently ignored".formatted(network));
    }

    /**
     * The regression itself: before the fix these rails answered 200 while leaving the QR Code
     * ACTIVE. A green 200 alone would not have caught it — the status is the tell.
     */
    @ParameterizedTest(name = "{0} notification is not silently ignored")
    @CsvSource({"Base, " + EVM_WALLET, "XRP, " + XRP_WALLET, "Arc, " + EVM_WALLET})
    void aForgottenRailNoLongerReturnsOkWhileDoingNothing(String network, String wallet) {
        String qrCodeId = createQRCode(network, wallet);

        notify(qrCodeId, network, wallet);

        assertNotEquals("ACTIVE", statusOf(qrCodeId),
                "%s used to answer 200 and leave the QR Code untouched".formatted(network));
    }

    /**
     * The old check asked only whether the QR Code supported <em>some</em> blockchain, so a QR Code
     * offering one chain accepted a notification for another.
     */
    @Test
    void aQRCodeOfferingOneChainRefusesANotificationForAnother() {
        String qrCodeId = createQRCode("Solana", SOLANA_WALLET);

        MockMvcResponse response = notify(qrCodeId, "Base", EVM_WALLET);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(),
                "a Solana-only QR Code must not accept a Base payment: " + response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId), "a refused notification must change nothing");
    }

}
