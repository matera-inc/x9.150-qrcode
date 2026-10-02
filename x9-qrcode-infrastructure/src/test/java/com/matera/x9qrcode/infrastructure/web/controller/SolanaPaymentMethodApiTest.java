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

    /**
     * <b>X9-RAIL-040</b> — a Solana method round-trips with both published fields.
     *
     * <p><b>Source:</b> Ours, on the Solana Foundation's published embedding — recipient plus optional memo.
     * ADR-0010, official-spec/SOLANA-FIELDS.md. X9.150 §14.5 fixes only where the object hangs.
     *
     * <p><b>Why:</b> The acceptance case. X9.150 specifies the style and the root of a payment method; the inner
     * JSON belongs to the network's owner, so these two fields are the Foundation's, not ours.
     */
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

    /**
     * <b>X9-RAIL-041</b> — the Solana memo is optional.
     *
     * <p><b>Source:</b> Ours, per the published embedding.
     *
     * <p><b>Why:</b> A QR Code offering only bank rails has nowhere to put a memo, so requiring it would make
     * Solana unusable alongside them.
     */
    @Test
    void theMemoIsOptional() {
        JsonPath qrCode = createAndRead("""
            { "recipient": "%s" }""".formatted(RECIPIENT));

        assertEquals(RECIPIENT, qrCode.getString("paymentMethods[0].networks.solana.recipient"));
        assertNull(qrCode.get("paymentMethods[0].networks.solana.memo"), qrCode.prettify());
    }

    /**
     * <b>X9-RAIL-042</b> — the memo is carried, never composed by us.
     *
     * <p><b>Source:</b> Ours. INTERPRETATION I-8 — we carry the memo, we do not compose it.
     *
     * <p><b>Why:</b> The memo is the fallback channel for matching an unannounced on-chain payment to a bill.
     * Rewriting it would break the biller's own matching while looking like a helpful
     * normalisation.
     */
    @Test
    void theMemoIsNeverRewrittenByUs() {
        JsonPath qrCode = createAndRead("""
            { "recipient": "%s", "memo": "order 7781 — table 4" }""".formatted(RECIPIENT));

        assertEquals("order 7781 — table 4", qrCode.getString("paymentMethods[0].networks.solana.memo"));
    }

    // ------------------------------------------------------------------------------- refusals

    /**
     * <b>X9-RAIL-043</b> — a Solana method without a recipient is refused.
     *
     * <p><b>Source:</b> Ours, per the published embedding — recipient is required.
     *
     * <p><b>Why:</b> A chain method with no destination is a QR Code that cannot be paid, discovered at the till.
     */
    @Test
    void aSolanaMethodWithoutARecipientIsRefused() {
        String response = createExpectingRejection("""
            { "memo": "no recipient here" }""");

        assertTrue(response.toLowerCase().contains("recipient"), response);
    }

    /**
     * <b>X9-RAIL-044</b> — the field name we once guessed is refused.
     *
     * <p><b>Source:</b> Ours. ADR-0010 — a rail is interpreted on its owner's publication, not on our guess.
     *
     * <p><b>Why:</b> Before the Foundation's embedding was published this field had a name we had invented.
     * Continuing to accept it would quietly bless the guess and spread it to adopters as if it were
     * part of the standard.
     */
    @Test
    void theOldGuessedFieldNameIsRefused() {
        createExpectingRejection("""
            { "walletAddress": "%s" }""".formatted(RECIPIENT));
    }

    /**
     * <b>X9-RAIL-045</b> — a recipient that is not base58 of the right length is refused.
     *
     * <p><b>Source:</b> Ours, per the Solana Foundation's published embedding. ADR-0010.
     *
     * <p><b>Why:</b> A malformed address is money sent nowhere and nothing downstream can recover it.
     * Caught at creation, because after that the QR Code is printed and in somebody's hands.
     */
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
     * <b>X9-RAIL-046</b> — a 43-character recipient is accepted.
     *
     * <p><b>Source:</b> Ours. INTERPRETATION I-8 — the published length is a MAXIMUM, not a width.
     *
     * <p><b>Why:</b> Valid Solana addresses are 43 or 44 characters. Reading the figure as a fixed width would
     * refuse a legitimate address roughly half the time — the kind of bug that looks like
     * flakiness.
     */
    @Test
    void aFortyThreeCharacterRecipientIsAccepted() {
        String fortyThree = RECIPIENT.substring(0, 43);

        JsonPath qrCode = createAndRead("""
            { "recipient": "%s" }""".formatted(fortyThree));

        assertEquals(fortyThree, qrCode.getString("paymentMethods[0].networks.solana.recipient"));
    }

    /**
     * <b>X9-RAIL-047</b> — a memo beyond its published limit is refused.
     *
     * <p><b>Source:</b> Ours, per the published embedding.
     *
     * <p><b>Why:</b> Refused at the door rather than truncated downstream where neither side would be told.
     */
    @Test
    void aMemoBeyondOneHundredCharactersIsRefused() {
        createExpectingRejection("""
            { "recipient": "%s", "memo": "%s" }""".formatted(RECIPIENT, "x".repeat(101)));
    }

}
