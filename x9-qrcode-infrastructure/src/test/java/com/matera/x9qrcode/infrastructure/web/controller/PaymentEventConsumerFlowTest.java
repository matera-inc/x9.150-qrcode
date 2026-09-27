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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole payment-notification loop, end to end, with a stand-in for the consuming system.
 *
 * <p>The point of the test is the division of responsibility. X9.150 never touches money, so it
 * cannot know a payment settled — it only knows what a payer claimed. A post-commit notification
 * therefore publishes {@code payment.sent} and leaves the QR Code PAYMENT_INITIATED. Some other
 * system watches the funds arrive, matches the transaction it was told about, and calls back to say
 * the QR Code is paid. Only that produces {@code payment.cleared}.
 *
 * <p>The consumer here is deliberately dumb — poll, read, act — because that is all a consumer has
 * to be: X9.150 pushes nothing and holds no consumer state beyond the cursor the consumer itself
 * keeps.
 */
class PaymentEventConsumerFlowTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String NOTIFY = "/pub/api/v1/payment-notification";
    private static final String EVENTS = "/pub/api/v1/events";
    private static final String APPLICATION_JOSE = "application/jose";

    private static final String WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final String TX_HASH = "5VfydnLu4XwV2FquzyFbENPBqbCyJLxq5wRhtZ8rLraBdHfDDmWXnc8nBtCqPU4f";
    private static final long AMOUNT = 25_000_000L;

    @Autowired
    private PaymentEventDrain paymentEventDrain;

    // --------------------------------------------------------------------------- the payee side

    private String createQRCode() {
        String body = """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Consumer Flow Test",
                "phone": "+14155550100",
                "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": {
                "description": "consumer flow",
                "invoice": { "number": "INV-2030-77", "date": "2030-01-10", "dueDate": "2030-12-31T23:59:59Z" },
                "amountDue": { "amount": %d, "currency": "USDC" }
              },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "Solana": { "walletAddress": "%s" } } }
              ]
            }
            """.formatted(AMOUNT, AMOUNT, WALLET);

        return given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");
    }

    // --------------------------------------------------------------------------- the payer side

    private String sign(String payload) {
        return given().contentType("application/json")
                .header("Correlation-Id", UUID.randomUUID().toString())
                .header("TTL-Seconds", "300")
                .body(payload)
                .when().post("/api/v1/signature/generate")
                .then().statusCode(HttpStatus.OK.value())
                .extract().body().asString();
    }

    private MockMvcResponse notify(String qrCodeId, String action, String transactionId) {
        String transactionField = transactionId == null ? "" : "\"transactionId\": \"%s\",".formatted(transactionId);
        String body = """
            {
              "payment": { "qrcodeId": "%s", %s "amount": %d, "currency": "USDC", "network": "Solana" },
              "expectedDate": "2030-10-08T06:59:59Z",
              "blockchain": { "action": "%s", "to": "%s", "from": "%s" }
            }
            """.formatted(qrCodeId, transactionField, AMOUNT, action, WALLET, WALLET);

        return given().contentType(APPLICATION_JOSE).body(sign(body)).when().post(NOTIFY);
    }

    // ------------------------------------------------------------------- the consuming system

    /** Polls the events API exactly as an external consumer would: cursor in, cursor out. */
    private record Consumer(List<Map<String, Object>> seen, String cursor) { }

    private Consumer poll(Consumer consumer) {
        // Force a drain rather than waiting for the scheduler, so the test is deterministic.
        paymentEventDrain.drainOnce();

        JsonPath page = given()
                .queryParam("after", consumer.cursor() == null ? "" : consumer.cursor())
                .when().get(EVENTS)
                .then().statusCode(HttpStatus.OK.value())
                .extract().jsonPath();

        List<Map<String, Object>> events = page.getList("events");
        List<Map<String, Object>> seen = new ArrayList<>(consumer.seen());
        seen.addAll(events);

        String nextCursor = page.getString("nextCursor");

        return new Consumer(seen, nextCursor == null ? consumer.cursor() : nextCursor);
    }

    private List<String> typesFor(Consumer consumer, String qrCodeId) {
        return consumer.seen().stream()
                .filter(e -> qrCodeId.equals(e.get("qrCodeId")))
                .map(e -> (String) e.get("type"))
                .toList();
    }

    private String statusOf(String qrCodeId) {
        return given().when().get(CREATE + "/" + qrCodeId)
                .then().statusCode(HttpStatus.OK.value())
                .extract().path("status");
    }

    // ------------------------------------------------------------------------------- the flow

    @Test
    void aConsumerWatchesAPaymentThroughToSettlement() {
        Consumer consumer = new Consumer(List.of(), null);
        String qrCodeId = createQRCode();

        // 1. Pre-commit. Funds have not moved; this is the notification that takes the QR Code out
        //    of circulation.
        assertEquals(HttpStatus.OK.value(), notify(qrCodeId, "PAYMENT_INITIATED", null).statusCode());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));

        consumer = poll(consumer);
        assertEquals(List.of("payment.initiated"), typesFor(consumer, qrCodeId),
                "the consumer should learn the payment started");

        // 2. Post-commit. The payer reports a transaction — and the QR Code STAYS initiated, because
        //    X9.150 cannot see money and will not claim a payment settled on a payer's say-so.
        assertEquals(HttpStatus.OK.value(), notify(qrCodeId, "SENT", TX_HASH).statusCode());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId),
                "a reported transaction is not settlement: only a system that saw the funds may say paid");

        consumer = poll(consumer);
        assertEquals(List.of("payment.initiated", "payment.sent"), typesFor(consumer, qrCodeId));

        // 3. The consuming system matched the reported transaction against funds it actually
        //    received, and tells X9.150 the QR Code is paid.
        Map<String, Object> sent = consumer.seen().stream()
                .filter(e -> qrCodeId.equals(e.get("qrCodeId")) && "payment.sent".equals(e.get("type")))
                .findFirst().orElseThrow();

        assertEquals(TX_HASH, sent.get("transactionId"),
                "the event must carry the transaction the consumer has to match on");
        assertEquals("INV-2030-77", sent.get("invoiceNumber"),
                "the event must carry the biller's own reference, so the consumer can route it");

        given().contentType("application/json")
                .body("{\"status\": \"PAID\", \"endToEndId\": \"%s\", \"network\": \"Solana\"}".formatted(TX_HASH))
                .when().put(CREATE + "/" + qrCodeId + "/status-update")
                .then().statusCode(HttpStatus.OK.value());

        assertEquals("PAID", statusOf(qrCodeId));

        // 4. And only now is the QR Code cleared.
        consumer = poll(consumer);
        assertEquals(List.of("payment.initiated", "payment.sent", "payment.cleared"), typesFor(consumer, qrCodeId));
    }

    @Test
    void eventsCarryAStableIdAndAMonotonicCursor() {
        Consumer consumer = new Consumer(List.of(), null);
        String qrCodeId = createQRCode();

        notify(qrCodeId, "PAYMENT_INITIATED", null);
        consumer = poll(consumer);

        Map<String, Object> event = consumer.seen().stream()
                .filter(e -> qrCodeId.equals(e.get("qrCodeId"))).findFirst().orElseThrow();

        assertNotNull(event.get("eventId"), "consumers deduplicate on eventId");
        assertEquals("1.0", event.get("schemaVersion"));
        assertNotNull(consumer.cursor(), "a page must hand back a cursor to resume from");

        // Re-reading from the cursor returns nothing new — the cursor is exclusive.
        Consumer resumed = poll(consumer);
        assertTrue(typesFor(resumed, qrCodeId).size() == typesFor(consumer, qrCodeId).size(),
                "reading again from the same cursor must not replay events");
    }

    @Test
    void aDrainedEventIsNotDrainedTwice() {
        String qrCodeId = createQRCode();
        notify(qrCodeId, "PAYMENT_INITIATED", null);

        paymentEventDrain.drainOnce();
        int secondPass = paymentEventDrain.drainOnce();

        assertEquals(0, secondPass, "the outbox must be empty after a successful drain");

        Consumer consumer = poll(new Consumer(List.of(), null));
        assertEquals(1, typesFor(consumer, qrCodeId).size(), "exactly one event, not a duplicate");
        assertFalse(typesFor(consumer, qrCodeId).isEmpty());
    }

}
