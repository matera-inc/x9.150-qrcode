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

    /** What a caller actually echoes back: the ETag, opaque and covering revision AND status. */
    private String eTagOf(String id) {
        return given().when().get(CREATE + "/" + id)
                .then().statusCode(HttpStatus.OK.value()).extract().header("ETag");
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

    /**
     * <b>X9-LIFE-030</b> — a transition is applied when the entity tag still matches.
     *
     * <p><b>Source:</b> Ours. HTTP conditional requests (RFC 9110 If-Match) applied to a status transition; X9.150
     * says nothing about editing. ADR-0016.
     *
     * <p><b>Why:</b> The acceptance case for compare-and-set. Requested by an adopter who needed "cancel only if
     * still ACTIVE" and could otherwise only do check-then-act, with a payer able to reserve the QR
     * Code between the two calls.
     */
    @Test
    void aTransitionIsAppliedWhenTheRevisionStillMatches() {
        String id = createQRCode();

        MockMvcResponse response = cancel(id, eTagOf(id));

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("CANCELLED", statusOf(id));
    }

    /**
     * <b>X9-LIFE-031</b> — an update without If-Match is still unconditional.
     *
     * <p><b>Source:</b> RFC 9110 — If-Match is optional. Conformance.
     *
     * <p><b>Why:</b> Making the header mandatory would break every existing caller. The protection is opt-in, and
     * a caller that does not need it is not forced to carry a tag.
     */
    @Test
    void anUnconditionalUpdateStillWorks() {
        String id = createQRCode();

        assertEquals(HttpStatus.OK.value(), cancel(id, null).statusCode(),
                "every existing caller sends no If-Match and must be unaffected");
        assertEquals("CANCELLED", statusOf(id));
    }

    /**
     * <b>X9-LIFE-032</b> — If-Match: * means no condition.
     *
     * <p><b>Source:</b> RFC 9110 §13.1.1 — "*" matches any current representation. Conformance.
     *
     * <p><b>Why:</b> A generic HTTP client that always sends If-Match must not be refused for using the wildcard
     * the specification defines for exactly that case.
     */
    @Test
    void anAsteriskMeansNoCondition() {
        String id = createQRCode();

        assertEquals(HttpStatus.OK.value(), cancel(id, "*").statusCode());
    }

    // ------------------------------------------------------- the condition no longer holds

    /**
     * <b>X9-LIFE-033</b> — a transition is refused when the QR Code changed after it was read.
     *
     * <p><b>Source:</b> RFC 9110 §13.1.1 — 412 Precondition Failed. Conformance.
     *
     * <p><b>Why:</b> The race this exists to close: a payer reaches PAYMENT_INITIATED between the read that decided
     * to cancel and the write that applies it. Small window, and the consequence is money.
     */
    @Test
    void aTransitionIsRefusedWhenTheQRCodeChangedAfterItWasRead() {
        String id = createQRCode();
        String tagTheCallerRead = eTagOf(id);

        // Someone else moves it on — the notification that would have arrived in the window.
        given().contentType("application/json").body("{\"status\":\"PAYMENT_INITIATED\"}")
                .when().put(CREATE + "/" + id + "/status-update")
                .then().statusCode(HttpStatus.OK.value());

        MockMvcResponse response = cancel(id, tagTheCallerRead);

        assertEquals(HttpStatus.PRECONDITION_FAILED.value(), response.statusCode(),
                "the cancel was decided on a reading that is now stale: " + response.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(id),
                "and nothing may have been written — a payer is midway through paying this");
    }

    /**
     * <b>X9-LIFE-034</b> — the refusal reports what was actually found.
     *
     * <p><b>Source:</b> Ours. The status code is RFC 9110's; returning currentStatus and currentRevision with it is
     * this implementation's.
     *
     * <p><b>Why:</b> A 412 that only says "no" costs the caller another round trip to find out why. With the
     * current state attached, the adopter decides directly: PAID means treat it as a payment,
     * CANCELLED means the work is already done.
     */
    @Test
    void theRefusalReportsWhatWasActuallyFound() {
        String id = createQRCode();
        String stale = eTagOf(id);

        given().contentType("application/json").body("{\"status\":\"PAYMENT_INITIATED\"}")
                .when().put(CREATE + "/" + id + "/status-update").then().statusCode(HttpStatus.OK.value());

        MockMvcResponse response = cancel(id, stale);

        assertEquals("PAYMENT_INITIATED", response.jsonPath().getString("currentStatus"),
                "PAYMENT_INITIATED means abandon the cancel; PAID would mean settle instead");
        assertEquals(revisionOf(id), (int) response.jsonPath().getInt("currentRevision"),
                "so the decision can be retaken against the revision actually there");
    }

    // ------------------------------------------------------------------------- malformed

    /**
     * <b>X9-LIFE-035</b> — an unrecognised If-Match is refused rather than ignored.
     *
     * <p><b>Source:</b> RFC 9110 — an unsatisfiable precondition fails. Conformance.
     *
     * <p><b>Why:</b> Fails CLOSED. A header the server cannot interpret must not be silently dropped, or a caller
     * who believes they are protected is not — which is worse than never offering the header, and
     * is exactly what happened when If-Match went undeclared on PATCH.
     */
    @Test
    void anUnrecognisedIfMatchIsRefusedRatherThanIgnored() {
        String id = createQRCode();

        MockMvcResponse response = cancel(id, "not-a-tag");

        assertEquals(HttpStatus.PRECONDITION_FAILED.value(), response.statusCode(), response.asString());
        assertEquals("ACTIVE", statusOf(id), "and nothing was written");
    }

    /**
     * <b>X9-LIFE-036</b> — a quoted or weak entity tag is accepted.
     *
     * <p><b>Source:</b> RFC 9110 §8.8.3 — entity tags are quoted and may be weak (W/). Conformance.
     *
     * <p><b>Why:</b> Clients and proxies normalise tags differently. Rejecting W/"1-ACTIVE" would make the feature
     * work or not depending on the HTTP stack in between.
     */
    @Test
    void aQuotedOrWeakEntityTagIsAccepted() {
        String first = createQRCode();
        assertEquals(HttpStatus.OK.value(), cancel(first, eTagOf(first)).statusCode());

        String second = createQRCode();
        assertEquals(HttpStatus.OK.value(), cancel(second, "W/" + eTagOf(second)).statusCode());
    }

    // ------------------------------------------- it is a precondition, not a legality check

    /**
     * <b>X9-LIFE-037</b> — an illegal transition is 409, not 412.
     *
     * <p><b>Source:</b> RFC 9110 — 412 means the precondition failed; 409 means the request conflicts with state.
     * Conformance.
     *
     * <p><b>Why:</b> The tag matched, so the caller's view was current — the transition is simply not allowed.
     * Returning 412 would tell them to re-read and retry, which would fail identically forever.
     */
    @Test
    void anIllegalTransitionIsStillA409NotA412() {
        String id = createQRCode();

        // PAID -> PAYMENT_INITIATED. Chosen because it is genuinely illegal: the previous example
        // here was PAYMENT_INITIATED -> PAYMENT_INITIATED, which is now an idempotent no-op under
        // ADR-0020 and so proves nothing about transitions.
        given().contentType("application/json")
                .body("{\"status\":\"PAID\",\"endToEndId\":\"E2E-ILLEGAL-1\",\"network\":\"solana\"}")
                .when().put(CREATE + "/" + id + "/status-update").then().statusCode(HttpStatus.OK.value());

        MockMvcResponse response = given().contentType("application/json")
                .body("{\"status\":\"PAYMENT_INITIATED\"}")
                .header("If-Match", eTagOf(id))
                .when().put(CREATE + "/" + id + "/status-update");

        assertEquals(HttpStatus.CONFLICT.value(), response.statusCode(),
                "the revision matched; what failed is the transition itself: " + response.asString());
        assertTrue(response.asString().contains("currentStatus"));
    }

}
