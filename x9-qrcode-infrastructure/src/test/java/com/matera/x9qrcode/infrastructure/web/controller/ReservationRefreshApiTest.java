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
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A repeated announcement moves the reservation's window, rather than merely being tolerated.
 *
 * <p>This is the half of ADR-0021 that an "it returned 200" test cannot see. Accepting the repeat
 * and ignoring it would pass every status assertion in the black-box suite and still leave the
 * payer's reservation expiring on the original clock — so a payer who retried every minute for ten
 * minutes would lose the bill anyway, at a moment of this service's choosing rather than theirs.
 *
 * <p><b>The window is shortened to ten seconds for this test</b>, because the shipped ninety would
 * mean a three-minute test. Ten is not a guess at how slow a runner gets: with two sleeps of six
 * seconds, the test only misreports if the HTTP calls between them take more than four seconds,
 * which is no longer scheduling noise but a problem worth a red build. The related flake in
 * {@code PaymentNotificationAcceptanceApiTest} is the same hazard read from the other side —
 * there the setup had to finish inside the window, here the calls do.
 */
@TestPropertySource(properties = "x9.reservation.ttl-seconds=10")
class ReservationRefreshApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String NOTIFY = "/pub/api/v1/payment-notification";
    private static final String APPLICATION_JOSE = "application/jose";

    private static final String PAYER = "9acc5ba9-ad22-3914-a268-631e367f74d7";
    private static final long AMOUNT = 1000L;

    /** Six seconds: comfortably inside a ten-second window, and two of them comfortably outside. */
    private static final long HALF_A_WINDOW_MILLIS = 6_000L;

    private static final String CREATE_BODY = """
        {
          "validUntil": "2030-12-31T23:59:59Z",
          "creditor": {
            "name": "Reservation Refresh Test",
            "phone": "+14155550100",
            "email": "test@example.com",
            "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
            "MCC": "5999"
          },
          "bill": { "description": "refresh", "amountDue": { "amount": 1000, "currency": "USD" } },
          "paymentNotification": { "kind": "DEFAULT" },
          "paymentMethods": [
            { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1000,
              "networks": { "ach": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" } } }
          ]
        }
        """;

    private String createQRCode() {
        return given().contentType("application/json").body(CREATE_BODY)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");
    }

    private static String announcement(String qrCodeId, String payerInfo) {
        return """
            {
              "payment": { "qrcodeId": "%s", "amount": %d, "currency": "USD", "network": "ACH",
                           "transactionId": "021000021.0000001" },
              "payer": { "info": "%s" },
              "expectedDate": "2030-10-08T06:59:59Z"
            }
            """.formatted(qrCodeId, AMOUNT, payerInfo);
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

    private MockMvcResponse announce(String qrCodeId, String payerInfo) {
        return given().contentType(APPLICATION_JOSE)
                .body(sign(announcement(qrCodeId, payerInfo)))
                .when().post(NOTIFY);
    }

    private String statusOf(String qrCodeId) {
        return given().when().get(CREATE + "/" + qrCodeId)
                .then().statusCode(HttpStatus.OK.value())
                .extract().path("status");
    }

    /**
     * <b>X9-HOLD-030</b> — a repeated announcement moves the window.
     *
     * <p><b>Source:</b> Ours. ADR-0021.
     *
     * <p><b>Why:</b> Accepting a repeat is only half the rule. A payer retrying because their first
     * request timed out is still holding the bill, and the retry is the evidence of that — so the
     * clock has to start again from it. A repeat that was accepted and then ignored would expire
     * the reservation on the original schedule, which is the same failure as refusing the retry,
     * arriving later and harder to see.
     */
    @Test
    void aRepeatedAnnouncementMovesTheWindow() throws InterruptedException {
        String qrCodeId = createQRCode();

        assertEquals(HttpStatus.OK.value(), announce(qrCodeId, PAYER).statusCode());

        Thread.sleep(HALF_A_WINDOW_MILLIS);

        MockMvcResponse repeat = announce(qrCodeId, PAYER);
        assertEquals(HttpStatus.OK.value(), repeat.statusCode(), repeat.asString());

        Thread.sleep(HALF_A_WINDOW_MILLIS);

        // Past the ORIGINAL window, inside the refreshed one.
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId),
                "the repeat must restart the clock, not merely be tolerated");
    }

    /**
     * <b>X9-HOLD-031</b> — without a repeat, the same wait releases it.
     *
     * <p><b>Source:</b> Ours. ADR-0019 and ADR-0021.
     *
     * <p><b>Why:</b> The negative control, and this pair is worth more than either half. Without
     * it, X9-HOLD-030 passes against a build whose reservations never expire at all — which is the
     * very thing ADR-0019 was written to stop, and it would look like a feature working.
     */
    @Test
    void withoutARepeatTheSameWaitReleasesIt() throws InterruptedException {
        String qrCodeId = createQRCode();

        assertEquals(HttpStatus.OK.value(), announce(qrCodeId, PAYER).statusCode());

        Thread.sleep(HALF_A_WINDOW_MILLIS * 2);

        assertEquals("ACTIVE", statusOf(qrCodeId),
                "a reservation nobody renewed must lapse on its own");
    }

}
