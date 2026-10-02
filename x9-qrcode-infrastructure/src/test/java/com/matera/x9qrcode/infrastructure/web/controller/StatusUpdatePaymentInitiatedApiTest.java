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
 * The `PAYMENT_INITIATED` case of the status-update endpoint.
 *
 * <p>`openapi.yaml` has always advertised the value while the code rejected it, so a caller
 * following the published contract got a 400. ANSI X9.150-2026 §A.9 asks the payee's PSP to make
 * this transition on initial network acceptance of a credit transfer request (pacs.008) "to prevent
 * duplicate payment", which is also why it must be refused — with a 409, not a 400 — once the QR
 * Code has moved on.
 */
class StatusUpdatePaymentInitiatedApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String STATUS_UPDATE = "/api/v1/payment-request/%s/status-update";

    // No locationId -> the server mints one, so these never collide with other tests' QR Codes.
    private static final String CREATE_BODY = """
        {
          "validUntil": "2030-12-31T23:59:59Z",
          "creditor": {
            "name": "Status Update Test",
            "phone": "+14155550100",
            "email": "test@example.com",
            "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
            "MCC": "5999"
          },
          "bill": { "description": "status update test", "amountDue": { "amount": 1000, "currency": "USD" } },
          "paymentNotification": { "kind": "DEFAULT" },
          "paymentMethods": [
            { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
              "networks": { "fednow": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" } } }
          ]
        }
        """;

    private String createActiveQRCode() {
        return given().contentType("application/json").body(CREATE_BODY)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");
    }

    private MockMvcResponse updateStatus(String qrCodeId, String body) {
        return given().contentType("application/json").body(body)
                .when().put(STATUS_UPDATE.formatted(qrCodeId));
    }

    private MockMvcResponse initiatePayment(String qrCodeId) {
        return updateStatus(qrCodeId, "{\"status\": \"PAYMENT_INITIATED\"}");
    }

    /**
     * <b>X9-LIFE-050</b> — a payment can be initiated on an ACTIVE QR Code over HTTP.
     *
     * <p><b>Source:</b> Ours. ADR-0002.
     *
     * <p><b>Why:</b> The acceptance case for the transition through the API.
     */
    @Test
    void shouldInitiatePaymentOnAnActiveQRCode() {
        String qrCodeId = createActiveQRCode();

        MockMvcResponse response = initiatePayment(qrCodeId);

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("PAYMENT_INITIATED", response.jsonPath().getString("status"));
    }

    /**
     * <b>X9-LIFE-051</b> — the reservation is visible on subsequent reads.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> A reservation nobody can observe cannot be acted on. The biller's system polls this to learn
     * somebody is paying.
     */
    @Test
    void shouldReportPaymentInitiatedOnSubsequentReads() {
        String qrCodeId = createActiveQRCode();
        initiatePayment(qrCodeId);

        given().when().get(CREATE + "/" + qrCodeId)
                .then().statusCode(HttpStatus.OK.value())
                .body("status", org.hamcrest.Matchers.equalTo("PAYMENT_INITIATED"));
    }

    /**
     * <b>X9-LIFE-052</b> — a second initiation is a conflict.
     *
     * <p><b>Source:</b> Ours. ADR-0002.
     *
     * <p><b>Why:</b> 409 rather than 400: the request is well-formed, the state is what refuses it.
     */
    @Test
    void shouldRejectWithConflictWhenPaymentIsAlreadyInitiated() {
        String qrCodeId = createActiveQRCode();
        initiatePayment(qrCodeId);

        MockMvcResponse response = initiatePayment(qrCodeId);

        // The contract advertises 409 for this case; it used to be an undifferentiated 400.
        assertEquals(HttpStatus.CONFLICT.value(), response.statusCode(), response.asString());
        assertEquals("PAYMENT_INITIATED", response.jsonPath().getString("currentStatus"));
    }

    /**
     * <b>X9-LIFE-053</b> — an already-paid QR Code cannot be initiated.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §9. Conformance.
     *
     * <p><b>Why:</b> No second payment against a settled bill.
     */
    @Test
    void shouldRejectWithConflictWhenQRCodeIsAlreadyPaid() {
        String qrCodeId = createActiveQRCode();
        updateStatus(qrCodeId, """
            {"status": "PAID", "endToEndId": "XYZ.USBK.X9aTf72qLm.1", "network": "FedNow"}
            """).then().statusCode(HttpStatus.OK.value());

        MockMvcResponse response = initiatePayment(qrCodeId);

        assertEquals(HttpStatus.CONFLICT.value(), response.statusCode(), response.asString());
        assertEquals("PAID", response.jsonPath().getString("currentStatus"));
    }

    /**
     * <b>X9-LIFE-054</b> — a cancelled QR Code cannot be initiated.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §9. Conformance.
     *
     * <p><b>Why:</b> A withdrawn bill accepts no new payers.
     */
    @Test
    void shouldRejectWithConflictWhenQRCodeIsCancelled() {
        String qrCodeId = createActiveQRCode();
        updateStatus(qrCodeId, "{\"status\": \"CANCELLED\"}").then().statusCode(HttpStatus.OK.value());

        MockMvcResponse response = initiatePayment(qrCodeId);

        assertEquals(HttpStatus.CONFLICT.value(), response.statusCode(), response.asString());
        assertEquals("CANCELLED", response.jsonPath().getString("currentStatus"));
    }

    /**
     * <b>X9-LIFE-055</b> — a reserved QR Code can be reactivated.
     *
     * <p><b>Source:</b> Ours. ADR-0002 — the reservation must be releasable.
     *
     * <p><b>Why:</b> A payer who abandons the payment would otherwise strand the QR Code forever. Reactivation is
     * how the biller takes it back, and without it the reservation is a one-way door.
     */
    @Test
    void shouldStillAllowReactivationAfterPaymentIsInitiated() {
        String qrCodeId = createActiveQRCode();
        initiatePayment(qrCodeId);

        MockMvcResponse response = updateStatus(qrCodeId, "{\"status\": \"ACTIVE\"}");

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("ACTIVE", response.jsonPath().getString("status"));
    }

    /**
     * <b>X9-LIFE-056</b> — a pre-payment may not carry settlement details.
     *
     * <p><b>Source:</b> Ours. ADR-0002.
     *
     * <p><b>Why:</b> A transaction hash in a pre-payment claims money has already moved — the opposite of what the
     * phase means.
     */
    @Test
    void shouldRejectPaymentDetailsWhenInitiatingPayment() {
        String qrCodeId = createActiveQRCode();

        MockMvcResponse response = updateStatus(qrCodeId, """
            {"status": "PAYMENT_INITIATED", "endToEndId": "XYZ.USBK.X9aTf72qLm.1", "network": "FedNow"}
            """);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        assertTrue(response.asString().contains("paymentDetails"), response.asString());
    }

}
