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
     * A validity window wide enough that creating the QR Code cannot consume it.
     *
     * <p>This is the flake that reached CI. The window is fixed at creation, so the create call has
     * to finish inside it — and the old value left about nine seconds for that. Normally creation
     * takes a hundred milliseconds; on a contended runner it stalled past the window, the QR Code
     * was refused as already expired, and the test failed in <em>setup</em>, for a reason with
     * nothing to do with expiry.
     *
     * <p>Twenty seconds is not a guess at how slow CI gets. It is the point where "creation took
     * this long" stops being scheduling noise and becomes a genuine problem worth a red build.
     */
    private static final int VALIDITY_SECONDS = 20;

    private static String expiringShortly() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(VALIDITY_SECONDS).withNano(0)
                .toString().replace("+00:00", "Z");
    }

    /**
     * Waits for a moment to actually pass, rather than sleeping a fixed span.
     *
     * <p>A fixed sleep assumes the setup before it was instant. Waiting on the clock measures the
     * rule instead of the machine — but only the waiting half. The window above is the other half,
     * and it was the one that broke.
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

    /**
     * <b>X9-LIFE-040</b> — a fully valid notification is accepted.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §9 — Payment Notification. Conformance.
     *
     * <p><b>Why:</b> The acceptance case for the whole endpoint. Without it every refusal below would pass against
     * a service that refused all notifications.
     */
    @Test
    void aFullyValidNotificationIsAccepted() {
        String qrCodeId = createQRCode();

        MockMvcResponse response = notify(notification(qrCodeId, AMOUNT, "USD"));

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
    }

    // ---------------------------------------------------------------------------- amount

    /**
     * <b>X9-AMT-060</b> — an amount that is not the bill is refused over HTTP.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §13.5.1. Conformance.
     *
     * <p><b>Why:</b> The domain rule proven through serialisation, validation order and HTTP status mapping — the
     * layers a unit test cannot see, and where several real defects here have lived.
     */
    @Test
    void anIncorrectAmountIsRefused() {
        String qrCodeId = createQRCode();

        assertRefusedAndUntouched(notify(notification(qrCodeId, AMOUNT + 1, "USD")), qrCodeId, "amount");
    }

    /**
     * <b>X9-AMT-061</b> — an underpayment is refused.
     *
     * <p><b>Source:</b> Conformance.
     *
     * <p><b>Why:</b> Accepting less than is owed and marking the bill paid is the failure that costs money
     * silently: the biller sees a settled bill and a short balance.
     */
    @Test
    void anUnderpaymentIsRefused() {
        String qrCodeId = createQRCode();

        assertRefusedAndUntouched(notify(notification(qrCodeId, 1L, "USD")), qrCodeId, "amount");
    }

    // -------------------------------------------------------------------------- currency

    /**
     * <b>X9-CUR-040</b> — a currency this QR Code does not offer is refused over HTTP.
     *
     * <p><b>Source:</b> Conformance — the QR Code publishes what it accepts.
     *
     * <p><b>Why:</b> No agreed rate and no agreed rail for a currency that was never offered.
     */
    @Test
    void aCurrencyThisQRCodeDoesNotOfferIsRefused() {
        String qrCodeId = createQRCode();

        assertRefusedAndUntouched(notify(notification(qrCodeId, AMOUNT, "EUR")), qrCodeId, "currency");
    }

    // ------------------------------------------------------------------------------ rail

    /**
     * <b>X9-RAIL-030</b> — a rail this QR Code does not offer is refused.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §14.5. Conformance.
     *
     * <p><b>Why:</b> Paying by a route the biller never published bank details for did not pay this bill, whoever
     * received the money.
     */
    @Test
    void aRailThisQRCodeDoesNotOfferIsRefused() {
        String qrCodeId = createQRCode();

        assertRefusedAndUntouched(
                notify(notification(qrCodeId, AMOUNT, "USD", "FedNow")), qrCodeId, "network");
    }

    // ---------------------------------------------------------------------------- expiry

    /**
     * <b>X9-LIFE-041</b> — a payload fetched while valid cannot be paid once it expires.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §11 — validUntil. Conformance.
     *
     * <p><b>Why:</b> Expiry is judged when the payment is NOTIFIED, not when the payload was fetched. Otherwise a
     * payer could hold a fetched payload indefinitely and pay against terms that have lapsed.
     */
    @Test
    void aPayloadFetchedWhileValidCannotBePaidOnceItExpires() throws InterruptedException {
        String expiresSoon = expiringShortly();

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

    // The second expiry test that used to live here has been removed rather than widened.
    //
    // It created an already-doomed QR Code and notified it, which is not a scenario any payer can
    // reach — and it asserted the same rule as the test above, by the same mechanism, at the cost of
    // a second sleep. The rule itself is proven deterministically where the instant is a parameter
    // rather than the wall clock: PaymentNotificationAcceptancePolicyTest, which covers expiry with
    // no timing at all.
    //
    // What survives above is the part only an end-to-end test can show: a payer who fetched a
    // genuine, correctly signed payload while the QR Code was valid still cannot pay it afterwards.

    // ------------------------------------------------------------------------ QR Code state

    /**
     * <b>X9-LIFE-042</b> — a QR Code already being paid is refused.
     *
     * <p><b>Source:</b> Ours. ADR-0002 — the reservation.
     *
     * <p><b>Why:</b> Two payers must not both believe they hold the same QR Code.
     */
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

    /**
     * <b>X9-LIFE-043</b> — a cancelled QR Code is refused.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §9. Conformance.
     *
     * <p><b>Why:</b> A withdrawn bill must not take money.
     */
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

    /**
     * <b>X9-LIFE-044</b> — an unknown QR Code is refused.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> Distinguished from a malformed request so a payer PSP can tell "retry" from "give up".
     */
    @Test
    void anUnknownQRCodeIsRefused() {
        MockMvcResponse response = notify(notification(UUID.randomUUID().toString(), AMOUNT, "USD"));

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
    }

    // ---------------------------------------------------------------------------- opt-in

    /**
     * <b>X9-LIFE-045</b> — a QR Code created without a paymentNotification refuses notifications.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §15 — paymentNotification is optional, so a biller may decline it.
     *
     * <p><b>Why:</b> Declining to be notified is a choice, and accepting notifications anyway would silently
     * override it. A biller who did not ask to be told must not have state changed by being told.
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
     * <b>X9-LIFE-046</b> — a QR Code naming an external endpoint refuses notifications here.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §15.2 — the endpoint names where notifications go.
     *
     * <p><b>Why:</b> The biller pointed payers somewhere else. Accepting one here would settle the bill at a
     * deployment the biller is not reading, so their own system never learns of the payment.
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

    /**
     * <b>X9-SIG-010</b> — an unsigned body is refused.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §9 — notifications are signed. Conformance.
     *
     * <p><b>Why:</b> The endpoint is public and unauthenticated; the signature is the only thing establishing who
     * sent this.
     */
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

    /**
     * <b>X9-SIG-011</b> — a tampered signature is refused.
     *
     * <p><b>Source:</b> RFC 7515 — the signature covers the claims. Conformance.
     *
     * <p><b>Why:</b> If this passed, every other check on this endpoint would be theatre: an attacker could sign a
     * valid notification and then rewrite the amount.
     */
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
