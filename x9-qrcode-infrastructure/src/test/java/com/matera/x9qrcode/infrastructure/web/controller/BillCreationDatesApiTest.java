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

import java.time.LocalDate;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dates on a bill that record when something happened, as opposed to when payment is owed.
 *
 * <p>{@code bill.invoice.date} is "the date the bill was issued" and {@code bill.order.date} is "the
 * date the order was created" — the contract's own words. Both are historical by nature: a bill
 * issued yesterday, for an order raised last week, is the ordinary case. Only
 * {@code bill.invoice.dueDate} is a future date, and only it says so.
 *
 * <p>The validator used to force all three forward. That rejected every truthful invoice, and made
 * {@code order} unusable unless the caller invented a date — which would put a false date on a
 * customer's signed QR Code. Worse, omitting {@code order.date}, which the contract explicitly
 * permits, produced an unhandled {@code NullPointerException} and a bare 500.
 *
 * <p>Found by an adopter integrating against a running deployment, not by this suite: every fixture
 * here happened to use future dates.
 */
class BillCreationDatesApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";

    /** @param orderFragment a JSON fragment for {@code bill.order}, or empty for no order at all. */
    private MockMvcResponse create(String invoiceDate, String orderFragment) {
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
                "description": "creation dates",
                %s
                "invoice": { "number": "INV-1", "date": "%s", "dueDate": "2030-12-31T23:59:59Z" },
                "amountDue": { "amount": 1000, "currency": "USDC" }
              },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
                  "networks": { "solana": { "recipient": "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM" } } }
              ]
            }
            """.formatted(orderFragment, invoiceDate);

        return given().contentType("application/json").body(body).when().post(CREATE);
    }

    private static String yesterday() {
        return LocalDate.now().minusDays(1).toString();
    }

    // ------------------------------------------------------------------ order.date is optional

    /**
     * The defect as reported: the contract requires only {@code number}, so a caller following the
     * schema sends exactly that — and got a 500 with no problem detail and nothing logged.
     */
    @Test
    void anOrderWithoutADateIsAcceptedRatherThanCrashing() {
        MockMvcResponse response = create("2030-01-10", "\"order\": { \"number\": \"ORD-1\" },");

        assertEquals(HttpStatus.CREATED.value(), response.statusCode(),
                "order.date is optional in the contract; omitting it must not be a 500: "
                    + response.asString());
    }

    // ------------------------------------------------- dates in the past are the ordinary case

    @Test
    void anOrderRaisedInThePastIsAccepted() {
        MockMvcResponse response = create("2030-01-10",
                "\"order\": { \"number\": \"ORD-1\", \"date\": \"%s\" },".formatted(yesterday()));

        assertEquals(HttpStatus.CREATED.value(), response.statusCode(),
                "an order is created BEFORE the bill for it: " + response.asString());
    }

    @Test
    void aBillIssuedInThePastIsAccepted() {
        MockMvcResponse response = create(yesterday(), "");

        assertEquals(HttpStatus.CREATED.value(), response.statusCode(),
                "invoice.date is when the bill was issued, which is not in the future: "
                    + response.asString());
    }

    @Test
    void aBillIssuedLongAgoIsStillAccepted() {
        MockMvcResponse response = create("2020-03-01",
                "\"order\": { \"number\": \"ORD-1\", \"date\": \"2020-02-25\" },");

        assertEquals(HttpStatus.CREATED.value(), response.statusCode(), response.asString());
    }

    // --------------------------------------------------- what must still be in the future

    /** The one date that genuinely has to be ahead of us, and the only one the contract says so. */
    @Test
    void aDueDateInThePastIsStillRefused() {
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
                "description": "past due date",
                "invoice": { "number": "INV-1", "date": "2020-01-10", "dueDate": "2020-12-31T23:59:59Z" },
                "amountDue": { "amount": 1000, "currency": "USDC" }
              },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
                  "networks": { "solana": { "recipient": "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM" } } }
              ]
            }
            """;

        MockMvcResponse response = given().contentType("application/json").body(body)
                .when().post(CREATE);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        assertTrue(response.asString().contains("dueDate"),
                "a bill already overdue cannot be presented for payment: " + response.asString());
    }

}
