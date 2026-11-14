/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.app.dto.SignatureInputDataDTO;
import com.matera.x9qrcode.app.dto.enumerated.SignatureTypeEnumDTO;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.util.Base64URL;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When this service notifies a payee on a payer's behalf, it checks the payee's answer and reports
 * what it found (Story 2.3, S2.3-AC6).
 *
 * <p>ANSI X9.150 A.4.5 asks the payee to answer with a signed {@code {"statusCode": integer}}. The
 * answer is verified against the payee's X9 certificate and the correlationId this service sent, and
 * the result is reported as {@code signature}: {@code VERIFIED}, {@code ABSENT} (not a JWS at all) or
 * {@code INVALID} (presented as a JWS, failing a check). Nothing is refused on that basis: a 200 that
 * is not verified is reported as an unsigned yes.
 *
 * <p>The stub payee signs with this deployment's own key, which the test configuration trusts, so a
 * correct answer verifies and every wrong one fails for the reason the test names. The cases follow
 * the answer table of Story 3.5, as far as they can be produced in-process.
 */
class OutboundAnswerVerificationApiTest extends AbstractIntegrationTest {

    private static final int PAYEE_PORT = 18124;
    private static final String PAYEE = "http://127.0.0.1:" + PAYEE_PORT + "/pub/api/v1/payment-notification";
    private static final String PRE_PAYMENT = "/api/v1/payment-notification/pre-payment";

    /** What the stub payee answers, given the JWS it received. */
    private record Answer(int status, String body) { }

    private static HttpServer payee;
    private static final AtomicReference<Function<String, Answer>> answering = new AtomicReference<>();
    private static final AtomicReference<String> lastSignedYes = new AtomicReference<>();
    private static QRCodeSignatureService signer;

    @Autowired
    private QRCodeSignatureService qrCodeSignatureService;

    @BeforeAll
    static void startPayee() throws IOException {
        payee = HttpServer.create(new InetSocketAddress("127.0.0.1", PAYEE_PORT), 0);
        payee.createContext("/", OutboundAnswerVerificationApiTest::handle);
        payee.start();
    }

    @AfterAll
    static void stopPayee() {
        if (payee != null) {
            payee.stop(0);
        }
    }

    @BeforeEach
    void wireSigner() {
        signer = qrCodeSignatureService;
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String received = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Answer answer = answering.get().apply(received);
        byte[] body = answer.body() == null ? new byte[0] : answer.body().getBytes(StandardCharsets.UTF_8);

        exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);

        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    // ------------------------------------------------------------------------- answer makers

    private static UUID correlationIdOf(String jws) {
        try {
            return UUID.fromString(String.valueOf(JWSObject.parse(jws).getHeader().getCustomParam("correlationId")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String signed(int statusCode, UUID correlationId) {
        return signer.signData(new SignatureInputDataDTO(SignatureTypeEnumDTO.X9,
            Map.of("statusCode", statusCode), correlationId, null, statusCode)).jwsToken();
    }

    private MockMvcResponse notifyPayee() {
        String request = """
            {
              "endpoint": "%s",
              "notification": {
                "payment": { "qrcodeId": "01A0E3A12805CB382EF4687F18CDC43A",
                             "amount": 25000, "currency": "USD", "network": "ACH" },
                "payer": { "info": "Jane Payer" },
                "expectedDate": "2030-10-08T06:59:59Z"
              }
            }
            """.formatted(PAYEE);

        MockMvcResponse response = given().contentType("application/json").body(request).when().post(PRE_PAYMENT);
        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        return response;
    }

    private static void assertReported(MockMvcResponse response, int statusCode, boolean accepted, String signature) {
        assertEquals(statusCode, response.jsonPath().getInt("statusCode"), response.asString());
        assertEquals(accepted, response.jsonPath().getBoolean("accepted"), response.asString());
        assertEquals(signature, response.jsonPath().getString("signature"), response.asString());
    }

    private static String problemOf(MockMvcResponse response) {
        return String.valueOf(response.jsonPath().getString("signatureProblem"));
    }

    // -------------------------------------------------------------------------------- VERIFIED

    /**
     * <b>X9-SIG-130</b> — a signed yes with the correlationId sent is VERIFIED.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 A.4.5 note, §10.3.2, §10.7. S2.3-AC6; Story 3.5 case 1.
     *
     * <p><b>Why:</b> The answer a conformant payee gives must be recognised as one, or the flag means
     * nothing.
     */
    @Test
    void aSignedYesWithTheCorrelationIdSentIsVerified() {
        answering.set(received -> new Answer(200, signed(200, correlationIdOf(received))));

        MockMvcResponse response = notifyPayee();

        assertReported(response, 200, true, "VERIFIED");
        assertNull(response.jsonPath().getString("signatureProblem"));
        assertTrue(response.jsonPath().getString("body").contains("."), "the signed answer is passed back");
    }

    /**
     * <b>X9-SIG-131</b> — a signed no is VERIFIED and not accepted.
     *
     * <p><b>Source:</b> Ours (signed refusals). S2.3-AC6; Story 3.5 case 16.
     *
     * <p><b>Why:</b> A verified refusal is as much evidence as a verified yes.
     */
    @Test
    void aSignedNoIsVerifiedAndNotAccepted() {
        answering.set(received -> new Answer(400, signed(400, correlationIdOf(received))));

        assertReported(notifyPayee(), 400, false, "VERIFIED");
    }

    // ---------------------------------------------------------------------------------- INVALID

    /**
     * <b>X9-SIG-132</b> — a signed yes under another correlationId is INVALID, and still an unsigned yes.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §10.3.2 (the responder echoes correlationId). S2.3-AC6; Story
     * 3.5 cases 2 and 4.
     *
     * <p><b>Why:</b> A correctly signed yes to some other question, replayed, must not pass for the
     * answer to this one. It is reported, not refused: the payer decides what it is worth.
     */
    @Test
    void aSignedYesUnderAnotherCorrelationIdIsInvalid() {
        answering.set(received -> {
            String yes = signed(200, correlationIdOf(received));
            lastSignedYes.set(yes);
            return new Answer(200, yes);
        });
        notifyPayee();
        String replayed = lastSignedYes.get();

        answering.set(received -> new Answer(200, replayed));
        MockMvcResponse response = notifyPayee();

        assertReported(response, 200, true, "INVALID");
        assertTrue(problemOf(response).contains("correlationId"), problemOf(response));
    }

    /**
     * <b>X9-SIG-133</b> — a signed answer whose payload was altered is INVALID.
     *
     * <p><b>Source:</b> RFC 7515; ANSI X9.150-2026 §10.7 step 9. Story 3.5 case 8.
     *
     * <p><b>Why:</b> The signature is the whole point; a body that no longer matches it is not signed.
     */
    @Test
    void aSignedAnswerWhosePayloadWasAlteredIsInvalid() {
        answering.set(received -> {
            String[] parts = signed(200, correlationIdOf(received)).split("\\.");
            String altered = Base64URL.encode("{\"statusCode\":201}").toString();
            return new Answer(200, parts[0] + "." + altered + "." + parts[2]);
        });

        MockMvcResponse response = notifyPayee();

        assertReported(response, 200, true, "INVALID");
        assertFalse(problemOf(response).isBlank());
    }

    /**
     * <b>X9-SIG-134</b> — an HTTP status that differs from the signed one is INVALID.
     *
     * <p><b>Source:</b> Ours. Story 3.5 case 14.
     *
     * <p><b>Why:</b> A signed no delivered as an HTTP 200 (or the reverse) says two things at once;
     * neither can be reported as the payee's signed decision.
     */
    @Test
    void anHttpStatusThatDiffersFromTheSignedOneIsInvalid() {
        answering.set(received -> new Answer(200, signed(400, correlationIdOf(received))));

        MockMvcResponse response = notifyPayee();

        assertReported(response, 200, true, "INVALID");
        assertTrue(problemOf(response).contains("statusCode mismatch"), problemOf(response));
    }

    /**
     * <b>X9-SIG-135</b> — an unsecured JOSE object ({@code alg: none}) is INVALID.
     *
     * <p><b>Source:</b> RFC 7515/7518; ANSI X9.150-2026 §10.3.1 (alg from the X9-approved suite).
     * Story 3.5 case 12.
     *
     * <p><b>Why:</b> It looks like a JWS and carries no signature at all: the classic downgrade.
     */
    @Test
    void anUnsecuredJoseObjectIsInvalid() {
        String header = Base64URL.encode("{\"alg\":\"none\"}").toString();
        String payload = Base64URL.encode("{\"statusCode\":200}").toString();
        answering.set(received -> new Answer(200, header + "." + payload + "."));

        MockMvcResponse response = notifyPayee();

        assertReported(response, 200, true, "INVALID");
    }

    // ----------------------------------------------------------------------------------- ABSENT

    /**
     * <b>X9-SIG-136</b> — an empty 200 is ABSENT and reported as an unsigned yes.
     *
     * <p><b>Source:</b> Ours. S2.3-AC6; Story 3.5 case 5.
     *
     * <p><b>Why:</b> Payees that predate signed answers send exactly this. Refusing it would break
     * them; passing it off as signed would mislead the payer.
     */
    @Test
    void anEmptyYesIsAbsentAndStillAccepted() {
        answering.set(received -> new Answer(200, null));

        MockMvcResponse response = notifyPayee();

        assertReported(response, 200, true, "ABSENT");
        assertFalse(problemOf(response).isBlank());
    }

    /**
     * <b>X9-SIG-137</b> — unsigned JSON and plain text are ABSENT.
     *
     * <p><b>Source:</b> Ours. Story 3.5 cases 6 and 7.
     *
     * <p><b>Why:</b> {@code {"statusCode":200}} unsigned is the A.4.5 body without the part that
     * makes it evidence.
     */
    @Test
    void unsignedJsonAndPlainTextAreAbsent() {
        answering.set(received -> new Answer(200, "{\"statusCode\":200}"));
        assertReported(notifyPayee(), 200, true, "ABSENT");

        answering.set(received -> new Answer(200, "OK"));
        assertReported(notifyPayee(), 200, true, "ABSENT");
    }

    /**
     * <b>X9-SIG-138</b> — a plain JSON refusal is ABSENT, not accepted, body passed through.
     *
     * <p><b>Source:</b> Ours. Story 3.5 case 15.
     *
     * <p><b>Why:</b> The payee's own reason still reaches the payer verbatim.
     */
    @Test
    void aPlainJsonRefusalIsAbsentAndPassedThrough() {
        answering.set(received -> new Answer(400, "{\"detail\":\"the amount does not match\"}"));

        MockMvcResponse response = notifyPayee();

        assertReported(response, 400, false, "ABSENT");
        assertTrue(response.jsonPath().getString("body").contains("does not match"), response.asString());
    }
}
