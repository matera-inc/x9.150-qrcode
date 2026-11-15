/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

import io.restassured.module.mockmvc.response.MockMvcResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A notification naming a rail or a currency the QR Code does not offer (ADR-0036, amended
 * 2026-11-15 by the owner), through the real endpoint, signature, store and event log.
 *
 * <ul>
 *   <li>A pre-commit: a signed 400 naming the rail or currency, nothing recorded.</li>
 *   <li>A post-commit: the same signed 400, but the call is recorded on a {@code payment.sent}
 *       carrying {@code NOT_OFFERED_RAIL} and/or {@code NOT_OFFERED_CURRENCY}, its hash and
 *       {@code receivedInStatus}; the QR Code's status and reservation do not change.</li>
 * </ul>
 */
class NotOfferedNotificationApiTest extends NotificationMatrixApiSupport {

    private static final String E2E = "20261115021000021P1BRJQ0000000002";
    private static final String TRACE = "021000021.0000002";
    private static final Set<String> CRITICAL_HEADERS = Set.of("correlationId", "iat", "ttl", "statusCode");

    /** A QR Code offering USD on FedNow, and nothing else. */
    private String createFedNowOnlyQRCode() {
        String body = """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Not Offered Test", "phone": "+14155550100", "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "not offered", "amountDue": { "amount": %d, "currency": "USD" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": %d,
                  "networks": {
                    "fednow": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" } } }
              ]
            }
            """.formatted(AMOUNT, AMOUNT);

        return given().contentType("application/json").body(body)
                .when().post(CREATE)
                .then().statusCode(HttpStatus.CREATED.value())
                .extract().path("id");
    }

    /** A bank-rail notification in any currency; no {@code transactionId} makes it a pre-commit. */
    private MockMvcResponse bankIn(String qrCodeId, String network, String currency, String transactionId,
                                   String payerInfo) {
        String reference = transactionId == null ? "" : "\"transactionId\": \"%s\",".formatted(transactionId);
        String body = """
            {
              "payment": { "qrcodeId": "%s", %s "amount": %d, "currency": "%s", "network": "%s" },
              "payer": { "info": "%s" },
              "expectedDate": "2030-10-08T06:59:59Z"
            }
            """.formatted(qrCodeId, reference, AMOUNT, currency, network, payerInfo);

        return given().contentType(APPLICATION_JOSE).body(sign(body)).when().post(NOTIFY);
    }

    private static JWSVerifier publicVerifier() throws Exception {
        String jwks = given().when().get("/pub/.well-known/jwks")
                .then().statusCode(HttpStatus.OK.value())
                .extract().body().asString();

        JWK key = JWKSet.parse(jwks).getKeys().getFirst();

        return key instanceof ECKey ec
            ? new ECDSAVerifier(ec.toECPublicKey(), CRITICAL_HEADERS)
            : new RSASSAVerifier(((RSAKey) key).toRSAPublicKey(), CRITICAL_HEADERS);
    }

    /** A 400 signed by this payee, whose one violation ends with {@code reason}. */
    private void assertSignedRefusal(MockMvcResponse response, String reason) throws Exception {
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        assertTrue(String.valueOf(response.contentType()).startsWith(APPLICATION_JOSE), response.contentType());

        JWSObject answer = JWSObject.parse(response.asString());
        assertTrue(answer.verify(publicVerifier()), "the refusal must be signed by the payee");

        JsonNode body = objectMapper.readTree(answer.getPayload().toString());
        assertEquals(400, body.path("statusCode").asInt(-1), body.toString());
        assertEquals(1, body.path("violations").size(), body.toString());
        assertTrue(body.path("violations").get(0).asText().endsWith(": " + reason), body.toString());
    }

    /**
     * <b>X9-HOLD-230</b> — a pre-commit on a rail the QR Code does not offer, RTP on a FedNow-only
     * QR Code included, or in a currency it does not offer, is a signed 400 naming it; nothing is
     * recorded.
     *
     * <p><b>Source:</b> Ours. ADR-0036, amended 2026-11-15 (owner).
     *
     * <p><b>Why:</b> A pre-commit asks "may I pay this way?" before money moves; the answer for a
     * way the biller never published is no, and saying which part was wrong lets the payer fix it.
     */
    @Test
    void aPreCommitOnAnUnofferedRailOrCurrencyIsASignedRefusalAndRecordsNothing() throws Exception {
        for (String[] notified : List.of(new String[] {"RTP", "USD", "This QR Code does not offer the RTP rail."},
                                         new String[] {"ACH", "USD", "This QR Code does not offer the ACH rail."},
                                         new String[] {"FedNow", "EUR", "This QR Code does not offer the EUR currency."},
                                         new String[] {"RTP", "EUR",
                                             "This QR Code does not offer the RTP rail nor the EUR currency."})) {
            String qrCodeId = createFedNowOnlyQRCode();

            assertSignedRefusal(bankIn(qrCodeId, notified[0], notified[1], null, PAYER_A), notified[2]);

            assertEquals("ACTIVE", statusOf(qrCodeId));
            assertEquals(List.of(), eventTypesFor(qrCodeId), notified[0] + "/" + notified[1] + ": nothing recorded");
        }
    }

    /**
     * <b>X9-HOLD-231</b> — a post-commit on a rail the QR Code does not offer is a signed 400, yet
     * its reference is recorded on a payment.sent flagged {@code NOT_OFFERED_RAIL}; the QR Code
     * stays ACTIVE.
     *
     * <p><b>Source:</b> Ours. ADR-0036, amended 2026-11-15 (owner): "It's an error — we record the
     * call just in case, but we return an error."
     *
     * <p><b>Why:</b> Java grouped FedNow and RTP, so an RTP report reserved a FedNow-only QR Code,
     * and an ACH report was refused and its reference lost. The money may have moved; the payment
     * system needs the reference, and the payer needs to hear it was wrong.
     */
    @Test
    void aPostCommitOnAnUnofferedRailIsRecordedAndRefused() throws Exception {
        String qrCodeId = createFedNowOnlyQRCode();

        assertSignedRefusal(bankIn(qrCodeId, "RTP", "USD", E2E, PAYER_A), "This QR Code does not offer the RTP rail.");

        assertEquals("ACTIVE", statusOf(qrCodeId), "no reservation is taken");
        List<Map<String, Object>> events = eventsFor(qrCodeId);
        assertEquals(List.of("payment.sent"), events.stream().map(event -> event.get("type")).toList());
        Map<String, Object> sent = events.getFirst();
        assertEquals(E2E, sent.get("transactionId"), "the reference is retrievable from the event");
        assertEquals("RTP", sent.get("network"));
        assertEquals(PAYER_A, sent.get("payerInfo"));
        assertEquals("ACTIVE", sent.get("receivedInStatus"));
        assertEquals(List.of("RECEIVED_IN_WRONG_STATUS", "NOT_OFFERED_RAIL"), sent.get("deviations"));
        assertEquals("1.1", sent.get("schemaVersion"));

        assertEquals(HttpStatus.OK.value(), bankIn(qrCodeId, "FedNow", "USD", null, PAYER_B).statusCode(),
            "the QR Code is still open to an honest payer");
    }

    /**
     * <b>X9-HOLD-232</b> — the holder's post-commit in a currency the QR Code does not offer is a
     * signed 400, recorded flagged {@code NOT_OFFERED_CURRENCY}; the reservation and its window
     * are left alone.
     *
     * <p><b>Source:</b> Ours. ADR-0036, amended 2026-11-15 (owner).
     *
     * <p><b>Why:</b> Currency was checked only at the pre-commit gate, so this report registered
     * its hash and froze the reservation as if the QR Code had been paid.
     */
    @Test
    void theHoldersPostCommitInAnUnofferedCurrencyIsRecordedAndRefused() throws Exception {
        String qrCodeId = createFedNowOnlyQRCode();
        assertEquals(HttpStatus.OK.value(), bankIn(qrCodeId, "FedNow", "USD", null, PAYER_A).statusCode());

        assertSignedRefusal(bankIn(qrCodeId, "FedNow", "EUR", E2E, PAYER_A),
            "This QR Code does not offer the EUR currency.");

        assertEquals("PAYMENT_INITIATED", statusOf(qrCodeId));
        List<Map<String, Object>> events = eventsFor(qrCodeId);
        assertEquals(List.of("payment.initiated", "payment.sent"),
            events.stream().map(event -> event.get("type")).toList());
        Map<String, Object> sent = events.getLast();
        assertEquals(E2E, sent.get("transactionId"));
        assertEquals("EUR", sent.get("currency"));
        assertEquals("PAYMENT_INITIATED", sent.get("receivedInStatus"));
        assertEquals(List.of("NOT_OFFERED_CURRENCY"), sent.get("deviations"));

        assertEquals(HttpStatus.OK.value(), bankIn(qrCodeId, "FedNow", "USD", null, PAYER_A).statusCode(),
            "no hash was registered, so the holder may still retry its pre-commit");
        assertEquals(HttpStatus.CONFLICT.value(), bankIn(qrCodeId, "FedNow", "USD", null, PAYER_B).statusCode(),
            "and the holder still holds it");
    }

    /**
     * <b>X9-HOLD-233</b> — a post-commit naming neither an offered rail nor an offered currency
     * carries both codes, and a PAID QR Code stays PAID.
     *
     * <p><b>Source:</b> Ours. ADR-0036, amended 2026-11-15 (owner).
     *
     * <p><b>Why:</b> The payment system decides what to do with the money from the codes; it must
     * be told everything that was wrong.
     */
    @Test
    void bothCodesWhenNeitherIsOfferedAndAPaidQRCodeStaysPaid() throws Exception {
        String qrCodeId = createFedNowOnlyQRCode();
        markPaid(qrCodeId);

        assertSignedRefusal(bankIn(qrCodeId, "ACH", "EUR", TRACE, PAYER_A),
            "This QR Code does not offer the ACH rail nor the EUR currency.");

        assertEquals("PAID", statusOf(qrCodeId));
        Map<String, Object> sent = lastEventFor(qrCodeId);
        assertEquals("payment.sent", sent.get("type"));
        assertEquals(TRACE, sent.get("transactionId"));
        assertEquals("PAID", sent.get("receivedInStatus"));
        assertEquals(List.of("RECEIVED_IN_WRONG_STATUS", "NOT_OFFERED_RAIL", "NOT_OFFERED_CURRENCY"),
            sent.get("deviations"));
    }

}
