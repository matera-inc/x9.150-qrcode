/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;
import com.matera.x9qrcode.infrastructure.service.events.PaymentEventDrain;

import io.restassured.module.mockmvc.response.MockMvcResponse;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The two-phase on-chain flow, which has had no trigger since the unpublished blockchains were
 * removed.
 *
 * <p>{@code payment.sent} and {@code payment.failed} report a transaction already committed to a
 * public ledger. No bank rail can produce one: an ACH debit has no evidenced moment between
 * "announced" and "settled" that a payer could point at. Solana can — a txHash anyone may verify —
 * so restoring it restores the distinction, and these are the first tests to exercise it since.
 *
 * <p>The phase itself is inferred rather than declared: ANSI X9.150 has no phase marker because the
 * committee rejected one (ADR-0004), so {@code blockchain.action} carries it.
 *
 * <p>What stays constant is the settlement boundary. X9.150 never touches money, so a payer saying
 * "sent" does not make a QR Code PAID — only a system that actually saw the funds may say that, via
 * the status endpoint.
 */
class SolanaTwoPhaseNotificationApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String NOTIFY = "/pub/api/v1/payment-notification";
    private static final String EVENTS = "/pub/api/v1/events";
    private static final String APPLICATION_JOSE = "application/jose";

    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final String PAYER_WALLET = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU";
    private static final String TX_HASH = "5VfydnLu4XwV2FquzyFbENPBqbCyJLxq5wRhtZ8rLraBdHfDDmWXnc8nBtCqPU4f";
    private static final long AMOUNT = 25_000_000L;

    @Autowired
    private PaymentEventDrain paymentEventDrain;

    private String createSolanaQRCode() {
        String body = """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Solana Flow", "phone": "+14155550100", "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": {
                "description": "solana flow",
                "invoice": { "number": "INV-SOL-1", "date": "2030-01-10", "dueDate": "2030-12-31T23:59:59Z" },
                "amountDue": { "amount": %d, "currency": "USDC" }
              },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "solana": { "recipient": "%s", "memo": "{QRCD:\\"INV-SOL-1\\"}" } } }
              ]
            }
            """.formatted(AMOUNT, AMOUNT, RECIPIENT);

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

    private MockMvcResponse notify(String qrCodeId, String action, String transactionId, String destination) {
        String transactionField = transactionId == null ? "" : "\"transactionId\": \"%s\",".formatted(transactionId);
        String body = """
            {
              "payment": { "qrcodeId": "%s", %s "amount": %d, "currency": "USDC", "network": "Solana" },
              "expectedDate": "2030-10-08T06:59:59Z",
              "blockchain": { "action": "%s", "to": "%s", "from": "%s" }
            }
            """.formatted(qrCodeId, transactionField, AMOUNT, action, destination, PAYER_WALLET);

        return given().contentType(APPLICATION_JOSE).body(sign(body)).when().post(NOTIFY);
    }

    private String statusOf(String qrCodeId) {
        return given().when().get(CREATE + "/" + qrCodeId)
                .then().statusCode(HttpStatus.OK.value())
                .extract().path("status");
    }

    private List<String> eventTypesFor(String qrCodeId) {
        paymentEventDrain.drainOnce();

        JsonPath page = given().queryParam("after", "")
                .when().get(EVENTS)
                .then().statusCode(HttpStatus.OK.value())
                .extract().jsonPath();

        List<Map<String, Object>> events = page.getList("events");

        return events.stream()
                .filter(event -> qrCodeId.equals(event.get("qrCodeId")))
                .map(event -> (String) event.get("type"))
                .toList();
    }

    // ---------------------------------------------------------------------- the flow, end to end

    @Test
    void aPreCommitNotificationTakesTheQRCodeOutOfCirculation() {
        String qrCodeId = createSolanaQRCode();

        MockMvcResponse response = notify(qrCodeId, "PAYMENT_INITIATED", null, RECIPIENT);

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId),
                "nothing has moved on-chain yet, so this is the notification that reserves the QR Code");
    }

    /**
     * The settlement boundary, restored along with the rail. A payer reporting a transaction is not
     * the same as funds arriving, and X9.150 cannot tell the difference — so it does not pretend to.
     */
    @Test
    void aPostCommitNotificationReportsWithoutClearing() {
        String qrCodeId = createSolanaQRCode();

        assertEquals(HttpStatus.OK.value(), notify(qrCodeId, "PAYMENT_INITIATED", null, RECIPIENT).statusCode());
        assertEquals(HttpStatus.OK.value(), notify(qrCodeId, "SENT", TX_HASH, RECIPIENT).statusCode());

        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId),
                "a reported transaction is not settlement: only a system that saw the funds may say paid");

        assertEquals(List.of("payment.initiated", "payment.sent"), eventTypesFor(qrCodeId),
                "payment.sent has had no trigger since the blockchains were removed; Solana restores it");
    }

    /** A payer reporting the payment did not proceed releases nothing, but is published. */
    @Test
    void aFailedPaymentIsPublishedAsSuch() {
        String qrCodeId = createSolanaQRCode();

        notify(qrCodeId, "PAYMENT_INITIATED", null, RECIPIENT);
        assertEquals(HttpStatus.OK.value(), notify(qrCodeId, "NOT_SENT", null, RECIPIENT).statusCode());

        assertEquals(List.of("payment.initiated", "payment.failed"), eventTypesFor(qrCodeId));
    }

    // ------------------------------------------------------------------------------- refusals

    /**
     * The destination must be one this QR Code published. The address is read from {@code recipient}
     * — the field Solana's own publication names — rather than from the {@code walletAddress}
     * convention this repository once invented.
     */
    @Test
    void aDestinationThisQRCodeNeverPublishedIsRefused() {
        String qrCodeId = createSolanaQRCode();

        MockMvcResponse response = notify(qrCodeId, "PAYMENT_INITIATED", null, PAYER_WALLET);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId), "a refused notification must change nothing");
    }

    /** Post-commit means a transaction exists, so `SENT` without one is a contradiction. */
    @Test
    void aSentNotificationWithoutATransactionIsRefused() {
        String qrCodeId = createSolanaQRCode();

        notify(qrCodeId, "PAYMENT_INITIATED", null, RECIPIENT);

        MockMvcResponse response = notify(qrCodeId, "SENT", null, RECIPIENT);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
    }

}
