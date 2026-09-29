/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * What {@code revision} counts.
 *
 * <p>A payment request marked PAID or CANCELLED is <em>the same request with a new status</em>, not
 * a new version of it. A new version appears when a caller changes what is being asked for — the
 * amount due, the due date, a payment method — through PATCH.
 *
 * <p>It used to count both, because {@code revision} was doing double duty as the Mongo
 * {@code @Version} optimistic-lock token, which necessarily moves on every write. The two are now
 * separate fields: the lock token is internal and moves whenever the document does, while
 * {@code revision} is the payment request's own version and moves only when its data does.
 */
class RevisionTracksDataApiTest extends AbstractIntegrationTest {

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
                "description": "revision semantics",
                "invoice": { "number": "INV-R-1", "date": "2030-01-10", "dueDate": "2030-12-31T23:59:59Z" },
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

    private String eTagOf(String id) {
        return given().when().get(CREATE + "/" + id)
                .then().statusCode(HttpStatus.OK.value()).extract().header("ETag");
    }

    private void setStatus(String id, String status) {
        given().contentType("application/json").body("{\"status\":\"%s\"}".formatted(status))
                .when().put(CREATE + "/" + id + "/status-update")
                .then().statusCode(HttpStatus.OK.value());
    }

    private void patchAmountTo(String id, long amount) {
        String body = """
            { "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "solana": { "recipient": "%s" } } } ] }
            """.formatted(amount, RECIPIENT);

        given().contentType("application/json").body(body)
                .when().patch(CREATE + "/" + id).then().statusCode(HttpStatus.OK.value());
    }

    // ----------------------------------------------------------------- a status is not a version

    @Test
    void aStatusChangeDoesNotCreateANewRevision() {
        String id = createQRCode();
        int before = revisionOf(id);

        setStatus(id, "PAYMENT_INITIATED");

        assertEquals(before, revisionOf(id),
                "the request has not changed — only what has happened to it");
    }

    @Test
    void severalStatusChangesStillDoNotCreateARevision() {
        String id = createQRCode();
        int before = revisionOf(id);

        setStatus(id, "PAYMENT_INITIATED");
        // PAID additionally requires the reference of the payment that cleared it.
        given().contentType("application/json")
                .body("{\"status\":\"PAID\",\"network\":\"Solana\",\"endToEndId\":\"5Vfydn\"}")
                .when().put(CREATE + "/" + id + "/status-update")
                .then().statusCode(HttpStatus.OK.value());

        assertEquals(before, revisionOf(id));
    }

    // ------------------------------------------------------------------- a data change is one

    @Test
    void aDataChangeCreatesANewRevision() {
        String id = createQRCode();
        int before = revisionOf(id);

        patchAmountTo(id, 12500L);

        assertEquals(before + 1, revisionOf(id),
                "the biller changed what is being asked for, so this IS a new version");
    }

    @Test
    void eachDataChangeCountsOnce() {
        String id = createQRCode();
        int before = revisionOf(id);

        patchAmountTo(id, 12500L);
        patchAmountTo(id, 11000L);

        assertEquals(before + 2, revisionOf(id));
    }

    // ------------------------------------------- but a conditional request still sees both

    /**
     * The consequence that has to be handled rather than accepted: now that `revision` correctly
     * ignores status, a conditional request keyed on it alone would no longer notice a payer
     * starting to pay — which is the single thing the feature exists to catch. The ETag covers both.
     */
    @Test
    void theETagStillChangesOnAStatusChangeEvenThoughTheRevisionDoesNot() {
        String id = createQRCode();
        String tagBefore = eTagOf(id);
        int revisionBefore = revisionOf(id);

        setStatus(id, "PAYMENT_INITIATED");

        assertEquals(revisionBefore, revisionOf(id), "revision is unmoved, correctly");
        assertNotEquals(tagBefore, eTagOf(id),
                "but a caller holding the old tag must still be refused: someone is paying this");
    }

    @Test
    void theETagAlsoChangesOnADataChange() {
        String id = createQRCode();
        String tagBefore = eTagOf(id);

        patchAmountTo(id, 12500L);

        assertNotEquals(tagBefore, eTagOf(id));
    }


    // ------------------------------------------------- the same protection on the edit path

    /**
     * Editing a live bill has the cancel race in a different costume, and an adopter found that
     * `If-Match` was accepted and then silently ignored here — a header a caller believes is
     * protecting them, doing nothing, is worse than not offering it.
     */
    @Test
    void aPatchIsRefusedWhenTheQRCodeChangedAfterItWasRead() {
        String id = createQRCode();
        String tagTheCallerRead = eTagOf(id);

        // A payer starts paying in the window between the read and the edit.
        setStatus(id, "PAYMENT_INITIATED");

        String body = """
            { "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 44444,
                  "networks": { "solana": { "recipient": "%s" } } } ] }
            """.formatted(RECIPIENT);

        given().contentType("application/json").header("If-Match", tagTheCallerRead).body(body)
                .when().patch(CREATE + "/" + id)
                .then().statusCode(HttpStatus.PRECONDITION_FAILED.value());

        assertEquals(22500, (int) given().when().get(CREATE + "/" + id)
                        .then().extract().path("paymentMethods[0].amount"),
                "nothing may have been written to a bill someone is midway through paying");
    }

    @Test
    void aPatchIsAppliedWhenTheTagStillMatches() {
        String id = createQRCode();

        String body = """
            { "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 44444,
                  "networks": { "solana": { "recipient": "%s" } } } ] }
            """.formatted(RECIPIENT);

        given().contentType("application/json").header("If-Match", eTagOf(id)).body(body)
                .when().patch(CREATE + "/" + id)
                .then().statusCode(HttpStatus.OK.value());

        assertEquals(44444, (int) given().when().get(CREATE + "/" + id)
                .then().extract().path("paymentMethods[0].amount"));
    }

    /** An unconditional patch keeps working, because every existing caller sends no If-Match. */
    @Test
    void aPatchWithoutIfMatchIsStillUnconditional() {
        String id = createQRCode();
        setStatus(id, "PAYMENT_INITIATED");

        patchAmountTo(id, 33333L);

        assertEquals(33333, (int) given().when().get(CREATE + "/" + id)
                .then().extract().path("paymentMethods[0].amount"));
    }

}
