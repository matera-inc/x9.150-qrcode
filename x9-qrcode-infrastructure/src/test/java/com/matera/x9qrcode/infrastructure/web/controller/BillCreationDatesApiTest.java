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
     * <b>X9-AMT-080</b> — an order without a date is accepted rather than crashing.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.3 — order requires only `number`; `date` is optional. Conformance.
     *
     * <p><b>Why:</b> A caller following the schema sent {"number": "..."} and got a 500 — Spring's default error
     * page, not our problem+json, with nothing logged at ERROR. An optional field that crashes the
     * service when omitted is the schema lying.
     */
    @Test
    void anOrderWithoutADateIsAcceptedRatherThanCrashing() {
        MockMvcResponse response = create("2030-01-10", "\"order\": { \"number\": \"ORD-1\" },");

        assertEquals(HttpStatus.CREATED.value(), response.statusCode(),
                "order.date is optional in the contract; omitting it must not be a 500: "
                    + response.asString());
    }

    // ------------------------------------------------- dates in the past are the ordinary case

    /**
     * <b>X9-AMT-081</b> — an order raised in the past is accepted.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.3 places no lower bound on an order date. Conformance.
     *
     * <p><b>Why:</b> An order date is BY NATURE in the past: the bill is for something ordered last Tuesday. A
     * >= today rule meant for a due date had been applied to it, so the only accepted value was one
     * that could not be true.
     */
    @Test
    void anOrderRaisedInThePastIsAccepted() {
        MockMvcResponse response = create("2030-01-10",
                "\"order\": { \"number\": \"ORD-1\", \"date\": \"%s\" },".formatted(yesterday()));

        assertEquals(HttpStatus.CREATED.value(), response.statusCode(),
                "an order is created BEFORE the bill for it: " + response.asString());
    }

    /**
     * <b>X9-AMT-082</b> — an invoice issued in the past is accepted.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.4 — the issue date. Conformance.
     *
     * <p><b>Why:</b> Same shape as AMT-081 for the invoice: an invoice is issued before it is paid.
     */
    @Test
    void aBillIssuedInThePastIsAccepted() {
        MockMvcResponse response = create(yesterday(), "");

        assertEquals(HttpStatus.CREATED.value(), response.statusCode(),
                "invoice.date is when the bill was issued, which is not in the future: "
                    + response.asString());
    }

    /**
     * <b>X9-AMT-083</b> — an invoice issued long ago is still accepted.
     *
     * <p><b>Source:</b> Conformance.
     *
     * <p><b>Why:</b> No arbitrary horizon. A bill chased months later is a normal thing, and inventing a cutoff
     * would refuse real debts for no reason the standard gives.
     */
    @Test
    void aBillIssuedLongAgoIsStillAccepted() {
        MockMvcResponse response = create("2020-03-01",
                "\"order\": { \"number\": \"ORD-1\", \"date\": \"2020-02-25\" },");

        assertEquals(HttpStatus.CREATED.value(), response.statusCode(), response.asString());
    }

    // --------------------------------------------------- what must still be in the future

    /**
     * <b>X9-AMT-084</b> — a due date in the past is still refused.
     *
     * <p><b>Source:</b> Ours — the rule the past-date rules above were wrongly borrowed from.
     *
     * <p><b>Why:</b> The boundary. Relaxing issue dates must not relax the DUE date: minting a QR Code already
     * overdue means a late fee the payer could never have avoided.
     */
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
