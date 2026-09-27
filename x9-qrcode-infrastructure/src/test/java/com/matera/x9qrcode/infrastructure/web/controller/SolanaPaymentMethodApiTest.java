/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Solana as a typed payment method, in the shape the Solana Foundation published.
 *
 * <p>The fields are {@code recipient} and {@code memo} — see {@code official-spec/SOLANA-FIELDS.md}.
 * They are worth stating twice because this repository previously guessed at them, before any
 * publication existed, and modelled a lone {@code walletAddress} with no memo. Both halves were
 * wrong. Every QR Code built on that guess would have been unreadable by a conformant payer, which
 * is precisely the failure ADR-0010 exists to prevent — and the reason the bar for interpreting a
 * network is a publication you can point at.
 *
 * <p>The object key is {@code solana}, lower-case like every other key under {@code networks}
 * (§14.5). The network's name as a notification <em>value</em> is {@code Solana}. Two fields, two
 * conventions; see INTERPRETATION.md I-1.
 */
class SolanaPaymentMethodApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final long AMOUNT = 25_000_000L;

    private static String qrCodeBody(String solanaObject, String currency) {
        return """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Solana Test", "phone": "+14155550100", "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "solana", "amountDue": { "amount": %d, "currency": "%s" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "%s", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": { "solana": %s } }
              ]
            }
            """.formatted(AMOUNT, currency, currency, AMOUNT, solanaObject);
    }

    private JsonPath createAndRead(String solanaObject) {
        String id = given().contentType("application/json").body(qrCodeBody(solanaObject, "USDC"))
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");

        return given().when().get(CREATE + "/" + id)
                .then().statusCode(HttpStatus.OK.value())
                .extract().jsonPath();
    }

    private String createExpectingRejection(String solanaObject) {
        return given().contentType("application/json").body(qrCodeBody(solanaObject, "USDC"))
                .when().post(CREATE)
                .then().statusCode(HttpStatus.BAD_REQUEST.value())
                .extract().asString();
    }

    // --------------------------------------------------------------------------- the published shape

    @Test
    void aSolanaMethodRoundTripsWithBothPublishedFields() {
        JsonPath qrCode = createAndRead(
            """
            { "recipient": "%s", "memo": "{QRCD:\\"abc123\\"}" }""".formatted(RECIPIENT));

        assertEquals(RECIPIENT, qrCode.getString("paymentMethods[0].networks.solana.recipient"),
                qrCode.prettify());
        assertEquals("{QRCD:\"abc123\"}", qrCode.getString("paymentMethods[0].networks.solana.memo"),
                "the memo must survive exactly as the biller wrote it: " + qrCode.prettify());
    }

    /** `memo` is optional (M/O column: O). A method with only a recipient is complete. */
    @Test
    void theMemoIsOptional() {
        JsonPath qrCode = createAndRead("""
            { "recipient": "%s" }""".formatted(RECIPIENT));

        assertEquals(RECIPIENT, qrCode.getString("paymentMethods[0].networks.solana.recipient"));
        assertNull(qrCode.get("paymentMethods[0].networks.solana.memo"), qrCode.prettify());
    }

    /**
     * The memo is carried verbatim and never composed by this service. The published table says the
     * payload ID <em>should</em> be included as {@code {QRCD:"payloadID"}}, but composing that value
     * on the biller's behalf would mean rewriting a field they chose. See INTERPRETATION.md I-8.
     */
    @Test
    void theMemoIsNeverRewrittenByUs() {
        JsonPath qrCode = createAndRead("""
            { "recipient": "%s", "memo": "order 7781 — table 4" }""".formatted(RECIPIENT));

        assertEquals("order 7781 — table 4", qrCode.getString("paymentMethods[0].networks.solana.memo"));
    }

    // ------------------------------------------------------------------------------- refusals

    /** `recipient` is mandatory (M). A Solana method without one names no destination at all. */
    @Test
    void aSolanaMethodWithoutARecipientIsRefused() {
        String response = createExpectingRejection("""
            { "memo": "no recipient here" }""");

        assertTrue(response.toLowerCase().contains("recipient"), response);
    }

    /**
     * The guess this replaced used `walletAddress`. Sending it now is simply an unknown field, and
     * the schema refuses it rather than accepting a method with no destination.
     */
    @Test
    void theOldGuessedFieldNameIsRefused() {
        createExpectingRejection("""
            { "walletAddress": "%s" }""".formatted(RECIPIENT));
    }

    @ParameterizedTest(name = "recipient {0} is refused")
    @ValueSource(strings = {
        "0OIl1111111111111111111111111111111111111111",
        "tooshort",
        "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWMEXTRA"
    })
    void aRecipientThatIsNotBase58OfTheRightLengthIsRefused(String recipient) {
        createExpectingRejection("""
            { "recipient": "%s" }""".formatted(recipient));
    }

    /**
     * Base58 is variable-length: a 32-byte key encodes to 43 or 44 characters, and roughly one in
     * twenty-nine lands on 43. The published table says "44"; reading that as an exact width would
     * reject real wallets, so it is read as a maximum. See INTERPRETATION.md I-8.
     */
    @Test
    void aFortyThreeCharacterRecipientIsAccepted() {
        String fortyThree = RECIPIENT.substring(0, 43);

        JsonPath qrCode = createAndRead("""
            { "recipient": "%s" }""".formatted(fortyThree));

        assertEquals(fortyThree, qrCode.getString("paymentMethods[0].networks.solana.recipient"));
    }

    /** The memo has a published ceiling of 100 characters. */
    @Test
    void aMemoBeyondOneHundredCharactersIsRefused() {
        createExpectingRejection("""
            { "recipient": "%s", "memo": "%s" }""".formatted(RECIPIENT, "x".repeat(101)));
    }

}
