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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What this service tolerates from a third-party payer, and what it still refuses.
 *
 * <p>Two boundaries, two rules. A create request comes from inside this ecosystem, against a
 * published OpenAPI contract that spells the rail {@code fednow} and the currency {@code USD}; it
 * gets exactly one spelling and a 400 otherwise ({@link NetworkNamingApiTest},
 * {@link SupportedCurrencyApiTest}). A <b>payment notification</b> arrives from a payer whose
 * implementation is not ours to correct, and whose reading of the standard may legitimately differ
 * from ours: §2.4 introduces its network list as "exact, all-uppercase values" and then gives
 * {@code FedNow}, which is not. Someone will send {@code ACH}, someone else {@code ach}, and both
 * read the specification correctly.
 *
 * <p>Refusing a payment over the case of a string we can resolve unambiguously would be
 * indefensible — the payer has already moved, or is about to. So the §2.4-shaped fields on an
 * inbound notification are matched case-insensitively.
 *
 * <p>The point of this file is that leniency is about <em>spelling</em> and nothing else. Every
 * substantive rule — the amount, a currency this QR Code never offered, a rail it does not carry —
 * still refuses, and still leaves the QR Code untouched.
 */
class PayerNotificationLeniencyApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String NOTIFY = "/pub/api/v1/payment-notification";
    private static final String APPLICATION_JOSE = "application/jose";

    private static final long AMOUNT = 25_000L;

    private static String qrCodeBody() {
        return """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Payer Leniency Test",
                "phone": "+14155550100",
                "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "payer leniency", "amountDue": { "amount": %d, "currency": "USD" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "ach": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" } } }
              ]
            }
            """.formatted(AMOUNT, AMOUNT);
    }

    private String createQRCode() {
        return given().contentType("application/json").body(qrCodeBody())
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

    private MockMvcResponse notify(String qrCodeId, String network, String currency, long amount) {
        String body = """
            {
              "payment": { "qrcodeId": "%s", "amount": %d, "currency": "%s", "network": "%s",
                           "transactionId": "021000021.0000001" },
              "payer": { "info": "Jane Payer, Springfield Savings" },
              "expectedDate": "2030-10-08T06:59:59Z"
            }
            """.formatted(qrCodeId, amount, currency, network);

        return given().contentType(APPLICATION_JOSE).body(sign(body)).when().post(NOTIFY);
    }

    private String statusOf(String qrCodeId) {
        return given().when().get(CREATE + "/" + qrCodeId)
                .then().statusCode(HttpStatus.OK.value())
                .extract().path("status");
    }

    // ------------------------------------------------------------------- spelling is forgiven

    @ParameterizedTest(name = "a notification naming the rail {0} is accepted")
    @ValueSource(strings = {"ACH", "ach", "Ach", "aCh"})
    void theRailIsMatchedWhateverTheCase(String network) {
        String qrCodeId = createQRCode();

        MockMvcResponse response = notify(qrCodeId, network, "USD", AMOUNT);

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
    }

    @ParameterizedTest(name = "a notification in currency {0} is accepted")
    @ValueSource(strings = {"USD", "usd", "Usd"})
    void theCurrencyIsMatchedWhateverTheCase(String currency) {
        String qrCodeId = createQRCode();

        MockMvcResponse response = notify(qrCodeId, "ACH", currency, AMOUNT);

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
    }

    /**
     * The value is stored and read back exactly as the payer wrote it. A notification records what
     * somebody claimed; normalising it would be rewriting their words, and the claim is the thing
     * a dispute would later turn on.
     */
    @Test
    void theNotifiedValueIsEchoedBackVerbatim() {
        String qrCodeId = createQRCode();

        assertEquals(HttpStatus.OK.value(), notify(qrCodeId, "aCh", "usd", AMOUNT).statusCode());

        String stored = given().when().get(CREATE + "/" + qrCodeId)
                .then().statusCode(HttpStatus.OK.value())
                .extract().path("paymentNotification.data.payment.network");

        assertEquals("aCh", stored, "we record what the payer said, not what we would have said");
    }

    // ------------------------------------------------------------- substance is still enforced

    /** Leniency is about spelling. A currency this QR Code never offered is not a spelling. */
    @Test
    void aCurrencyThisQRCodeDoesNotOfferIsStillRefused() {
        String qrCodeId = createQRCode();

        MockMvcResponse response = notify(qrCodeId, "ACH", "EUR", AMOUNT);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertTrue(response.asString().contains("EUR"), response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId), "a refused notification must change nothing");
    }

    @Test
    void aRailThisQRCodeDoesNotOfferIsStillRefused() {
        String qrCodeId = createQRCode();

        MockMvcResponse response = notify(qrCodeId, "fednow", "USD", AMOUNT);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

    @Test
    void theWrongAmountIsStillRefused() {
        String qrCodeId = createQRCode();

        MockMvcResponse response = notify(qrCodeId, "ach", "usd", AMOUNT + 1);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

    /** A network we do not interpret is refused whatever its case — leniency is not acceptance. */
    @ParameterizedTest(name = "a notification naming {0} is refused")
    @ValueSource(strings = {"Solana", "solana", "PIX"})
    void anUninterpretedNetworkIsStillRefused(String network) {
        String qrCodeId = createQRCode();

        MockMvcResponse response = notify(qrCodeId, network, "USD", AMOUNT);

        assertNotEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals("ACTIVE", statusOf(qrCodeId));
    }

}
