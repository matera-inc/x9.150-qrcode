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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acceptance gate for a pre-funds payment notification.
 *
 * <p>A notification that arrives before money moves is a request for permission, so the feature is
 * defined by what it refuses. Every case here asserts three things, not one: the call is refused,
 * the reason says why, and <b>the QR Code is untouched</b> — a gate that rejects but still mutates
 * state is the failure worth guarding against.
 */
class PaymentNotificationAcceptanceApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String NOTIFY = "/pub/api/v1/payment-notification";
    private static final String APPLICATION_JOSE = "application/jose";

    private static final String ROUTING_NUMBER = "021000021";
    private static final String ACCOUNT_NUMBER = "1234567890";
    private static final long AMOUNT = 25_000L;

    private static String qrCodeBody(String validUntil, String methodValidUntil) {
        return """
            {
              "validUntil": "%s",
              "creditor": {
                "name": "Acceptance Test",
                "phone": "+14155550100",
                "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "acceptance test", "amountDue": { "amount": %d, "currency": "USD" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "%s", "amount": %d,
                  "networks": { "ach": { "routingNumber": "%s", "accountNumber": "%s", "protectionType": "tokenized" } } }
              ]
            }
            """.formatted(validUntil, AMOUNT, methodValidUntil, AMOUNT, ROUTING_NUMBER, ACCOUNT_NUMBER);
    }

    private static String notification(String qrCodeId, long amount, String currency) {
        return notification(qrCodeId, amount, currency, "ACH");
    }

    /**
     * An ACH notification. ACH is the rail that goes through the acceptance gate: the payer is
     * announcing a debit it has not yet originated, so the QR Code comes out of circulation only if
     * everything checks out. FedNow and RTP carry the QR Code id inside the ISO 20022 message and
     * reconcile from it, so a notification there is a courtesy and changes no status.
     */
    private static String notification(String qrCodeId, long amount, String currency, String network) {
        return """
            {
              "payment": { "qrcodeId": "%s", "amount": %d, "currency": "%s", "network": "%s",
                           "transactionId": "021000021.0000001" },
              "payer": { "info": "Jane Payer, Springfield Savings" },
              "expectedDate": "2030-10-08T06:59:59Z"
            }
            """.formatted(qrCodeId, amount, currency, network);
    }

    private String createQRCode() {
        return createQRCode("2030-12-31T23:59:59Z", "2030-12-31T23:59:59Z");
    }

    private String createQRCode(String validUntil, String methodValidUntil) {
        return given().contentType("application/json").body(qrCodeBody(validUntil, methodValidUntil))
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

    private MockMvcResponse notify(String body) {
        return given().contentType(APPLICATION_JOSE).body(sign(body)).when().post(NOTIFY);
    }

    private String statusOf(String qrCodeId) {
        return given().when().get(CREATE + "/" + qrCodeId)
                .then().statusCode(HttpStatus.OK.value())
                .extract().path("status");
    }

    /**
     * Waits for a moment to actually pass, rather than sleeping a fixed span.
     *
     * <p>A fixed sleep assumes the setup before it was instant. It is not: on a loaded runner the
     * create call alone can consume the window, and the test then fails during setup for reasons
     * that have nothing to do with the rule under test. Waiting on the clock is the only version
     * that measures the rule instead of the machine.
     */
    private void sleepUntilAfter(String instant) throws InterruptedException {
        OffsetDateTime expiry = OffsetDateTime.parse(instant);

        long remaining = java.time.Duration.between(OffsetDateTime.now(ZoneOffset.UTC), expiry).toMillis();

        Thread.sleep(Math.max(remaining, 0) + 500);
    }

    private void assertRefusedAndUntouched(MockMvcResponse response, String qrCodeId, String expectedInBody) {
        assertNotEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertTrue(response.asString().contains(expectedInBody),
                "reason should mention '%s': %s".formatted(expectedInBody, response.asString()));
        assertEquals("ACTIVE", statusOf(qrCodeId), "a refused notification must leave the QR Code untouched");
    }

    // ---------------------------------------------------------------- the happy path, for contrast

    @Test
    void aFullyValidNotificationIsAccepted() {
        String qrCodeId = createQRCode();

        MockMvcResponse response = notify(notification(qrCodeId, AMOUNT, "USD"));

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
    }

    // ---------------------------------------------------------------------------- amount

    @Test
    void anIncorrectAmountIsRefused() {
        String qrCodeId = createQRCode();

        assertRefusedAndUntouched(notify(notification(qrCodeId, AMOUNT + 1, "USD")), qrCodeId, "amount");
    }

    @Test
    void anUnderpaymentIsRefused() {
        String qrCodeId = createQRCode();

        assertRefusedAndUntouched(notify(notification(qrCodeId, 1L, "USD")), qrCodeId, "amount");
    }

    // -------------------------------------------------------------------------- currency

    @Test
    void aCurrencyThisQRCodeDoesNotOfferIsRefused() {
        String qrCodeId = createQRCode();

        assertRefusedAndUntouched(notify(notification(qrCodeId, AMOUNT, "EUR")), qrCodeId, "currency");
    }

    // ------------------------------------------------------------------------------ rail

    /**
     * A bank rail names no destination account in a notification — the account is in the payment
     * message, not here — so the address check that guards a wallet has no equivalent. What is left
     * to guard is the rail itself: a QR Code offering ACH has not thereby offered FedNow.
     */
    @Test
    void aRailThisQRCodeDoesNotOfferIsRefused() {
        String qrCodeId = createQRCode();

        assertRefusedAndUntouched(
                notify(notification(qrCodeId, AMOUNT, "USD", "FedNow")), qrCodeId, "network");
    }

    // ---------------------------------------------------------------------------- expiry

    /**
     * The scenario that matters: a payer who legitimately holds a valid, signed payload and tries to
     * pay it after it expired.
     *
     * <p>Creating an already-doomed QR Code and notifying it proves very little — no real payer ever
     * obtains a payload that way. Here the payload is <b>fetched while the QR Code is still valid</b>,
     * exactly as a payer's app would, and only then does time pass. The payer is holding something
     * genuine and correctly signed; what must stop the payment is the QR Code's own expiry, not any
     * defect in what the payer presents.
     */
    @Test
    void aPayloadFetchedWhileValidCannotBePaidOnceItExpires() throws InterruptedException {
        String expiresSoon = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(12).withNano(0)
                .toString().replace("+00:00", "Z");

        String createResponse = given().contentType("application/json")
                .body(qrCodeBody(expiresSoon, expiresSoon))
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().body().asString();

        String qrCodeId = JsonPath.from(createResponse).getString("id");
        String qrCodeB64 = JsonPath.from(createResponse).getString("qrCodeB64");
        String locationId = JsonPath.from(createResponse).getString("location.id");

        // The payer scans and fetches the payload — while the QR Code is unambiguously valid.
        MockMvcResponse payload = given().contentType(APPLICATION_JOSE)
                .body(sign("{\"qrCodeContent\": \"%s\"}".formatted(qrCodeB64)))
                .when().post("/pub/api/v1/loc/" + locationId);

        assertEquals(HttpStatus.OK.value(), payload.statusCode(),
                "the payer must be able to fetch the payload while the QR Code is valid: " + payload.asString());

        // Time passes. The payer still holds a genuine, correctly signed payload.
        sleepUntilAfter(expiresSoon);

        MockMvcResponse response = notify(notification(qrCodeId, AMOUNT, "USD"));

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(),
                "a payload fetched while valid must not remain payable after the QR Code expires: "
                        + response.asString());
        assertTrue(response.asString().contains("expired"),
                "the reason should say the QR Code expired, not merely name a field: " + response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId), "a refused notification must leave the QR Code untouched");
    }

    /** The same expiry rule, without the payer ever having fetched anything. */
    @Test
    void anExpiredQRCodeIsRefused() throws InterruptedException {
        String expiresSoon = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(10).withNano(0)
                .toString().replace("+00:00", "Z");
        String qrCodeId = createQRCode(expiresSoon, expiresSoon);

        sleepUntilAfter(expiresSoon);

        MockMvcResponse response = notify(notification(qrCodeId, AMOUNT, "USD"));

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(),
                "an expired QR Code must not be payable: " + response.asString());
    }

    // ------------------------------------------------------------------------ QR Code state

    @Test
    void aQRCodeAlreadyBeingPaidIsRefused() {
        String qrCodeId = createQRCode();
        notify(notification(qrCodeId, AMOUNT, "USD"));
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));

        MockMvcResponse second = notify(notification(qrCodeId, AMOUNT, "USD"));

        assertNotEquals(HttpStatus.OK.value(), second.statusCode(),
                "a second payer must not be able to initiate the same QR Code: " + second.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
    }

    @Test
    void aCancelledQRCodeIsRefused() {
        String qrCodeId = createQRCode();
        given().contentType("application/json").body("{\"status\": \"CANCELLED\"}")
                .when().put(CREATE + "/" + qrCodeId + "/status-update")
                .then().statusCode(HttpStatus.OK.value());

        MockMvcResponse response = notify(notification(qrCodeId, AMOUNT, "USD"));

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("CANCELLED", statusOf(qrCodeId));
    }

    @Test
    void anUnknownQRCodeIsRefused() {
        MockMvcResponse response = notify(notification(UUID.randomUUID().toString(), AMOUNT, "USD"));

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
    }

    // ---------------------------------------------------------------------------- opt-in

    /**
     * Notifications are opt-in at creation: ask for one and it exists, say nothing and it does not.
     * A QR Code created without a {@code paymentNotification} object must refuse notifications
     * outright — before any amount, address or expiry is even considered.
     */
    @Test
    void aQRCodeCreatedWithoutAPaymentNotificationRefusesNotifications() {
        String body = qrCodeBody("2030-12-31T23:59:59Z", "2030-12-31T23:59:59Z")
                .replace("\"paymentNotification\": { \"kind\": \"DEFAULT\" },", "");

        String qrCodeId = given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");

        MockMvcResponse response = notify(notification(qrCodeId, AMOUNT, "USD"));

        assertRefusedAndUntouched(response, qrCodeId, "paymentNotification");
    }

    /**
     * {@code kind: EXTERNAL} means the creditor hosts its own callback, so X9.150 is not the party
     * that should be receiving this.
     */
    @Test
    void aQRCodeWithAnExternalNotificationEndpointRefusesNotifications() {
        String body = qrCodeBody("2030-12-31T23:59:59Z", "2030-12-31T23:59:59Z")
                .replace("\"paymentNotification\": { \"kind\": \"DEFAULT\" }",
                         "\"paymentNotification\": { \"kind\": \"EXTERNAL\", \"endpoint\": \"https://biller.example.com/notify\" }");

        String qrCodeId = given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");

        MockMvcResponse response = notify(notification(qrCodeId, AMOUNT, "USD"));

        assertRefusedAndUntouched(response, qrCodeId, "EXTERNAL");
    }

    // ------------------------------------------------------------------------- signature

    @Test
    void anUnsignedBodyIsRefused() {
        String qrCodeId = createQRCode();

        MockMvcResponse response = given().contentType(APPLICATION_JOSE)
                .body(notification(qrCodeId, AMOUNT, "USD"))
                .when().post(NOTIFY);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(),
                "an unsigned notification must never be processed: " + response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

    @Test
    void aTamperedSignatureIsRefused() {
        String qrCodeId = createQRCode();
        String jws = sign(notification(qrCodeId, AMOUNT, "USD"));

        // Same header and payload, one byte of the signature flipped.
        String tampered = jws.substring(0, jws.length() - 2) + (jws.endsWith("A") ? "B" : "A");

        MockMvcResponse response = given().contentType(APPLICATION_JOSE).body(tampered).when().post(NOTIFY);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(),
                "a tampered signature must never be processed: " + response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

}
