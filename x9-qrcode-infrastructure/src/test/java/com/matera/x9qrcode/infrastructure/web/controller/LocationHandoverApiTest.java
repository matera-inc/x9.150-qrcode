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

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Handing a location from one QR Code to the next, which is how a printed image outlives the QR Code
 * it was printed for.
 *
 * <p>A payment request may be collected by several QR Codes. A bill for 22500 receives 10000; that
 * QR Code's life is over, and a new one asks for the 12500 still owed. The image on the invoice must
 * keep working, so the new QR Code takes over the old one's location — the location, not the id, is
 * what the EMV encodes.
 *
 * <p>The handover was building the assignment and discarding it:
 *
 * <pre>
 *   input.locationId().ifPresent(locationId -&gt; treatLocationUpdate(locationId, qrCodeEntity));
 *   // treatLocationUpdate returns patchQRCodeEntity::updateLocationId — never applied
 * </pre>
 *
 * <p>So the previous holder was released and nobody took the location. That is worse than failing:
 * the request answered 200, and the QR Code already in a customer's hands quietly stopped resolving.
 */
class LocationHandoverApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";

    private MockMvcResponse create(long amount, String invoice) {
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
                "description": "partial payment",
                "invoice": { "number": "%s", "date": "2030-01-10", "dueDate": "2030-12-31T23:59:59Z" },
                "amountDue": { "amount": %d, "currency": "USDC" }
              },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "solana": { "recipient": "%s" } } }
              ]
            }
            """.formatted(invoice, amount, amount, RECIPIENT);

        return given().contentType("application/json").body(body).when().post(CREATE);
    }

    private void markPaid(String id, String reference) {
        given().contentType("application/json")
                .body("{\"status\":\"PAID\",\"network\":\"Solana\",\"endToEndId\":\"%s\"}".formatted(reference))
                .when().put(CREATE + "/" + id + "/status-update")
                .then().statusCode(HttpStatus.OK.value());
    }

    private MockMvcResponse claimLocation(String id, String locationId, long amount) {
        String body = """
            { "locationId": "%s",
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "solana": { "recipient": "%s" } } } ] }
            """.formatted(locationId, amount, RECIPIENT);

        return given().contentType("application/json").body(body).when().patch(CREATE + "/" + id);
    }

    private String locationOf(String id) {
        return given().when().get(CREATE + "/" + id)
                .then().statusCode(HttpStatus.OK.value()).extract().path("location.id");
    }

    // ------------------------------------------------------------------------ the handover

    /**
     * <b>X9-LOC-010</b> — the new QR Code actually takes over the location.
     *
     * <p><b>Source:</b> Ours. Reusing a location is this implementation's; X9.150 has no concept of it.
     *
     * <p><b>Why:</b> The donor released its location and it was given to NOBODY — the consumer was discarded, so a
     * QR Code already printed and in a payer's hands stopped resolving, while the API answered 200.
     */
    @Test
    void theNewQRCodeActuallyTakesOverTheLocation() {
        MockMvcResponse first = create(22500L, "INV-H-1");
        String firstId = first.path("id");
        String location = first.path("location.id");

        markPaid(firstId, "collected-10000");

        String secondId = create(12500L, "INV-H-2").path("id");

        assertEquals(HttpStatus.OK.value(), claimLocation(secondId, location, 12500L).statusCode());
        assertEquals(location, locationOf(secondId),
                "the new QR Code must hold the location, not merely have freed it from the old one");
    }

    /**
     * <b>X9-LOC-011</b> — the printed image survives and serves the new amount.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> The whole point of hand-over: the sticker on the table does not change, what it asks for does.
     * If the image had to be reprinted there would be no reason to move the location at all.
     */
    @Test
    void thePrintedImageSurvivesAndServesTheNewAmount() {
        MockMvcResponse first = create(22500L, "INV-H-3");
        String firstId = first.path("id");
        String location = first.path("location.id");
        String printedEmv = first.path("qrCode");

        markPaid(firstId, "collected-10000");

        String secondId = create(12500L, "INV-H-4").path("id");
        MockMvcResponse handover = claimLocation(secondId, location, 12500L);

        assertEquals(printedEmv, handover.path("qrCode"),
                "a customer holding the printed code must not need a new one");
        assertNotEquals(location, locationOf(firstId),
                "and the QR Code that was paid must have let the location go");
    }

    /**
     * <b>X9-LOC-012</b> — moving the location is not a no-op even when the amounts are unchanged.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> The "nothing to update" guard judged the patch on its payment methods alone, so re-pointing a
     * printed QR Code at a different bill for the same amount was refused as an empty change.
     */
    @Test
    void movingTheLocationIsNotANoOpEvenWhenTheAmountsAreUnchanged() {
        MockMvcResponse first = create(12500L, "INV-H-5");
        String firstId = first.path("id");
        String location = first.path("location.id");

        markPaid(firstId, "collected");

        String secondId = create(12500L, "INV-H-6").path("id");

        assertEquals(HttpStatus.OK.value(), claimLocation(secondId, location, 12500L).statusCode(),
                "the amounts match by coincidence; the location moved, so something changed");
    }

    /**
     * <b>X9-LOC-013</b> — a location held by a live QR Code is not handed over.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> A payer holding the printed code can still pay the QR Code that owns it. Moving the location
     * out from under them would make a code that was valid a second ago resolve to someone else's
     * bill. The donor must be cancelled first.
     */
    @Test
    void aLocationHeldByALiveQRCodeIsNotHandedOver() {
        MockMvcResponse first = create(22500L, "INV-H-7");
        String location = first.path("location.id");

        String secondId = create(12500L, "INV-H-8").path("id");

        assertNotEquals(HttpStatus.OK.value(), claimLocation(secondId, location, 12500L).statusCode(),
                "the first QR Code is still ACTIVE and collecting");
    }

}
