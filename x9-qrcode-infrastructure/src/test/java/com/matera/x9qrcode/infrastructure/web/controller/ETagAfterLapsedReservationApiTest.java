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
import org.springframework.test.context.TestPropertySource;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 2.2 (ADR-0028): the ETag a caller is given after a reservation lapsed is the one the server
 * compares {@code If-Match} against.
 *
 * <p>Before the fix GET built its ETag from the effective status ({@code ACTIVE} once the
 * reservation lapsed) while {@code If-Match} was compared against the stored one
 * ({@code PAYMENT_INITIATED}), so every conditional request after a lapse answered 412.
 *
 * <p><b>The window is shortened to one second for this test</b> and the test waits past it. The
 * reservation is taken through the payee's status-update, which is stamped with the same window as
 * a payer's announcement and does not depend on the rail's notification rules.
 */
@TestPropertySource(properties = "x9.reservation.ttl-seconds=1")
class ETagAfterLapsedReservationApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final long PAST_THE_WINDOW_MILLIS = 2_000L;

    private static final String PATCH_BODY = """
        { "paymentMethods": [
            { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 44444,
              "networks": { "solana": { "recipient": "%s" } } } ] }
        """.formatted(RECIPIENT);

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
              "bill": {
                "description": "etag after lapse",
                "invoice": { "number": "INV-E-1", "date": "2030-01-10", "dueDate": "2030-12-31T23:59:59Z" },
                "amountDue": { "amount": 22500, "currency": "USDC" }
              },
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

    private String lapsedReservation() throws InterruptedException {
        String id = createQRCode();

        given().contentType("application/json").body("{\"status\":\"PAYMENT_INITIATED\"}")
                .when().put(CREATE + "/" + id + "/status-update")
                .then().statusCode(HttpStatus.OK.value());

        Thread.sleep(PAST_THE_WINDOW_MILLIS);
        return id;
    }

    private MockMvcResponse get(String id) {
        return given().when().get(CREATE + "/" + id);
    }

    private MockMvcResponse patch(String id, String ifMatch) {
        return given().contentType("application/json").header("If-Match", ifMatch).body(PATCH_BODY)
                .when().patch(CREATE + "/" + id);
    }

    /**
     * <b>X9-LIFE-047</b> — after a reservation lapses, a PATCH carrying the ETag GET gave succeeds.
     *
     * <p><b>Source:</b> Ours. ADR-0016, ADR-0028. S2.2-AC1, S2.2-AC2.
     *
     * <p><b>Why:</b> The bug this story fixes. A caller who did exactly what the contract says —
     * read the ETag, send it back — was refused with 412 for no reason but the passage of time, and
     * could never succeed with any tag the server would hand out.
     */
    @Test
    void afterAReservationLapsesAPatchCarryingTheETagGetGaveSucceeds() throws InterruptedException {
        String id = lapsedReservation();

        MockMvcResponse read = get(id);
        assertEquals("ACTIVE", read.jsonPath().getString("status"));
        String eTag = read.header("ETag");
        assertTrue(eTag.endsWith("-ACTIVE\""), "the ETag must carry the effective status: " + eTag);

        MockMvcResponse patched = patch(id, eTag);
        assertEquals(HttpStatus.OK.value(), patched.statusCode(), patched.asString());

        assertEquals(44444, (int) get(id).then().extract().path("paymentMethods[0].amount"));
    }

    /**
     * <b>X9-LIFE-048</b> — after a lapse, a stale tag is still refused with 412.
     *
     * <p><b>Source:</b> Ours. ADR-0016, RFC 9110 If-Match. S2.2-AC3.
     *
     * <p><b>Why:</b> The negative control: X9-LIFE-047 would also pass against a build that stopped
     * checking If-Match altogether. A tag from another revision, or one naming the stored status the
     * caller was never shown, must still be refused, and nothing may be written.
     */
    @Test
    void afterALapseAStaleTagIsStillRefused() throws InterruptedException {
        String id = lapsedReservation();
        String eTag = get(id).header("ETag");
        int revision = Integer.parseInt(eTag.replace("\"", "").split("-")[0]);

        assertEquals(HttpStatus.PRECONDITION_FAILED.value(),
                patch(id, "\"%d-ACTIVE\"".formatted(revision + 1)).statusCode());
        assertEquals(HttpStatus.PRECONDITION_FAILED.value(),
                patch(id, "\"%d-PAYMENT_INITIATED\"".formatted(revision)).statusCode());

        assertEquals(22500, (int) get(id).then().extract().path("paymentMethods[0].amount"),
                "a refused precondition must leave the QR Code as it was");
    }
}
