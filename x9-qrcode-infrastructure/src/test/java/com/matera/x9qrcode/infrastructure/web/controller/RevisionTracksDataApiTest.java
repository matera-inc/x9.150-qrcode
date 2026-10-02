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

    /**
     * <b>X9-PATCH-010</b> — a status change does not create a new revision.
     *
     * <p><b>Source:</b> Ours. ADR-0016 — a revision is a version of the request, not of its status.
     *
     * <p><b>Why:</b> A bill marked PAID is the same bill with a new status. Before this, revision was also the
     * Mongo optimistic-lock token — one field doing two jobs — so a bill nobody had edited was
     * reported as version 2, 3, 4, one per thing that had happened to it.
     */
    @Test
    void aStatusChangeDoesNotCreateANewRevision() {
        String id = createQRCode();
        int before = revisionOf(id);

        setStatus(id, "PAYMENT_INITIATED");

        assertEquals(before, revisionOf(id),
                "the request has not changed — only what has happened to it");
    }

    /**
     * <b>X9-PATCH-011</b> — several status changes still do not create a revision.
     *
     * <p><b>Source:</b> Ours. ADR-0016.
     *
     * <p><b>Why:</b> More than one transition, because a rule that holds once can still drift on repetition.
     */
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

    /**
     * <b>X9-PATCH-012</b> — a data change creates a new revision.
     *
     * <p><b>Source:</b> Ours. ADR-0016.
     *
     * <p><b>Why:</b> The other half. If nothing moved the revision it would be constant, which would satisfy
     * PATCH-010 while telling a biller nothing about what they are looking at.
     */
    @Test
    void aDataChangeCreatesANewRevision() {
        String id = createQRCode();
        int before = revisionOf(id);

        patchAmountTo(id, 12500L);

        assertEquals(before + 1, revisionOf(id),
                "the biller changed what is being asked for, so this IS a new version");
    }

    /**
     * <b>X9-PATCH-013</b> — each data change counts once.
     *
     * <p><b>Source:</b> Ours. ADR-0016.
     *
     * <p><b>Why:</b> A patch touching several fields is ONE edit. Counting per field would make the number depend
     * on how the caller batched their changes rather than on how many times the bill changed.
     */
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
     * <b>X9-LIFE-038</b> — the ETag changes on a status change even though the revision does not.
     *
     * <p><b>Source:</b> Ours. ADR-0016 — the ETag covers revision AND status.
     *
     * <p><b>Why:</b> The consequence that makes conditional requests still work after splitting the two. A
     * precondition keyed on the REVISION would stop noticing a payer starting to pay — the exact
     * race the feature exists for. This is why the ETag is opaque and must be echoed, not built.
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

    /**
     * <b>X9-LIFE-039</b> — the ETag also changes on a data change.
     *
     * <p><b>Source:</b> Ours. ADR-0016.
     *
     * <p><b>Why:</b> Both components move it, or a caller could hold a stale tag that still matched after an edit.
     */
    @Test
    void theETagAlsoChangesOnADataChange() {
        String id = createQRCode();
        String tagBefore = eTagOf(id);

        patchAmountTo(id, 12500L);

        assertNotEquals(tagBefore, eTagOf(id));
    }


    // ------------------------------------------------- the same protection on the edit path

    /**
     * <b>X9-PATCH-014</b> — a patch is refused when the QR Code changed after it was read.
     *
     * <p><b>Source:</b> RFC 9110 §13.1.1. Conformance.
     *
     * <p><b>Why:</b> Editing a live bill is the cancel race in a different costume. If-Match was accepted but never
     * declared on this path, so Spring dropped it — a header the caller believed was protecting them
     * doing nothing at all, which is worse than not offering one.
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

    /**
     * <b>X9-PATCH-015</b> — a patch is applied when the tag still matches.
     *
     * <p><b>Source:</b> RFC 9110. Conformance.
     *
     * <p><b>Why:</b> The acceptance case, so PATCH-014 cannot pass against a build that refuses every conditional
     * patch.
     */
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

    /**
     * <b>X9-PATCH-016</b> — a patch without If-Match is still unconditional.
     *
     * <p><b>Source:</b> RFC 9110 — the header is optional. Conformance.
     *
     * <p><b>Why:</b> Backwards compatibility: adding the protection must not break callers who do not use it.
     */
    @Test
    void aPatchWithoutIfMatchIsStillUnconditional() {
        String id = createQRCode();
        setStatus(id, "PAYMENT_INITIATED");

        patchAmountTo(id, 33333L);

        assertEquals(33333, (int) given().when().get(CREATE + "/" + id)
                .then().extract().path("paymentMethods[0].amount"));
    }

}
