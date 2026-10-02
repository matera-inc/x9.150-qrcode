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

import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Where a notification is dispatched to, and what happens to the QR Code when it lands.
 *
 * <p>The dispatch switch used to be a switch <em>statement</em> listing a handful of networks with
 * no {@code default}, so a notification on a rail it had forgotten fell straight through — never
 * validated, never applied, answered <b>200 OK</b> for a payment it ignored. It is a switch
 * expression over {@code NetworkEnum} now, so an unclassified rail fails to compile rather than
 * failing in production; these tests pin the runtime half of that.
 *
 * <p>Note the assertions check the resulting STATUS, not just the response code. A bare 200 check
 * would not have caught the original bug, because the original bug returned 200.
 *
 * <p>The three rails do not all mean the same thing. ACH announces a debit the payer has not yet
 * originated, so it goes through the acceptance gate and takes the QR Code out of circulation.
 * FedNow and RTP carry the QR Code id inside the ISO 20022 payment message and reconcile from it, so
 * a notification there is a courtesy: recorded, status untouched.
 */
class PaymentNotificationDispatchApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String NOTIFY = "/pub/api/v1/payment-notification";
    private static final String APPLICATION_JOSE = "application/jose";

    private static final long AMOUNT = 5_000L;

    private static String qrCodeOffering(String rail) {
        return """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Dispatch Test",
                "phone": "+14155550100",
                "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "dispatch test", "amountDue": { "amount": %d, "currency": "USD" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "%s": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" } } }
              ]
            }
            """.formatted(AMOUNT, AMOUNT, rail);
    }

    private static String notification(String qrCodeId, String network) {
        return """
            {
              "payment": {
                "qrcodeId": "%s",
                "amount": %d,
                "currency": "USD",
                "network": "%s",
                "transactionId": "021000021.0000001"
              },
              "payer": { "info": "Jane Payer, Springfield Savings" },
              "expectedDate": "2030-10-08T06:59:59Z"
            }
            """.formatted(qrCodeId, AMOUNT, network);
    }

    private String create(String body) {
        return given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");
    }

    private String sign(String payload) {
        return given().contentType("application/json")
                .header("Correlation-Id", UUID.randomUUID().toString())
                .header("TTL-Seconds", "300")
                .body(payload)
                .when().post("/api/v1/signature/generate")
                .then().statusCode(HttpStatus.OK.value())
                .extract().body().asString();
    }

    private MockMvcResponse notify(String qrCodeId, String network) {
        return given().contentType(APPLICATION_JOSE)
                .body(sign(notification(qrCodeId, network)))
                .when().post(NOTIFY);
    }

    private String statusOf(String qrCodeId) {
        return given().when().get(CREATE + "/" + qrCodeId)
                .then().statusCode(HttpStatus.OK.value())
                .extract().path("status");
    }

    /**
     * <b>X9-RAIL-100</b> — an ACH notification takes the QR Code out of circulation.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 A.11 — for ACH the payee's PSP should update status to initiated on receipt.
     * Conformance.
     *
     * <p><b>Why:</b> ACH origination is not settlement, so the QR Code is reserved rather than paid — exactly what
     * A.11 describes.
     */
    @Test
    void anACHNotificationTakesTheQRCodeOutOfCirculation() {
        String qrCodeId = create(qrCodeOffering("ach"));
        assertEquals("ACTIVE", statusOf(qrCodeId));

        MockMvcResponse response = notify(qrCodeId, "ACH");

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId),
                "an ACH notification announces a debit that has not happened yet, so it reserves the QR Code");
    }

    /**
     * <b>X9-RAIL-101</b> — a FedNow notification is recorded without settling.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 A.11 — on a successful payment the payer's PSP sends the notification.
     * Conformance; what the payee then does with it is ours, ADR-0014.
     *
     * <p><b>Why:</b> We transport and sequence; the consuming system reconciles. Settling automatically would mean
     * trusting a counterparty's claim about money we have not seen arrive.
     */
    @Test
    void aFedNowNotificationIsRecordedWithoutChangingStatus() {
        String qrCodeId = create(qrCodeOffering("fednow"));

        MockMvcResponse response = notify(qrCodeId, "FedNow");

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId),
                "FedNow reconciles from the ISO 20022 message, so the notification is a courtesy");
    }

    /**
     * <b>X9-RAIL-102</b> — a rail the QR Code does not offer is refused.
     *
     * <p><b>Source:</b> Conformance — the QR Code publishes what it accepts.
     *
     * <p><b>Why:</b> A payment by a route the biller never published details for did not pay this bill.
     */
    @Test
    void aRailTheQRCodeDoesNotOfferIsRefused() {
        String qrCodeId = create(qrCodeOffering("ach"));

        MockMvcResponse response = notify(qrCodeId, "FedNow");

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(),
                "a QR Code offering only ACH must not accept a FedNow payment: " + response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId), "a refused notification must change nothing");
    }

    /**
     * <b>X9-RAIL-103</b> — an uninterpreted network is rejected, not ignored.
     *
     * <p><b>Source:</b> Ours. ADR-0012 — and the deliberate asymmetry of I-9: ignoring is for DECODING someone
     * else's payload, not for accepting a payment against our own.
     *
     * <p><b>Why:</b> A rail added to the enum without a validation branch used to fall through unvalidated — the
     * same silent hole that once let Base, XRP and Arc be accepted and ignored. The switch is an
     * EXPRESSION now, so the next rail is a compile error instead.
     */
    @Test
    void anUninterpretedNetworkIsRejectedNotIgnored() {
        String qrCodeId = create(qrCodeOffering("ach"));

        MockMvcResponse response = notify(qrCodeId, "Ethereum");

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(),
                "Ethereum is not an interpreted rail: " + response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

}
