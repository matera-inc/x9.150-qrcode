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
     * The point of the feature: the caller sent JSON, and what reached the payee was a JWS.
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
     * Where the QR Code id sits, which is not a detail.
     *
     * <p>ANSI X9.150-2026 carries it as {@code payment.qrcodeId} — inside the payment object. This
     * service holds it one level up, beside the payment, and for a while signed that internal record
     * directly: the payload went out with a top-level {@code qrCodeId} and a {@code payment} that
     * had none. Every conformant payee refuses that, ours included, and no test here noticed,
     * because asserting the id appeared <em>somewhere</em> in the payload passes either way.
     *
     * <p>Two instances talking to each other found it in a minute. So this asserts the position.
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
     * The stronger statement: a payee can deserialise what we sent into the very class our own
     * contract generates. If this holds, the two sides cannot have drifted apart.
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

    @Test
    void anAcceptedPrePaymentComesBackAccepted() {
        MockMvcResponse response = post("/api/v1/payment-notification/pre-payment", prePayment(PAYEE));

        assertEquals(HttpStatus.OK.value(), response.statusCode());
        assertEquals(true, response.jsonPath().getBoolean("accepted"), response.asString());
        assertEquals(200, response.jsonPath().getInt("statusCode"));
    }

    /**
     * A refusal is a successful round trip, not a failure of ours — so it comes back as 200 with
     * {@code accepted: false}, and the payee's own words are passed through untouched. Collapsing it
     * into an error here would hide the reason the payer needs in order to react.
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
     * An unreachable payee is the opposite case and must not look like a refusal: nothing was
     * delivered, so the caller should retry rather than conclude the payment was declined.
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
     * The check that earns its keep. A payee infers the phase from whether a reference is present,
     * so a pre-payment carrying one arrives as a post-payment: the QR Code is never reserved, and
     * the payer proceeds to pay against something another payer can still claim.
     */
    @Test
    void aPrePaymentCarryingATransactionIdIsRefusedBeforeItIsSent() {
        MockMvcResponse response = post("/api/v1/payment-notification/pre-payment", postPayment(PAYEE));

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        assertTrue(received.isEmpty(), "nothing may reach the payee once we know the phase is wrong");
        assertTrue(response.asString().contains("transactionId"), response.asString());
    }

    @Test
    void aPostPaymentWithoutATransactionIdIsRefusedBeforeItIsSent() {
        MockMvcResponse response = post("/api/v1/payment-notification/post-payment", prePayment(PAYEE));

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        assertTrue(received.isEmpty());
    }

    @Test
    void aPostPaymentCarryingItsReferenceIsDelivered() throws Exception {
        MockMvcResponse response = post("/api/v1/payment-notification/post-payment", postPayment(PAYEE));

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());

        JWSObject delivered = JWSObject.parse(received.getFirst());

        assertTrue(delivered.getPayload().toString().contains("021000021.0000001"),
                "the reference is the whole point of a post-payment: " + delivered.getPayload());
    }

    // --------------------------------------------------------------------------- bad requests

    @Test
    void aRelativeEndpointIsRefused() {
        MockMvcResponse response = post("/api/v1/payment-notification/pre-payment",
                prePayment("/pub/api/v1/payment-notification"));

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        assertTrue(received.isEmpty());
    }

}
