/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import io.restassured.module.mockmvc.response.MockMvcResponse;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-field date rules of creation hold on every PATCH, against the merged state:
 * {@code bill.invoice.dueDate ≤ validUntil} and every {@code paymentMethods[i].validUntil ≤ validUntil}.
 * Whichever side a PATCH moves, the pair is checked once every change is applied, and a refused PATCH
 * changes nothing, the revision included.
 */
class PatchCrossFieldDatesApiTest extends AbstractIntegrationTest {

    private static final String PR = "/api/v1/payment-request";
    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final String VALID_UNTIL = "2030-12-31T23:59:59Z";
    private static final String DUE = "2030-12-30T23:59:59Z";
    private static final String METHOD_UNTIL = "2030-06-30T00:00:00Z";

    private static String method(String validUntil) {
        return """
            { "currency": "USDC", "validUntil": "%s", "amount": 1000,
              "networks": { "solana": { "recipient": "%s" } } }
            """.formatted(validUntil, RECIPIENT);
    }

    private static String create(boolean withInvoice) {
        String invoice = withInvoice
            ? "\"invoice\": { \"number\": \"INV-1\", \"date\": \"2030-01-10\", \"dueDate\": \"%s\" },".formatted(DUE)
            : "";
        String body = """
            {
              "validUntil": "%s",
              "creditor": {
                "name": "Acme", "phone": "+15552223333", "email": "billing@example.com",
                "address": { "line1": "1 A St", "city": "Los Angeles", "state": "CA",
                             "postalCode": "90012", "country": "US" },
                "MCC": "4900"
              },
              "bill": { "description": "dates", %s "amountDue": { "amount": 1000, "currency": "USDC" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [ %s ]
            }
            """.formatted(VALID_UNTIL, invoice, method(METHOD_UNTIL));

        return given().contentType("application/json").body(body).when().post(PR)
            .then().statusCode(HttpStatus.CREATED.value()).extract().path("id");
    }

    private static MockMvcResponse patch(String id, String body) {
        return given().contentType("application/json").body(body).when().patch(PR + "/" + id);
    }

    private static JsonPath read(String id) {
        return given().when().get(PR + "/" + id).then().statusCode(HttpStatus.OK.value()).extract().jsonPath();
    }

    private static void assertRefusedNaming(MockMvcResponse response, String field) {
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        List<String> violations = response.jsonPath().getList("violations");
        assertTrue(violations.stream().anyMatch(v -> v.startsWith(field + ":")),
            "the refusal must name " + field + ": " + response.asString());
    }

    private static void assertUnchanged(JsonPath before, String id) {
        JsonPath after = read(id);
        assertEquals(before.getString("revision"), after.getString("revision"), "a refused PATCH must not move the revision");
        assertEquals(before.getString("validUntil"), after.getString("validUntil"));
        assertEquals(before.getString("paymentMethods[0].validUntil"), after.getString("paymentMethods[0].validUntil"));
        assertEquals(before.getString("qrCode"), after.getString("qrCode"));
    }

    /**
     * <b>X9-PATCH-040</b> — a PATCH that changes only {@code validUntil}, to before the bill's
     * {@code dueDate}, is refused with 400 naming {@code bill.invoice.dueDate}; nothing changes. Moved
     * to exactly {@code dueDate}, it is accepted (the rule is inclusive).
     *
     * <p><b>Source:</b> Ours. The create-time rule {@code dueDate ≤ validUntil} (X9-LIFE-421), held
     * on the merged state of every PATCH (owner decision 2026-11-14).
     *
     * <p><b>Why:</b> The check ran only when the bill was patched, so moving {@code validUntil} alone
     * left a QR Code that expires before its bill falls due: a state creation refuses, reached by edit.
     */
    @Test
    void aPatchOfValidUntilAloneIsHeldToTheDueDate() {
        String id = create(true);
        JsonPath before = read(id);

        assertRefusedNaming(
            patch(id, "{ \"validUntil\": \"2030-12-01T00:00:00Z\", \"paymentMethods\": [ %s ] }".formatted(method(METHOD_UNTIL))),
            "bill.invoice.dueDate");
        assertUnchanged(before, id);

        patch(id, "{ \"validUntil\": \"%s\", \"paymentMethods\": [ %s ] }".formatted(DUE, method(METHOD_UNTIL)))
            .then().statusCode(HttpStatus.OK.value());
        assertEquals(DUE, read(id).getString("validUntil"));
    }

    /**
     * <b>X9-PATCH-041</b> — a PATCH that moves {@code validUntil} before a payment method's, or a
     * method's {@code validUntil} after the QR Code's, is refused with 400 naming
     * {@code paymentMethods[0].validUntil}; nothing changes.
     *
     * <p><b>Source:</b> Ours. The create-time rule {@code paymentMethods[i].validUntil ≤ validUntil}
     * (X9-LIFE-422), held on the merged state of every PATCH.
     *
     * <p><b>Why:</b> A method outliving its QR Code is offered after the payload refuses to be
     * fetched. This already held on PATCH; pinned beside X9-PATCH-040 so both rules are seen to.
     */
    @Test
    void aPatchIsHeldToEveryMethodsValidUntil() {
        String id = create(false);
        JsonPath before = read(id);

        assertRefusedNaming(
            patch(id, "{ \"validUntil\": \"2030-06-01T00:00:00Z\", \"paymentMethods\": [ %s ] }".formatted(method(METHOD_UNTIL))),
            "paymentMethods[0].validUntil");
        assertUnchanged(before, id);

        assertRefusedNaming(
            patch(id, "{ \"paymentMethods\": [ %s ] }".formatted(method("2031-01-01T00:00:00Z"))),
            "paymentMethods[0].validUntil");
        assertUnchanged(before, id);
    }

}
