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
 * cannot know a payment settled — it only knows what a payer claimed. An ACH notification announces
 * a debit the payer has not yet originated: it publishes {@code payment.initiated} and takes the QR
 * Code out of circulation, and there it stops. Some other system watches the funds arrive, matches
 * the trace number it was told about, and calls back to say the QR Code is paid. Only that produces
 * {@code payment.cleared}.
 *
 * <p>{@code payment.sent} has no bank-rail trigger. It reports a transaction already committed on a
 * public ledger, which is a distinction only a blockchain offers — the payer can point at a txHash
 * anyone can verify. An ACH debit has no such moment between "announced" and "settled" that the
 * payer could evidence, so the event stays unemitted until an interpreted chain returns.
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

    private static final String TRACE_NUMBER = "021000021.0000001";
    private static final long AMOUNT = 25_000L;

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
                "amountDue": { "amount": %d, "currency": "USD" }
              },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "ach": { "routingNumber": "021000021", "accountNumber": "1234567890",
                                         "protectionType": "tokenized" } } }
              ]
            }
            """.formatted(AMOUNT, AMOUNT);

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

    private MockMvcResponse notify(String qrCodeId) {
        String body = """
            {
              "payment": { "qrcodeId": "%s", "transactionId": "%s", "amount": %d, "currency": "USD",
                           "network": "ACH" },
              "payer": { "info": "Jane Payer, Springfield Savings" },
              "expectedDate": "2030-10-08T06:59:59Z"
            }
            """.formatted(qrCodeId, TRACE_NUMBER, AMOUNT);

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

    /**
     * <b>X9-EVT-001</b> — a consumer watches a payment through to settlement.
     *
     * <p><b>Source:</b> Ours. X9.150 defines no event stream; ADR-0014 — we transport and sequence, the consumer
     * reconciles.
     *
     * <p><b>Why:</b> The end-to-end case for how software around x9.150 learns a QR Code was paid, without polling
     * every QR Code it ever created.
     */
    @Test
    void aConsumerWatchesAPaymentThroughToSettlement() {
        Consumer consumer = new Consumer(List.of(), null);
        String qrCodeId = createQRCode();

        // 1. Pre-commit. Funds have not moved; this is the notification that takes the QR Code out
        //    of circulation.
        assertEquals(HttpStatus.OK.value(), notify(qrCodeId).statusCode());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));

        consumer = poll(consumer);
        assertEquals(List.of("payment.initiated"), typesFor(consumer, qrCodeId),
                "the consumer should learn the payment started");

        // 2. The payer's report is not settlement. X9.150 never touches money and cannot see funds
        //    arrive, so nothing here may move the QR Code to PAID on a payer's say-so. The consuming
        //    system matches the trace number against funds it actually received, and only then calls
        //    the status API.
        Map<String, Object> initiated = consumer.seen().stream()
                .filter(e -> qrCodeId.equals(e.get("qrCodeId")) && "payment.initiated".equals(e.get("type")))
                .findFirst().orElseThrow();

        assertEquals(TRACE_NUMBER, initiated.get("transactionId"),
                "the event must carry the transaction the consumer has to match on");
        assertEquals("INV-2030-77", initiated.get("invoiceNumber"),
                "the event must carry the biller's own reference, so the consumer can route it");

        // 3. Settlement observed elsewhere, reported back here.
        given().contentType("application/json")
                .body("{\"status\": \"PAID\", \"endToEndId\": \"%s\", \"network\": \"ACH\"}".formatted(TRACE_NUMBER))
                .when().put(CREATE + "/" + qrCodeId + "/status-update")
                .then().statusCode(HttpStatus.OK.value());

        assertEquals("PAID", statusOf(qrCodeId));

        // 4. And only now is the QR Code cleared.
        consumer = poll(consumer);
        assertEquals(List.of("payment.initiated", "payment.cleared"), typesFor(consumer, qrCodeId));
    }

    /**
     * <b>X9-EVT-002</b> — events carry a stable id and a monotonic cursor.
     *
     * <p><b>Source:</b> Ours. ADR-0014.
     *
     * <p><b>Why:</b> Delivery is at-least-once, so a consumer that crashes before persisting its cursor re-reads
     * events. Deduplication needs the id to be STABLE across re-publishes, and resumption needs the
     * cursor to never go backwards. Without both, a crash means duplicate or lost payments.
     */
    @Test
    void eventsCarryAStableIdAndAMonotonicCursor() {
        Consumer consumer = new Consumer(List.of(), null);
        String qrCodeId = createQRCode();

        notify(qrCodeId);
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

    /**
     * <b>X9-EVT-003</b> — a drained event is not drained twice.
     *
     * <p><b>Source:</b> Ours. ADR-0002 — the embedded transactional outbox.
     *
     * <p><b>Why:</b> Two pollers each seeing everything gains no isolation and only invites the belief that it
     * provides some. Exactly one system should poll a deployment.
     */
    @Test
    void aDrainedEventIsNotDrainedTwice() {
        String qrCodeId = createQRCode();
        notify(qrCodeId);

        paymentEventDrain.drainOnce();
        int secondPass = paymentEventDrain.drainOnce();

        assertEquals(0, secondPass, "the outbox must be empty after a successful drain");

        Consumer consumer = poll(new Consumer(List.of(), null));
        assertEquals(1, typesFor(consumer, qrCodeId).size(), "exactly one event, not a duplicate");
        assertFalse(typesFor(consumer, qrCodeId).isEmpty());
    }

}
