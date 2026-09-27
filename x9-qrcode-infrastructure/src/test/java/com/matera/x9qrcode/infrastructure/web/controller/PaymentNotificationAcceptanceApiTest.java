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

    private static final String WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final String OTHER_WALLET = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU";
    private static final long AMOUNT = 25_000_000L;

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
              "bill": { "description": "acceptance test", "amountDue": { "amount": %d, "currency": "USDC" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "%s", "amount": %d,
                  "networks": { "Solana": { "walletAddress": "%s" } } }
              ]
            }
            """.formatted(validUntil, AMOUNT, methodValidUntil, AMOUNT, WALLET);
    }

    private static String notification(String qrCodeId, long amount, String currency, String destination) {
        return """
            {
              "payment": { "qrcodeId": "%s", "amount": %d, "currency": "%s", "network": "Solana" },
              "expectedDate": "2030-10-08T06:59:59Z",
              "blockchain": { "action": "PAYMENT_INITIATED", "to": "%s", "from": "%s" }
            }
            """.formatted(qrCodeId, amount, currency, destination, WALLET);
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

        MockMvcResponse response = notify(notification(qrCodeId, AMOUNT, "USDC", WALLET));

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
    }

    // ---------------------------------------------------------------------------- amount

    @Test
    void anIncorrectAmountIsRefused() {
        String qrCodeId = createQRCode();

        assertRefusedAndUntouched(notify(notification(qrCodeId, AMOUNT + 1, "USDC", WALLET)), qrCodeId, "amount");
    }

    @Test
    void anUnderpaymentIsRefused() {
        String qrCodeId = createQRCode();

        assertRefusedAndUntouched(notify(notification(qrCodeId, 1L, "USDC", WALLET)), qrCodeId, "amount");
    }

    // -------------------------------------------------------------------------- currency

    @Test
    void aCurrencyThisQRCodeDoesNotOfferIsRefused() {
        String qrCodeId = createQRCode();

        assertRefusedAndUntouched(notify(notification(qrCodeId, AMOUNT, "USD", WALLET)), qrCodeId, "currency");
    }

    // ------------------------------------------------------------------ destination address

    @Test
    void aDestinationAddressThisQRCodeNeverPublishedIsRefused() {
        String qrCodeId = createQRCode();

        assertRefusedAndUntouched(
                notify(notification(qrCodeId, AMOUNT, "USDC", OTHER_WALLET)), qrCodeId, "destination address");
    }

    // ---------------------------------------------------------------------------- expiry

    @Test
    void anExpiredQRCodeIsRefused() throws InterruptedException {
        // Expires almost immediately, but stays readable: the TTL reaper only removes the document
        // 30 seconds after validUntil, so the refusal is observable rather than a 404 race.
        String soon = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(2)
                .withNano(0).toString().replace("+00:00", "Z");
        String qrCodeId = createQRCode(soon, soon);

        Thread.sleep(2500);

        MockMvcResponse response = notify(notification(qrCodeId, AMOUNT, "USDC", WALLET));

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(),
                "an expired QR Code must not be payable: " + response.asString());
        assertTrue(response.asString().contains("ValidUntil"),
                "the reason should point at validUntil: " + response.asString());

        // Two things are deliberately not asserted here, both recorded as Q19 in
        // PLAN-PAYMENT-NOTIFICATIONS.md.
        //
        // The status is not read back through GET: an expired QR Code stops being readable at all,
        // and the TTL reaper removes the document 30 seconds after validUntil, so the same condition
        // answers differently depending on timing.
        //
        // And the refusal does not come from the acceptance policy. ValidUntilVO rejects a past date
        // in its constructor, so an expired QR Code cannot even be RESTORED from the database — the
        // entity refuses to exist before any policy runs. The payment is correctly refused, but the
        // reason a caller sees is a field-validation message rather than "this QR Code expired",
        // which is a worse diagnostic than the rule deserves.
    }

    // ------------------------------------------------------------------------ QR Code state

    @Test
    void aQRCodeAlreadyBeingPaidIsRefused() {
        String qrCodeId = createQRCode();
        notify(notification(qrCodeId, AMOUNT, "USDC", WALLET));
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));

        MockMvcResponse second = notify(notification(qrCodeId, AMOUNT, "USDC", WALLET));

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

        MockMvcResponse response = notify(notification(qrCodeId, AMOUNT, "USDC", WALLET));

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("CANCELLED", statusOf(qrCodeId));
    }

    @Test
    void anUnknownQRCodeIsRefused() {
        MockMvcResponse response = notify(notification(UUID.randomUUID().toString(), AMOUNT, "USDC", WALLET));

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

        MockMvcResponse response = notify(notification(qrCodeId, AMOUNT, "USDC", WALLET));

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

        MockMvcResponse response = notify(notification(qrCodeId, AMOUNT, "USDC", WALLET));

        assertRefusedAndUntouched(response, qrCodeId, "EXTERNAL");
    }

    // ------------------------------------------------------------------------- signature

    @Test
    void anUnsignedBodyIsRefused() {
        String qrCodeId = createQRCode();

        MockMvcResponse response = given().contentType(APPLICATION_JOSE)
                .body(notification(qrCodeId, AMOUNT, "USDC", WALLET))
                .when().post(NOTIFY);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(),
                "an unsigned notification must never be processed: " + response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

    @Test
    void aTamperedSignatureIsRefused() {
        String qrCodeId = createQRCode();
        String jws = sign(notification(qrCodeId, AMOUNT, "USDC", WALLET));

        // Same header and payload, one byte of the signature flipped.
        String tampered = jws.substring(0, jws.length() - 2) + (jws.endsWith("A") ? "B" : "A");

        MockMvcResponse response = given().contentType(APPLICATION_JOSE).body(tampered).when().post(NOTIFY);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(),
                "a tampered signature must never be processed: " + response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

}
