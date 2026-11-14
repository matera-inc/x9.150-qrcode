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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;

/**
 * Black-box helpers for ADR-0036's "Every case, by message and status" matrix.
 *
 * <p>One QR Code offers every interpreted rail it can in one dollar peg group: USD on FedNow and
 * RTP, USD on ACH, and USDC on Solana. Notifications are signed through the service's own signing
 * endpoint, so every payer shares one certificate subject and is told apart by {@code payer.info}
 * alone, which is exactly the case ADR-0036 is about.
 */
abstract class NotificationMatrixApiSupport extends AbstractIntegrationTest {

    static final String CREATE = "/api/v1/payment-request";
    static final String NOTIFY = "/pub/api/v1/payment-notification";
    static final String EVENTS = "/pub/api/v1/events";
    static final String APPLICATION_JOSE = "application/jose";

    static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    static final String PAYER_WALLET = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU";
    static final long AMOUNT = 1_000L;

    static final String PAYER_A = "payer-a";
    static final String PAYER_B = "payer-b";

    @Autowired
    PaymentEventDrain paymentEventDrain;

    String createQRCode() {
        String body = """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Matrix Test", "phone": "+14155550100", "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "matrix", "amountDue": { "amount": %d, "currency": "USD" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": {
                    "fednow": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" },
                    "rtp": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" },
                    "ach": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" } } },
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "solana": { "recipient": "%s" } } }
              ]
            }
            """.formatted(AMOUNT, AMOUNT, AMOUNT, RECIPIENT);

        return given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");
    }

    String sign(String payload) {
        return given().contentType("application/json")
                .header("Correlation-Id", UUID.randomUUID().toString())
                .header("TTL-Seconds", "300")
                .body(payload)
                .when().post("/api/v1/signature/generate")
                .then().statusCode(HttpStatus.OK.value())
                .extract().body().asString();
    }

    private static String payerField(String payerInfo) {
        return payerInfo == null ? "" : "\"payer\": { \"info\": \"%s\" },".formatted(payerInfo);
    }

    private static String transactionField(String transactionId) {
        return transactionId == null ? "" : "\"transactionId\": \"%s\",".formatted(transactionId);
    }

    /** A FedNow, RTP or ACH notification; no {@code transactionId} makes it a pre-commit. */
    MockMvcResponse bank(String qrCodeId, String network, String transactionId, String payerInfo, long amount) {
        String body = """
            {
              "payment": { "qrcodeId": "%s", %s "amount": %d, "currency": "USD", "network": "%s" },
              %s
              "expectedDate": "2030-10-08T06:59:59Z"
            }
            """.formatted(qrCodeId, transactionField(transactionId), amount, network, payerField(payerInfo));

        return given().contentType(APPLICATION_JOSE).body(sign(body)).when().post(NOTIFY);
    }

    MockMvcResponse bank(String qrCodeId, String network, String transactionId, String payerInfo) {
        return bank(qrCodeId, network, transactionId, payerInfo, AMOUNT);
    }

    /** A Solana notification; {@code hash} null makes it a pre-commit unless the action is NOT_SENT. */
    MockMvcResponse solana(String qrCodeId, String action, String hash, String payerInfo, long amount) {
        String body = """
            {
              "payment": { "qrcodeId": "%s", %s "amount": %d, "currency": "USDC", "network": "Solana" },
              %s
              "blockchain": { "action": "%s", "to": "%s", "from": "%s" }
            }
            """.formatted(qrCodeId, transactionField(hash), amount, payerField(payerInfo), action, RECIPIENT,
                PAYER_WALLET);

        return given().contentType(APPLICATION_JOSE).body(sign(body)).when().post(NOTIFY);
    }

    MockMvcResponse solana(String qrCodeId, String action, String hash, String payerInfo) {
        return solana(qrCodeId, action, hash, payerInfo, AMOUNT);
    }

    MockMvcResponse statusUpdate(String qrCodeId, String body) {
        return given().contentType("application/json").body(body)
                .when().put(CREATE + "/" + qrCodeId + "/status-update");
    }

    void markPaid(String qrCodeId) {
        statusUpdate(qrCodeId, "{\"status\":\"PAID\",\"network\":\"FedNow\",\"endToEndId\":\"E2E-PAID-1\"}")
                .then().statusCode(HttpStatus.OK.value());
    }

    void cancel(String qrCodeId) {
        statusUpdate(qrCodeId, "{\"status\":\"CANCELLED\"}").then().statusCode(HttpStatus.OK.value());
    }

    String statusOf(String qrCodeId) {
        return given().when().get(CREATE + "/" + qrCodeId)
                .then().statusCode(HttpStatus.OK.value())
                .extract().path("status");
    }

    /** Every event for this QR Code, in order, read the way a consumer reads them: by cursor. */
    List<Map<String, Object>> eventsFor(String qrCodeId) {
        paymentEventDrain.drainOnce();

        List<Map<String, Object>> mine = new ArrayList<>();
        String cursor = "";
        boolean hasMore = true;

        while (hasMore) {
            JsonPath page = given().queryParam("after", cursor).queryParam("limit", 500)
                    .when().get(EVENTS)
                    .then().statusCode(HttpStatus.OK.value())
                    .extract().jsonPath();

            List<Map<String, Object>> events = page.getList("events");
            events.stream().filter(event -> qrCodeId.equals(event.get("qrCodeId"))).forEach(mine::add);

            hasMore = page.getBoolean("hasMore");
            cursor = page.getString("nextCursor");
            if (cursor == null) {
                break;
            }
        }

        return mine;
    }

    List<String> eventTypesFor(String qrCodeId) {
        return eventsFor(qrCodeId).stream().map(event -> (String) event.get("type")).toList();
    }

    Map<String, Object> lastEventFor(String qrCodeId) {
        List<Map<String, Object>> events = eventsFor(qrCodeId);
        return events.get(events.size() - 1);
    }

}
