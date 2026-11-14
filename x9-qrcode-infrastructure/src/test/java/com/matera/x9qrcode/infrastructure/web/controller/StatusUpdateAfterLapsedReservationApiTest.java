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
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BUG-002 (ADR-0035 row X9-MAP-010): a status-update {@code PAYMENT_INITIATED} on a QR Code whose
 * reservation has lapsed reserves it again.
 *
 * <p>Before the fix the "already in that status" no-op compared the STORED status
 * ({@code PAYMENT_INITIATED}), so the call answered 200 {@code ACTIVE}, wrote nothing and emitted
 * nothing: the payment system believed it held the QR Code while any payer could take it.
 *
 * <p><b>The window is shortened to one second for this test</b> and the test waits past it.
 */
@TestPropertySource(properties = "x9.reservation.ttl-seconds=1")
class StatusUpdateAfterLapsedReservationApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String EVENTS = "/pub/api/v1/events";
    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final long PAST_THE_WINDOW_MILLIS = 2_000L;

    @Autowired
    private PaymentEventDrain paymentEventDrain;

    private String createQRCode() {
        String body = """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Acme", "phone": "+15552223333", "email": "billing@example.com",
                "address": { "line1": "1 A St", "city": "Los Angeles", "state": "CA",
                             "postalCode": "90012", "country": "US" },
                "MCC": "4900"
              },
              "bill": { "description": "status-update after lapse", "amountDue": { "amount": 22500, "currency": "USDC" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 22500,
                  "networks": { "solana": { "recipient": "%s" } } }
              ]
            }
            """.formatted(RECIPIENT);
        return given().contentType("application/json").body(body)
                .when().post(CREATE).then().statusCode(HttpStatus.CREATED.value()).extract().path("id");
    }

    private MockMvcResponse initiate(String id) {
        return given().contentType("application/json").body("{\"status\":\"PAYMENT_INITIATED\"}")
                .when().put(CREATE + "/" + id + "/status-update");
    }

    private List<String> eventTypes(String qrCodeId) {
        // Force a drain rather than waiting for the scheduler, so the test is deterministic.
        paymentEventDrain.drainOnce();
        List<String> types = new ArrayList<>();
        String cursor = "";
        boolean hasMore = true;
        while (hasMore) {
            JsonPath page = given().queryParam("after", cursor).queryParam("limit", 500)
                    .when().get(EVENTS).then().statusCode(HttpStatus.OK.value()).extract().jsonPath();
            List<Map<String, Object>> events = page.getList("events");
            events.stream().filter(e -> qrCodeId.equals(e.get("qrCodeId")))
                    .forEach(e -> types.add((String) e.get("type")));
            String next = page.getString("nextCursor");
            hasMore = page.getBoolean("hasMore") && next != null;
            cursor = next == null ? cursor : next;
        }
        return types;
    }

    /**
     * <b>X9-LIFE-142</b> — a status-update PAYMENT_INITIATED after the reservation lapsed reserves
     * the QR Code again and emits payment.initiated.
     *
     * <p><b>Source:</b> Ours. ADR-0035 (row X9-MAP-010), ADR-0020. BUG-002.
     *
     * <p><b>Why:</b> The no-op for "already in that status" must read the status in effect. A lapsed
     * reservation is ACTIVE, so asking for PAYMENT_INITIATED is a real transition; skipping it left
     * the payment system believing it held a QR Code that any payer could take.
     */
    @Test
    void aStatusUpdateAfterALapseReservesTheQRCodeAgain() throws InterruptedException {
        String id = createQRCode();
        assertEquals(HttpStatus.OK.value(), initiate(id).statusCode());
        Thread.sleep(PAST_THE_WINDOW_MILLIS);
        assertEquals("ACTIVE", given().when().get(CREATE + "/" + id).jsonPath().getString("status"));

        MockMvcResponse again = initiate(id);

        assertEquals(HttpStatus.OK.value(), again.statusCode(), again.asString());
        assertEquals("PAYMENT_INITIATED", again.jsonPath().getString("status"));
        assertEquals("PAYMENT_INITIATED", given().when().get(CREATE + "/" + id).jsonPath().getString("status"));
        assertEquals(List.of("payment.initiated", "payment.initiated"), eventTypes(id));
    }

    /**
     * <b>X9-LIFE-143</b> — while the reservation is live, a repeated status-update PAYMENT_INITIATED
     * is still a no-op: 200, no event.
     *
     * <p><b>Source:</b> Ours. ADR-0020. BUG-002 (negative control).
     *
     * <p><b>Why:</b> X9-LIFE-142 would also pass against a build that dropped the retry no-op
     * altogether; a retry must stay safe.
     */
    @Test
    void aRepeatedStatusUpdateWhileTheReservationIsLiveIsStillANoOp() {
        String id = createQRCode();
        assertEquals(HttpStatus.OK.value(), initiate(id).statusCode());

        MockMvcResponse again = initiate(id);

        assertEquals(HttpStatus.OK.value(), again.statusCode(), again.asString());
        assertEquals("PAYMENT_INITIATED", again.jsonPath().getString("status"));
        assertEquals(List.of("payment.initiated"), eventTypes(id));
    }
}
