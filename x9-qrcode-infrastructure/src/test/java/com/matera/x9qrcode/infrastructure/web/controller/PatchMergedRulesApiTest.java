/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.domain.utils.BusinessClock;
import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSObject;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The owner's PATCH rules of 2026-11-15, through the API: a PATCH is held to the create's rules on
 * its merged result (the tip, and a date it sets already past), an earlier {@code validUntil} cuts
 * a running reservation back, and what Story 9.7 found Java dropping or failing on is applied or
 * refused by name. Also {@code sentAt}, never before {@code revisedAt}. The Go build answers the
 * same (internal/paymentrequest/patch_rules_test.go); the black-box suite pins both
 * (test_patch_merged_rules.py).
 */
class PatchMergedRulesApiTest extends AbstractIntegrationTest {

    private static final String PR = "/api/v1/payment-request";
    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final String FAR = "2030-12-31T23:59:59Z";

    @AfterEach
    void resetTheClock() {
        BusinessClock.reset();
    }

    private static String utc(OffsetDateTime at) {
        return at.toInstant().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    private static String method(String validUntil, String extra) {
        return """
            { "currency": "USDC", "validUntil": "%s", "amount": 1000, %s
              "networks": { "solana": { "recipient": "%s" } } }
            """.formatted(validUntil, extra, RECIPIENT);
    }

    private static String method(String validUntil) {
        return method(validUntil, "");
    }

    private static String create(String validUntil, String bill, String method) {
        String body = """
            {
              "validUntil": "%s",
              "creditor": {
                "name": "Acme", "phone": "+15552223333", "email": "billing@example.com",
                "address": { "line1": "1 A St", "city": "Los Angeles", "state": "CA",
                             "postalCode": "90012", "country": "US" },
                "MCC": "4900"
              },
              "bill": %s,
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [ %s ]
            }
            """.formatted(validUntil, bill, method);

        return given().contentType("application/json").body(body).when().post(PR)
            .then().statusCode(HttpStatus.CREATED.value()).extract().path("id");
    }

    private static String bill(String extra) {
        return """
            { "description": "merged rules", "paymentTiming": "immediate", %s
              "amountDue": { "amount": 1000, "currency": "USDC" } }
            """.formatted(extra);
    }

    private static String patchBill(String extra, String amountDue) {
        return """
            { "description": "merged rules", "paymentTiming": "immediate", %s "amountDue": %s }
            """.formatted(extra, amountDue);
    }

    private static String patchBill(String extra) {
        return patchBill(extra, "{ \"amount\": 1000 }");
    }

    private static String invoice(String dueDate) {
        return "\"invoice\": { \"number\": \"INV-1\", \"date\": \"2020-01-01\", \"dueDate\": \"%s\" },".formatted(dueDate);
    }

    private static MockMvcResponse patch(String id, String body) {
        return given().contentType("application/json").body(body).when().patch(PR + "/" + id);
    }

    private static JsonPath read(String id) {
        return given().when().get(PR + "/" + id).then().statusCode(HttpStatus.OK.value()).extract().jsonPath();
    }

    private static void assertRefusedUnchanged(String id, String body, String naming) {
        JsonPath before = read(id);
        MockMvcResponse response = patch(id, body);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        List<String> violations = response.jsonPath().getList("violations");
        assertTrue(violations.stream().anyMatch(violation -> violation.contains(naming)),
            "the refusal must say " + naming + ": " + response.asString());

        JsonPath after = read(id);
        assertEquals(before.getString("revision"), after.getString("revision"), "a refused PATCH must not move the revision");
        assertEquals(before.getString("validUntil"), after.getString("validUntil"));
        assertEquals(before.getMap("bill"), after.getMap("bill"));
        assertEquals(before.getList("paymentMethods"), after.getList("paymentMethods"));
    }

    private static void assertApplied(MockMvcResponse response) {
        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
    }

    /**
     * <b>X9-PATCH-050</b> — a PATCH whose merged tip is allowed with neither a range nor presets is
     * refused in the create's words, and nothing moves; one that keeps the stored presets is an
     * offer, and is applied.
     *
     * <p><b>Source:</b> Ours. ADR-0017, INTERPRETATION I-10; held on PATCH by the owner on
     * 2026-11-15.
     *
     * <p><b>Why:</b> A create refused an empty tip offer while a PATCH could write one, and the
     * payer's app was then told tips are welcome with nothing to choose from.
     */
    @Test
    void theMergedTipIsHeldToTheCreatesRule() {
        String id = create(FAR, bill(""), method(FAR));
        assertRefusedUnchanged(id, "{ \"bill\": %s, \"paymentMethods\": [ %s ] }"
                .formatted(patchBill("\"tip\": { \"allowed\": true },"), method(FAR)),
            "tip.allow: should inform range or presets when tip is allowed");

        String withPresets = create(FAR, bill("\"tip\": { \"allowed\": true, \"presets\": [10, 15] },"), method(FAR));
        assertApplied(patch(withPresets, "{ \"bill\": %s, \"paymentMethods\": [ %s ] }"
            .formatted(patchBill("\"tip\": { \"allowed\": true },"), method(FAR))));
        assertEquals(List.of(10, 15), read(withPresets).getList("bill.tip.presets"));
    }

    /**
     * <b>X9-PATCH-058</b> — a PATCH that refuses tips and still sends a range or presets is
     * refused in the create's words, and nothing moves; {@code allowed: false} alone, or with
     * {@code presets: null}, turns a stored offer off, its range and presets going with it.
     *
     * <p><b>Source:</b> Ours. ADR-0017, INTERPRETATION I-10; the merged tip is held to the
     * create's rule by the owner's decision of 2026-11-15.
     *
     * <p><b>Why:</b> A create refused a tip not allowed that carries a range, while a PATCH
     * answered 200 and silently dropped what the caller sent.
     */
    @Test
    void aPatchThatRefusesTipsCarriesNoOffer() {
        String offer = "\"tip\": { \"allowed\": true, \"range\": { \"min\": 5, \"max\": 25 }, \"presets\": [10, 15] },";
        String words = "tip.allow: cannot provided range or presets when tip is not allowed";
        String body = "{ \"bill\": %s, \"paymentMethods\": [ %s ] }";

        for (String stored : List.of("", offer)) {
            String id = create(FAR, bill(stored), method(FAR));

            for (String tip : List.of(
                "{ \"allowed\": false, \"range\": { \"min\": 5, \"max\": 25 } }",
                "{ \"allowed\": false, \"presets\": [10, 15] }",
                "{ \"allowed\": false, \"range\": { \"min\": 5, \"max\": 25 }, \"presets\": [10, 15] }")) {
                assertRefusedUnchanged(id, body.formatted(patchBill("\"tip\": %s,".formatted(tip)), method(FAR)), words);
            }
        }

        for (String off : List.of("{ \"allowed\": false }", "{ \"allowed\": false, \"presets\": null }")) {
            String id = create(FAR, bill(offer), method(FAR));

            assertApplied(patch(id, body.formatted(patchBill("\"tip\": %s,".formatted(off)), method(FAR))));
            assertEquals(Map.of("allowed", false), read(id).getMap("bill.tip"));
        }
    }

    /**
     * <b>X9-PATCH-051</b> — a date the PATCH sets already past on the payee's clock is refused in
     * the create's words (the due date, a discount's edge, the QR Code's {@code validUntil}), and
     * nothing moves; set to exactly now, it is accepted.
     *
     * <p><b>Source:</b> Ours. The create's rules, held on what a PATCH changes by the owner on
     * 2026-11-15.
     *
     * <p><b>Why:</b> A create refuses a QR Code that is born expired, overdue or with a discount
     * already over; a PATCH could write each of them.
     */
    @Test
    void aDateThePatchSetsInThePastIsRefused() {
        OffsetDateTime start = now();
        String due = utc(start.plusHours(1));
        String methodEnds = utc(start.plusHours(1));
        String id = create(utc(start.plusHours(2)), bill(invoice(due)), method(methodEnds));
        String stored = method(methodEnds);

        BusinessClock.setAt(start.toInstant());
        assertRefusedUnchanged(id, "{ \"bill\": %s, \"paymentMethods\": [ %s ] }"
                .formatted(patchBill(invoice(utc(start.minusSeconds(1)))), stored),
            "bill.invoice.dueDate: must be after or equal to actual date.");
        assertRefusedUnchanged(id, "{ \"bill\": %s, \"paymentMethods\": [ %s ] }".formatted(patchBill(invoice(due), """
                { "amount": 1000, "adjustments": { "formula": "FixedDiscountLateFeeLinearInterest", "parameters": {
                  "discounts": [ { "daysBefore": 1, "discount": 10, "explanation": "d" } ],
                  "lateFees": { "fixed": 0, "perDay": 0, "explanation": "none" } } } }"""), stored),
            "bill.amountDue.adjustments.discounts[0]: target date must be after the current date.");

        // Ninety minutes on, the method has lapsed but the QR Code has not.
        OffsetDateTime later = start.plusMinutes(90);
        BusinessClock.setAt(later.toInstant());
        assertRefusedUnchanged(id, "{ \"validUntil\": \"%s\", \"paymentMethods\": [ %s ] }"
                .formatted(utc(later.minusSeconds(1)), stored),
            "validUntil: must not be less than the current date.");
        BusinessClock.setAt(later.toInstant());
        assertApplied(patch(id, "{ \"validUntil\": \"%s\", \"paymentMethods\": [ %s ] }".formatted(utc(later.plusSeconds(5)), stored)));
    }

    /**
     * <b>X9-PATCH-052</b> — a date the PATCH leaves as it was is not judged again: an expired QR
     * Code whose bill is overdue is extended, the bill restated with its past due date.
     *
     * <p><b>Source:</b> Ours. Owner decision 2026-11-15.
     *
     * <p><b>Why:</b> Holding every stored date to now would make an expired QR Code impossible to
     * extend, which is the edit a biller most needs once it has lapsed.
     */
    @Test
    void anUntouchedPastDateIsNotJudgedAgain() {
        OffsetDateTime start = now();
        String due = utc(start.plusMinutes(30));
        String id = create(utc(start.plusHours(1)), bill(invoice(due)), method(utc(start.plusHours(1))));

        OffsetDateTime later = start.plusHours(3);
        BusinessClock.setAt(later.toInstant());
        assertApplied(patch(id, "{ \"validUntil\": \"%s\", \"bill\": %s, \"paymentMethods\": [ %s ] }"
            .formatted(utc(later.plusDays(1)), patchBill(invoice(due)), method(utc(later.plusDays(1))))));
        assertEquals(utc(later.plusDays(1)), Instant.parse(read(id).getString("validUntil")).toString());
    }

    /**
     * <b>X9-PATCH-053</b> — a PATCH that moves {@code validUntil} before the end of a running
     * reservation cuts it back: past the new {@code validUntil} the QR Code reads ACTIVE, not for
     * the rest of the window.
     *
     * <p><b>Source:</b> Ours. ADR-0019 ("a reservation cannot outlive the thing reserved"); held on
     * PATCH by the owner on 2026-11-15.
     *
     * <p><b>Why:</b> The window was capped at creation's {@code validUntil}; moved earlier, the QR
     * Code read PAYMENT_INITIATED after its own expiry.
     */
    @Test
    void anEarlierValidUntilCutsTheReservationBack() {
        OffsetDateTime start = now();
        BusinessClock.setAt(start.toInstant());
        String id = create(FAR, bill(""), method(FAR));
        given().contentType("application/json").body("{\"status\":\"PAYMENT_INITIATED\"}")
            .when().put(PR + "/" + id + "/status-update").then().statusCode(HttpStatus.OK.value());

        OffsetDateTime cut = start.plusSeconds(30);
        assertApplied(patch(id, "{ \"validUntil\": \"%s\", \"paymentMethods\": [ %s ] }".formatted(utc(cut), method(utc(cut)))));
        assertEquals("PAYMENT_INITIATED", read(id).getString("status"));

        BusinessClock.setAt(cut.plusSeconds(1).toInstant());
        assertEquals("ACTIVE", read(id).getString("status"), "the reservation ends at the new validUntil, not 90 s after it began");
    }

    /**
     * <b>X9-PATCH-054</b> — an order, an invoice with its invoicee, and adjustments sent to a bill
     * that had none are applied.
     *
     * <p><b>Source:</b> Ours. Story 9.7 found Java dropping them with a 200; the owner chose Go's
     * answer (2026-11-15).
     *
     * <p><b>Why:</b> A 200 for a change that went nowhere is the silent drop ADR-0018 removed for
     * currencies: the biller believes the invoice is on the QR Code and the payer never sees it.
     */
    @Test
    void whatIsSentToABillThatHadNoneIsApplied() {
        String id = create(FAR, bill(""), method(FAR));
        assertApplied(patch(id, "{ \"bill\": %s, \"paymentMethods\": [ %s ] }".formatted(patchBill("""
                "order": { "number": "ORD-1" },
                "invoice": { "number": "INV-1", "date": "2030-01-01", "dueDate": "2030-12-30T23:59:59Z",
                             "invoicee": { "name": "Ann" } },""", """
                { "amount": 1000, "adjustments": { "formula": "FixedDiscountLateFeeLinearInterest",
                  "parameters": { "lateFees": { "fixed": 1, "perDay": 1, "explanation": "late" } } } }"""), method(FAR))));

        JsonPath after = read(id);
        assertEquals("ORD-1", after.getString("bill.order.number"));
        assertEquals("Ann", after.getString("bill.invoice.invoicee.name"));
        assertNotNull(after.get("bill.amountDue.adjustments.parameters.lateFees"));
    }

    /**
     * <b>X9-PATCH-055</b> — a PATCH that names a currency twice is refused naming it, and nothing
     * moves.
     *
     * <p><b>Source:</b> Ours. Story 9.7 found Java keeping the last in silence; the owner chose Go's
     * answer, in the create's words (2026-11-15).
     *
     * <p><b>Why:</b> With two amounts for one currency the caller cannot know which the QR Code now
     * asks for.
     */
    @Test
    void aCurrencyNamedTwiceIsRefused() {
        String id = create(FAR, bill(""), method(FAR));
        assertRefusedUnchanged(id, "{ \"paymentMethods\": [ %s, %s ] }".formatted(method(FAR), method(FAR).replace("1000", "800")),
            "paymentMethods: must not contain duplicated currency: USDC");
    }

    /**
     * <b>X9-PATCH-056</b> — the nulls that ended in a 500: {@code bill: null} is refused by name;
     * {@code editable: null} removes {@code editable}; {@code editable: {}} on a method that had
     * none is the create's refusal.
     *
     * <p><b>Source:</b> Ours. Story 9.7; the owner chose Go's answers (2026-11-15).
     *
     * <p><b>Why:</b> A 500 tells the caller nothing about what to fix, and is counted against the
     * service rather than the request.
     */
    @Test
    void theNullsThatEndedInA500AreAnswered() {
        String id = create(FAR, bill(""), method(FAR));
        assertRefusedUnchanged(id, "{ \"bill\": null, \"paymentMethods\": [ %s ] }".formatted(method(FAR).replace("1000", "800")),
            "bill: must not be updated with null.");
        assertRefusedUnchanged(id, "{ \"paymentMethods\": [ %s ] }".formatted(method(FAR, "\"editable\": {},")),
            "Editable amount range is required when the amount is editable.");

        String editable = create(FAR, bill(""), method(FAR, "\"editable\": { \"range\": { \"min\": 1, \"max\": 2000 } },"));
        assertApplied(patch(editable, "{ \"paymentMethods\": [ %s ] }".formatted(method(FAR, "\"editable\": null,"))));
        assertNull(read(editable).get("paymentMethods[0].editable"));
    }

    /**
     * <b>X9-TIME-022</b> — the GET answer's {@code sentAt} is the instant it is generated, on the
     * business clock, but never earlier than {@code revisedAt}.
     *
     * <p><b>Source:</b> X9.150 §6: {@code sentAt} SHALL be greater than or equal to
     * {@code revisedAt}. The instant is ours (INTERPRETATION I-17, owner 2026-11-15).
     *
     * <p><b>Why:</b> A clock behind the last revision would otherwise answer a {@code sentAt} the
     * standard forbids.
     */
    @Test
    void theGetAnswersSentAtIsNeverBeforeRevisedAt() {
        String id = create(FAR, bill(""), method(FAR));
        JsonPath read = read(id);
        Instant revisedAt = Instant.parse(read.getString("revisedAt"));
        assertFalse(Instant.parse(read.getString("sentAt")).isBefore(revisedAt));

        BusinessClock.setAt(revisedAt.minus(1, ChronoUnit.HOURS));
        assertEquals(revisedAt, Instant.parse(read(id).getString("sentAt")), "a clock behind revisedAt: sentAt is revisedAt");

        Instant ahead = revisedAt.plus(1, ChronoUnit.HOURS);
        BusinessClock.setAt(ahead);
        Instant sentAt = Instant.parse(read(id).getString("sentAt"));
        assertFalse(sentAt.isBefore(ahead), "a clock ahead: sentAt is now on the business clock, " + sentAt);
    }

    /**
     * <b>X9-TIME-023</b> — the signed payload's {@code sentAt} is never earlier than its
     * {@code revisedAt}.
     *
     * <p><b>Source:</b> X9.150 §6. INTERPRETATION I-17.
     *
     * <p><b>Why:</b> The payload is what a payer's PSP receives and verifies; it is where the
     * standard's ordering of the two instants matters most.
     */
    @Test
    void thePayloadsSentAtIsNeverBeforeRevisedAt() throws Exception {
        String created = given().contentType("application/json").body("""
                {
                  "validUntil": "%s",
                  "creditor": { "name": "Acme", "phone": "+15552223333", "email": "billing@example.com",
                    "address": { "line1": "1 A St", "city": "Los Angeles", "state": "CA", "postalCode": "90012", "country": "US" },
                    "MCC": "4900" },
                  "bill": %s,
                  "paymentNotification": { "kind": "DEFAULT" },
                  "paymentMethods": [ %s ]
                }
                """.formatted(FAR, bill(""), method(FAR)))
            .when().post(PR).then().statusCode(HttpStatus.CREATED.value()).extract().body().asString();
        JsonPath qr = JsonPath.from(created);
        Instant revisedAt = Instant.parse(read(qr.getString("id")).getString("revisedAt"));

        BusinessClock.setAt(revisedAt.minus(1, ChronoUnit.MINUTES));
        String signed = given().contentType("application/json")
            .header("Correlation-Id", UUID.randomUUID().toString()).header("TTL-Seconds", "300")
            .body("{\"qrCodeContent\": \"%s\"}".formatted(qr.getString("qrCodeB64")))
            .when().post("/api/v1/signature/generate").then().statusCode(HttpStatus.OK.value()).extract().body().asString();
        MockMvcResponse response = given().contentType("application/jose").body(signed)
            .when().post("/pub/api/v1/loc/" + qr.getString("location.id"));
        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());

        JsonNode payload = objectMapper.readTree(JWSObject.parse(response.asString()).getPayload().toString());
        assertEquals(revisedAt, Instant.parse(payload.at("/sentAt").asText()));
        assertEquals(revisedAt, Instant.parse(payload.at("/revisedAt").asText()));
    }
}
