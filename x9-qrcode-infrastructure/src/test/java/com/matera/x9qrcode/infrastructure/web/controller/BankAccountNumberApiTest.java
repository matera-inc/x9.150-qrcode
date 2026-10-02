/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;

/**
 * What a bank account number may contain.
 *
 * <p>ANSI X9.150-2026 Table 2 defines <i>Account Number (Bank)</i> as 4–17 characters that
 * "SHALL consist only of digits (0–9) AND/OR letters (A–Z, a–z)" — alphanumeric, explicitly.
 *
 * <p>This contract had it as digits-only, which rejected conformant account numbers. It was found
 * by trying to run the standard's own Annex A example, whose RTP account is
 * {@code ACME00112233445}: a payload the standard publishes as correct, refused by our schema.
 * That is the kind of defect that only surfaces when the examples in a document are executed rather
 * than read, so the fixtures below are exactly the standard's.
 */
class BankAccountNumberApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";

    private static String body(String accountNumber) {
        return """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Account Number Test",
                "phone": "+14155550100",
                "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "account number", "amountDue": { "amount": 11845, "currency": "USD" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 11845,
                  "networks": { "rtp": { "routingNumber": "026009593", "accountNumber": "%s", "protectionType": "tokenized" } } }
              ]
            }
            """.formatted(accountNumber);
    }

    /**
     * <b>X9-RAIL-080</b> — an alphanumeric bank account number is accepted.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 Table 2 permits (0-9) AND/OR (A-Z, a-z), and Nacha's ACH Entry Detail Record
     * defines DFI Account Number the same way. Conformance — INTERPRETATION I-6.
     *
     * <p><b>Why:</b> This document found a real bug: the field had been implemented as digits-only, which refuses
     * legitimate US account numbers. Both governing documents agree it is alphanumeric.
     */
    @ParameterizedTest(name = "accountNumber {0} is accepted")
    @ValueSource(strings = {"ACME00112233445", "9876543210", "12345678987654321", "abcd", "A1b2C3"})
    void anAlphanumericAccountNumberIsAccepted(String accountNumber) {
        given().contentType("application/json").body(body(accountNumber))
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value());
    }

    /**
     * <b>X9-RAIL-081</b> — anything outside digits and letters is refused.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 Table 2. Conformance.
     *
     * <p><b>Why:</b> The limit of I-6. Alphanumeric is wider than digits, not unbounded — punctuation and spaces
     * are still refused.
     */
    @ParameterizedTest(name = "accountNumber {0} is refused")
    @ValueSource(strings = {"123", "123456789012345678", "ACME-0011", "ACME 0011", "ACME_0011", "conta+1"})
    void anythingOutsideDigitsAndLettersIsRefused(String accountNumber) {
        given().contentType("application/json").body(body(accountNumber))
                .when().post(CREATE)
                .then().statusCode(HttpStatus.BAD_REQUEST.value());
    }

}
