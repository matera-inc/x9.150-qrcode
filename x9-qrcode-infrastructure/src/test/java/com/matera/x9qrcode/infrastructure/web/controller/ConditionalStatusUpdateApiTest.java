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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test-and-set on a status transition.
 *
 * <p>The problem it solves: several transitions are legal from more than one status. A cancel is
 * accepted from both {@code ACTIVE} and {@code PAYMENT_INITIATED}, which is deliberate — a biller
 * may need to cancel a QR Code a payer reserved and abandoned. But it leaves a caller who means
 * "cancel only if nobody has started paying" with nothing to express that with, so the only option
 * is to read the status and then write: check-then-act, with a window in which a notification
 * arrives and the cancel lands on a payment already in flight.
 *
 * <p>Small window, and the consequence is money — the combination that makes a bug rare, real and
 * very hard to reproduce. Reported by an adopter implementing "cancel a payment request".
 *
 * <p>The condition is the revision rather than the status, because the revision is the
 * optimistic-lock token: it also catches a PATCH that changed the amount between the read and the
 * write, which a status comparison would sail straight past.
 */
class ConditionalStatusUpdateApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";

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
                "description": "conditional",
                "invoice": { "number": "INV-C-1", "date": "2030-01-10", "dueDate": "2030-12-31T23:59:59Z" },
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

    private int revisionOf(String id) {
        return given().when().get(CREATE + "/" + id)
                .then().statusCode(HttpStatus.OK.value()).extract().path("revision");
    }

    private String statusOf(String id) {
        return given().when().get(CREATE + "/" + id)
                .then().statusCode(HttpStatus.OK.value()).extract().path("status");
    }

    private MockMvcResponse cancel(String id, String ifMatch) {
        var request = given().contentType("application/json").body("{\"status\":\"CANCELLED\"}");

        if (ifMatch != null) {
            request = request.header("If-Match", ifMatch);
        }

        return request.when().put(CREATE + "/" + id + "/status-update");
    }

    // ------------------------------------------------------------------ the condition holds

    @Test
    void aTransitionIsAppliedWhenTheRevisionStillMatches() {
        String id = createQRCode();

        MockMvcResponse response = cancel(id, String.valueOf(revisionOf(id)));

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("CANCELLED", statusOf(id));
    }

    @Test
    void anUnconditionalUpdateStillWorks() {
        String id = createQRCode();

        assertEquals(HttpStatus.OK.value(), cancel(id, null).statusCode(),
                "every existing caller sends no If-Match and must be unaffected");
        assertEquals("CANCELLED", statusOf(id));
    }

    /** `*` is HTTP's "any current representation", which every existing QR Code satisfies. */
    @Test
    void anAsteriskMeansNoCondition() {
        String id = createQRCode();

        assertEquals(HttpStatus.OK.value(), cancel(id, "*").statusCode());
    }

    // ------------------------------------------------------- the condition no longer holds

    /**
     * The race, made deterministic: read at one revision, something happens, then write.
     */
    @Test
    void aTransitionIsRefusedWhenTheQRCodeChangedAfterItWasRead() {
        String id = createQRCode();
        int revisionTheCallerRead = revisionOf(id);

        // Someone else moves it on — the notification that would have arrived in the window.
        given().contentType("application/json").body("{\"status\":\"PAYMENT_INITIATED\"}")
                .when().put(CREATE + "/" + id + "/status-update")
                .then().statusCode(HttpStatus.OK.value());

        MockMvcResponse response = cancel(id, String.valueOf(revisionTheCallerRead));

        assertEquals(HttpStatus.PRECONDITION_FAILED.value(), response.statusCode(),
                "the cancel was decided on a reading that is now stale: " + response.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(id),
                "and nothing may have been written — a payer is midway through paying this");
    }

    /** The refusal has to be actionable without a second request. */
    @Test
    void theRefusalReportsWhatWasActuallyFound() {
        String id = createQRCode();
        int stale = revisionOf(id);

        given().contentType("application/json").body("{\"status\":\"PAYMENT_INITIATED\"}")
                .when().put(CREATE + "/" + id + "/status-update").then().statusCode(HttpStatus.OK.value());

        MockMvcResponse response = cancel(id, String.valueOf(stale));

        assertEquals("PAYMENT_INITIATED", response.jsonPath().getString("currentStatus"),
                "PAYMENT_INITIATED means abandon the cancel; PAID would mean settle instead");
        assertEquals(revisionOf(id), (int) response.jsonPath().getInt("currentRevision"),
                "so the decision can be retaken against the revision actually there");
    }

    // ------------------------------------------------------------------------- malformed

    /**
     * A value we cannot parse is refused rather than ignored. Treating it as unconditional would
     * apply the very write the caller was trying to make conditional.
     */
    @Test
    void aMalformedIfMatchIsRefusedRatherThanIgnored() {
        String id = createQRCode();

        MockMvcResponse response = cancel(id, "not-a-revision");

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        assertEquals("ACTIVE", statusOf(id), "and nothing was written");
    }

    /** Quoted and weak entity tags are what real HTTP clients send. */
    @Test
    void aQuotedOrWeakEntityTagIsAccepted() {
        String first = createQRCode();
        assertEquals(HttpStatus.OK.value(), cancel(first, "\"" + revisionOf(first) + "\"").statusCode());

        String second = createQRCode();
        assertEquals(HttpStatus.OK.value(), cancel(second, "W/\"" + revisionOf(second) + "\"").statusCode());
    }

    // ------------------------------------------- it is a precondition, not a legality check

    /**
     * 412 and 409 answer different questions, and a caller acts differently on each: 409 means the
     * transition is not legal from where the QR Code is; 412 means it may well be legal, but you
     * decided on something stale.
     */
    @Test
    void anIllegalTransitionIsStillA409NotA412() {
        String id = createQRCode();

        given().contentType("application/json").body("{\"status\":\"PAYMENT_INITIATED\"}")
                .when().put(CREATE + "/" + id + "/status-update").then().statusCode(HttpStatus.OK.value());

        MockMvcResponse response = given().contentType("application/json")
                .body("{\"status\":\"PAYMENT_INITIATED\"}")
                .header("If-Match", String.valueOf(revisionOf(id)))
                .when().put(CREATE + "/" + id + "/status-update");

        assertEquals(HttpStatus.CONFLICT.value(), response.statusCode(),
                "the revision matched; what failed is the transition itself: " + response.asString());
        assertTrue(response.asString().contains("currentStatus"));
    }

}
