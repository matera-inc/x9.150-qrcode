/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataDTO;

import com.fasterxml.jackson.databind.JsonNode;

import com.nimbusds.jose.JWSObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The payer's side: announce, wait for the answer, pay, then report.
 *
 * <p>Everything else in this suite serves the payee. These endpoints serve the software that is
 * about to pay a QR Code, and what they buy it is that a PSP never builds a JWS — it posts JSON, and
 * this signs with the deployment's own X9 certificate and delivers.
 *
 * <p>A stub payee runs on localhost so the round trip is real: the assertions below check what
 * actually arrived on the wire, not what we intended to send. That is the only way to catch the
 * failure that matters here — composing a message we believe is signed and conformant, and which
 * the other side cannot read.
 */
class OutboundPaymentNotificationApiTest extends AbstractIntegrationTest {

    private static final int PAYEE_PORT = 18123;
    private static final String PAYEE = "http://127.0.0.1:" + PAYEE_PORT + "/pub/api/v1/payment-notification";

    private static HttpServer payee;
    private static final List<String> received = new CopyOnWriteArrayList<>();
    private static final List<String> receivedContentTypes = new CopyOnWriteArrayList<>();
    private static final AtomicInteger payeeStatus = new AtomicInteger(200);
    private static final AtomicInteger payeeBodyMode = new AtomicInteger(0);

    @BeforeAll
    static void startPayee() throws Exception {
        payee = HttpServer.create(new InetSocketAddress("127.0.0.1", PAYEE_PORT), 0);
        payee.createContext("/", OutboundPaymentNotificationApiTest::handle);
        payee.start();
    }

    @AfterAll
    static void stopPayee() {
        if (payee != null) {
            payee.stop(0);
        }
    }

    @BeforeEach
    void resetPayee() {
        received.clear();
        receivedContentTypes.clear();
        payeeStatus.set(200);
        payeeBodyMode.set(0);
    }

    private static void handle(HttpExchange exchange) throws java.io.IOException {
        received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        receivedContentTypes.add(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));

        byte[] body = payeeBodyMode.get() == 0
            ? new byte[0]
            : "{\"detail\":\"the amount does not match\"}".getBytes(StandardCharsets.UTF_8);

        exchange.sendResponseHeaders(payeeStatus.get(), body.length == 0 ? -1 : body.length);

        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    // ------------------------------------------------------------------------------ requests

    private static String request(String endpoint, String transactionIdField) {
        return """
            {
              "endpoint": "%s",
              "notification": {
                "payment": { "qrcodeId": "01A0E3A12805CB382EF4687F18CDC43A", %s
                             "amount": 25000, "currency": "USD", "network": "ACH" },
                "payer": { "info": "Jane Payer" },
                "expectedDate": "2030-10-08T06:59:59Z"
              }
            }
            """.formatted(endpoint, transactionIdField);
    }

    private static String prePayment(String endpoint) {
        return request(endpoint, "");
    }

    private static String postPayment(String endpoint) {
        return request(endpoint, "\"transactionId\": \"021000021.0000001\",");
    }

    private MockMvcResponse post(String path, String body) {
        return given().contentType("application/json").body(body).when().post(path);
    }

    // -------------------------------------------------------------------- we sign, they verify

    /**
     * <b>X9-SIG-030</b> — what arrives at the payee is a signed JWS.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §9 — notifications are signed. INTERPRETATION I-15/ADR-0015: x9.150 signs
     * the PAYER's notification too, so a PSP integrating here never builds a JWS itself.
     *
     * <p><b>Why:</b> This shipped signing an internal DTO rather than the contract shape. The old test only
     * asserted the id appeared SOMEWHERE in the body, so it passed against a wire format no payee
     * could parse — a check too weak to see the thing it was named after.
     */
    @Test
    void whatArrivesAtThePayeeIsASignedJws() throws Exception {
        MockMvcResponse response = post("/api/v1/payment-notification/pre-payment", prePayment(PAYEE));

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals(1, received.size(), "the payee should have been called exactly once");

        assertTrue(receivedContentTypes.getFirst().startsWith("application/jose"),
                "a payee expects application/jose: " + receivedContentTypes);

        JWSObject delivered = JWSObject.parse(received.getFirst());

        assertTrue(delivered.getHeader().getCriticalParams().containsAll(
                List.of("correlationId", "iat", "ttl")),
                "X9.150 requires those three in crit: " + delivered.getHeader().toJSONObject());
        assertEquals("01A0E3A12805CB382EF4687F18CDC43A",
                payloadOf(delivered).path("payment").path("qrcodeId").asText(null),
                "the payload must carry the notification the caller composed: " + delivered.getPayload());
    }

    private JsonNode payloadOf(JWSObject jws) throws Exception {
        return objectMapper.readTree(jws.getPayload().toString());
    }

    // ------------------------------------------------------------------- the shape on the wire

    /**
     * <b>X9-SIG-031</b> — the id travels inside the payment object, where the standard puts it.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §9, Table — $.payment.qrcodeId. Conformance.
     *
     * <p><b>Why:</b> Structural, not a substring search. The previous assertion would have accepted the id in any
     * field at any depth, which is how the wrong wire shape survived.
     */
    @Test
    void theIdTravelsInsideThePaymentObjectWhereTheStandardPutsIt() throws Exception {
        post("/api/v1/payment-notification/pre-payment", prePayment(PAYEE));

        JsonNode payload = payloadOf(JWSObject.parse(received.getFirst()));

        assertEquals("01A0E3A12805CB382EF4687F18CDC43A", payload.path("payment").path("qrcodeId").asText(null),
                "the id belongs inside payment: " + payload);
        assertTrue(payload.path("qrCodeId").isMissingNode(),
                "and nowhere else — a top-level copy is the bug this test exists for: " + payload);
    }

    /**
     * <b>X9-SIG-032</b> — a payee can read what we sent using the contract type.
     *
     * <p><b>Source:</b> Conformance.
     *
     * <p><b>Why:</b> Deserialises with the published DTO rather than by inspection — the only way to prove an
     * adopter generating a client from our OpenAPI can actually read it.
     */
    @Test
    void aPayeeCanReadWhatWeSentUsingTheContractType() throws Exception {
        post("/api/v1/payment-notification/post-payment", postPayment(PAYEE));

        String payload = JWSObject.parse(received.getFirst()).getPayload().toString();

        PaymentNotificationDataDTO asAPayeeReadsIt = assertDoesNotThrow(
                () -> objectMapper.readValue(payload, PaymentNotificationDataDTO.class), payload);

        assertEquals("01A0E3A12805CB382EF4687F18CDC43A", asAPayeeReadsIt.getPayment().getQrcodeId());
        assertEquals("021000021.0000001", asAPayeeReadsIt.getPayment().getTransactionId());
        assertEquals("ACH", asAPayeeReadsIt.getPayment().getNetwork());
        assertEquals(25000L, asAPayeeReadsIt.getPayment().getAmount());
        assertEquals("Jane Payer", asAPayeeReadsIt.getPayer().getInfo());
    }

    // --------------------------------------------------------------- the verdict is passed back

    /**
     * <b>X9-LIFE-070</b> — an accepted pre-payment comes back accepted.
     *
     * <p><b>Source:</b> Ours. ADR-0015 — we relay the payee's own answer rather than interpreting it.
     *
     * <p><b>Why:</b> The payer's PSP must learn the payee said yes before moving money.
     */
    @Test
    void anAcceptedPrePaymentComesBackAccepted() {
        MockMvcResponse response = post("/api/v1/payment-notification/pre-payment", prePayment(PAYEE));

        assertEquals(HttpStatus.OK.value(), response.statusCode());
        assertEquals(true, response.jsonPath().getBoolean("accepted"), response.asString());
        assertEquals(200, response.jsonPath().getInt("statusCode"));
    }

    /**
     * <b>X9-LIFE-071</b> — a refused pre-payment is an answer, not an error.
     *
     * <p><b>Source:</b> Ours. ADR-0015 — the payee's status is passed through, not interpreted.
     *
     * <p><b>Why:</b> A refusal is a successful round trip with a negative answer. Reporting it as a transport
     * failure would make the caller retry something that was decided, not dropped.
     */
    @Test
    void aRefusedPrePaymentComesBackAsAnAnswerNotAnError() {
        payeeStatus.set(400);
        payeeBodyMode.set(1);

        MockMvcResponse response = post("/api/v1/payment-notification/pre-payment", prePayment(PAYEE));

        assertEquals(HttpStatus.OK.value(), response.statusCode(),
                "we reached the payee; their refusal is the answer, not a transport failure");
        assertFalse(response.jsonPath().getBoolean("accepted"));
        assertEquals(400, response.jsonPath().getInt("statusCode"));
        assertTrue(response.jsonPath().getString("body").contains("does not match"),
                "the payee's reason must survive: " + response.asString());
    }

    /**
     * <b>X9-LIFE-072</b> — an unreachable payee is a gateway failure.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> The complement of LIFE-071: a payee that never answered is genuinely different from one that
     * said no, and only the first is worth retrying.
     */
    @Test
    void anUnreachablePayeeIsAGatewayFailure() {
        MockMvcResponse response = post("/api/v1/payment-notification/pre-payment",
                prePayment("http://127.0.0.1:1/nobody-is-listening"));

        assertEquals(HttpStatus.BAD_GATEWAY.value(), response.statusCode(),
                "a payee outage is not the caller's fault and not a refusal: " + response.asString());
        assertTrue(received.isEmpty());
    }

    // ------------------------------------------------------------------------- phase discipline

    /**
     * <b>X9-LIFE-073</b> — a pre-payment carrying a transaction id is refused before it is sent.
     *
     * <p><b>Source:</b> Ours. ADR-0002 — the phase is inferred from the hash.
     *
     * <p><b>Why:</b> Refused locally rather than relayed, so we do not ask a counterparty to reject a message we
     * already know is contradictory.
     */
    @Test
    void aPrePaymentCarryingATransactionIdIsRefusedBeforeItIsSent() {
        MockMvcResponse response = post("/api/v1/payment-notification/pre-payment", postPayment(PAYEE));

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        assertTrue(received.isEmpty(), "nothing may reach the payee once we know the phase is wrong");
        assertTrue(response.asString().contains("transactionId"), response.asString());
    }

    /**
     * <b>X9-LIFE-074</b> — a post-payment without a transaction id is refused before it is sent.
     *
     * <p><b>Source:</b> Ours. ADR-0002.
     *
     * <p><b>Why:</b> The mirror of LIFE-073. Claiming settlement with no evidence is caught here rather than
     * becoming someone else's 400.
     */
    @Test
    void aPostPaymentWithoutATransactionIdIsRefusedBeforeItIsSent() {
        MockMvcResponse response = post("/api/v1/payment-notification/post-payment", prePayment(PAYEE));

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        assertTrue(received.isEmpty());
    }

    /**
     * <b>X9-LIFE-075</b> — a post-payment carrying its reference is delivered.
     *
     * <p><b>Source:</b> Conformance.
     *
     * <p><b>Why:</b> The acceptance case, so the two refusals above cannot pass against a path that sends nothing.
     */
    @Test
    void aPostPaymentCarryingItsReferenceIsDelivered() throws Exception {
        MockMvcResponse response = post("/api/v1/payment-notification/post-payment", postPayment(PAYEE));

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());

        JWSObject delivered = JWSObject.parse(received.getFirst());

        assertTrue(delivered.getPayload().toString().contains("021000021.0000001"),
                "the reference is the whole point of a post-payment: " + delivered.getPayload());
    }

    // --------------------------------------------------------------------------- bad requests

    /**
     * <b>X9-SIG-033</b> — a relative notification endpoint is refused.
     *
     * <p><b>Source:</b> Ours.
     *
     * <p><b>Why:</b> A relative URI would resolve against OUR host, so a notification meant for the payee would be
     * delivered to ourselves — a loop that looks like a successful send.
     */
    @Test
    void aRelativeEndpointIsRefused() {
        MockMvcResponse response = post("/api/v1/payment-notification/pre-payment",
                prePayment("/pub/api/v1/payment-notification"));

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        assertTrue(received.isEmpty());
    }

}
