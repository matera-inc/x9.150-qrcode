/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.events;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

import io.restassured.module.mockmvc.response.MockMvcResponse;
import io.restassured.path.json.JsonPath;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two properties the event log's consumers depend on, neither obvious from reading a mapper.
 */
class PaymentEventLogContractTest extends AbstractIntegrationTest {

    @Autowired
    private MongoTemplate mongoTemplate;

    /**
     * <b>X9-EVT-020</b> — the event log expires rather than growing forever.
     *
     * <p><b>Source:</b> Ours. ADR-0014 — the stream is a transport, not an archive.
     *
     * <p><b>Why:</b> A consumer offline longer than the retention loses events, and that is a deliberate trade
     * stated in the contract rather than an unbounded collection discovered in production.
     */
    @Test
    void theEventLogExpiresRatherThanGrowingForever() {
        List<Document> indexes = mongoTemplate.getCollection("payment_events")
                .listIndexes()
                .into(new java.util.ArrayList<>());

        Document ttl = indexes.stream()
                .filter(index -> index.containsKey("expireAfterSeconds"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                    "payment_events has no TTL index, so it grows without bound: " + indexes));

        assertEquals("occurred_at", ttl.get("key", Document.class).keySet().iterator().next(),
                "the TTL must hang off the event's own timestamp: " + ttl);
        assertTrue(ttl.get("expireAfterSeconds", Number.class).longValue() > 0, ttl.toString());
    }

    /**
     * <b>X9-EVT-021</b> — both the announced and the reported amount reach the consumer.
     *
     * <p><b>Source:</b> Ours. ADR-0014 — we transport and sequence, the consumer reconciles.
     *
     * <p><b>Why:</b> What the payer SAID they would pay and what they LATER reported paying are different facts,
     * and only the consumer can decide what a difference between them means. Collapsing them here
     * would make that reconciliation impossible.
     */
    @Test
    void anAnnouncedAndAReportedAmountBothReachTheConsumer() {
        String qrCodeId = createSolanaQRCode(ANNOUNCED);

        assertEquals(HttpStatus.OK.value(),
            notify(qrCodeId, "PAYMENT_INITIATED", null, ANNOUNCED).statusCode(),
            "the pre-commit must quote the amount we published");

        assertEquals(HttpStatus.OK.value(),
            notify(qrCodeId, "SENT", TX_HASH, REPORTED).statusCode(),
            "a post-commit is recorded, not judged: the money has already moved");

        paymentEventDrain.drainOnce();

        JsonPath page = given().queryParam("after", "")
                .when().get("/pub/api/v1/events")
                .then().statusCode(HttpStatus.OK.value())
                .extract().jsonPath();

        List<Map<String, Object>> mine = page.getList("events", Map.class).stream()
                .map(event -> (Map<String, Object>) event)
                .filter(event -> qrCodeId.equals(event.get("qrCodeId")))
                .toList();

        Map<String, Long> amountByType = mine.stream().collect(java.util.stream.Collectors.toMap(
            event -> (String) event.get("type"),
            event -> ((Number) event.get("amount")).longValue()));

        assertEquals(ANNOUNCED, amountByType.get("payment.initiated"),
                "the announced amount must survive on its own event: " + mine);
        assertEquals(REPORTED, amountByType.get("payment.sent"),
                "the reported amount must survive on its own event: " + mine);
        assertNotEquals(amountByType.get("payment.initiated"), amountByType.get("payment.sent"),
                "the discrepancy is exactly what the consumer is expected to spot");
    }

    // ------------------------------------------------------------------- the payer, on Solana

    private static final long ANNOUNCED = 10_000_000L;
    private static final long REPORTED = 9_000_000L;
    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final String TX_HASH = "5VfydnLu4XwV2FquzyFbENPBqbCyJLxq5wRhtZ8rLraBdHfDDmWXnc8nBtCqPU4f";

    @Autowired
    private PaymentEventDrain paymentEventDrain;

    private String createSolanaQRCode(long amount) {
        String body = """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Discrepancy Test", "phone": "+14155550100", "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "discrepancy", "amountDue": { "amount": %d, "currency": "USDC" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "solana": { "recipient": "%s" } } }
              ]
            }
            """.formatted(amount, amount, RECIPIENT);

        return given().contentType("application/json").body(body)
                .when().post("/api/v1/payment-request")
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");
    }

    private MockMvcResponse notify(String qrCodeId, String action, String transactionId, long amount) {
        String transactionField = transactionId == null ? "" : "\"transactionId\": \"%s\",".formatted(transactionId);
        String payload = """
            {
              "payment": { "qrcodeId": "%s", %s "amount": %d, "currency": "USDC", "network": "Solana" },
              "expectedDate": "2030-10-08T06:59:59Z",
              "blockchain": { "action": "%s", "to": "%s", "from": "%s" }
            }
            """.formatted(qrCodeId, transactionField, amount, action, RECIPIENT, RECIPIENT);

        String jws = given().contentType("application/json")
                .header("Correlation-Id", java.util.UUID.randomUUID().toString())
                .header("TTL-Seconds", "300")
                .body(payload)
                .when().post("/api/v1/signature/generate")
                .then().statusCode(HttpStatus.OK.value())
                .extract().body().asString();

        return given().contentType("application/jose").body(jws)
                .when().post("/pub/api/v1/payment-notification");
    }

}
