/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.app.usecase.paymentnotification.PaymentNotificationQRCodeUseCase;
import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;
import com.matera.x9qrcode.infrastructure.web.controller.advice.PaymentNotificationAnswerExceptionResolver;

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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * Every answer from the payment-notification endpoint is signed, including those the controller
 * never writes itself (Story 2.3 follow-up).
 *
 * <p>Story 2.3 signed every yes and every no the controller decides. Two kinds of answer were still
 * plain {@code problem+json}: a request Spring refuses before the controller runs (an empty body, a
 * wrong {@code Content-Type}, a body it cannot read), and an exception nothing maps (a 500). A payer
 * cannot tell an unsigned answer from one forged on the way, so these are signed too, under a fresh
 * {@code correlationId} when no question was verified.
 *
 * <p>The use case is replaced by a mock so that a 500 can be produced on demand; the requests Spring
 * refuses never reach it.
 */
class NotificationAnswerSignedEverywhereApiTest extends AbstractIntegrationTest {

    private static final String NOTIFY = "/pub/api/v1/payment-notification";
    private static final String APPLICATION_JOSE = "application/jose";
    private static final Set<String> CRITICAL_HEADERS = Set.of("correlationId", "iat", "ttl", "statusCode");

    @MockitoBean
    private PaymentNotificationQRCodeUseCase paymentNotificationQRCodeUseCase;

    @Autowired
    private PaymentNotificationAnswerExceptionResolver answerExceptionResolver;

    private static JWSVerifier publicVerifier() throws Exception {
        String jwks = given().when().get("/pub/.well-known/jwks")
                .then().statusCode(HttpStatus.OK.value())
                .extract().body().asString();

        JWK key = JWKSet.parse(jwks).getKeys().getFirst();

        return key instanceof ECKey ec
            ? new ECDSAVerifier(ec.toECPublicKey(), CRITICAL_HEADERS)
            : new RSASSAVerifier(((RSAKey) key).toRSAPublicKey(), CRITICAL_HEADERS);
    }

    /** Asserts the answer is a JWS this payee signed, carrying the status, and returns its body. */
    private JsonNode assertSignedAnswer(int status, String contentType, String raw, int expectedStatus)
            throws Exception {
        assertEquals(expectedStatus, status, raw);
        assertTrue(String.valueOf(contentType).startsWith(APPLICATION_JOSE), "application/jose: " + contentType);

        JWSObject answer = JWSObject.parse(raw);
        assertTrue(answer.verify(publicVerifier()), "the answer must verify against the published key");

        JsonNode body = objectMapper.readTree(answer.getPayload().toString());
        assertEquals(expectedStatus, body.path("statusCode").asInt(-1), body.toString());
        assertEquals(expectedStatus, body.path("status").asInt(-1), body.toString());
        assertFalse(body.path("title").asText().isBlank(), body.toString());
        assertNotNull(answer.getHeader().getCustomParam("correlationId"));

        return body;
    }

    private JsonNode assertSignedAnswer(MockMvcResponse response, int expectedStatus) throws Exception {
        return assertSignedAnswer(response.statusCode(), response.contentType(), response.asString(), expectedStatus);
    }

    /**
     * <b>X9-SIG-146</b> — an empty body, which Spring refuses before the controller, is answered with a
     * signed 400.
     *
     * <p><b>Source:</b> Ours (owner decision, Story 2.3 follow-up); ANSI X9.150-2026 A.4.5 note.
     *
     * <p><b>Why:</b> An unsigned 400 is indistinguishable from one injected on the way.
     */
    @Test
    void anEmptyBodyIsAnsweredWithASignedBadRequest() throws Exception {
        MockMvcResponse response = given().contentType(APPLICATION_JOSE).when().post(NOTIFY);

        assertSignedAnswer(response, HttpStatus.BAD_REQUEST.value());
        Mockito.verifyNoInteractions(paymentNotificationQRCodeUseCase);
    }

    /**
     * <b>X9-SIG-147</b> — a wrong {@code Content-Type} is answered with a signed 415.
     *
     * <p><b>Source:</b> Ours (owner decision, Story 2.3 follow-up).
     *
     * <p><b>Why:</b> Spring refuses it during handler mapping, before any advice tied to the controller
     * could run; it still reaches the payer, so it is still signed.
     */
    @Test
    void aWrongContentTypeIsAnsweredWithASignedUnsupportedMediaType() throws Exception {
        MockMvcResponse response = given().contentType("application/json").body("{}").when().post(NOTIFY);

        assertSignedAnswer(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
        Mockito.verifyNoInteractions(paymentNotificationQRCodeUseCase);
    }

    /**
     * <b>X9-SIG-148</b> — a body Spring cannot read is answered with a signed 400.
     *
     * <p><b>Source:</b> Ours (owner decision, Story 2.3 follow-up).
     *
     * <p><b>Why:</b> The endpoint reads its body as a string, so a read failure (an I/O error on the
     * request stream) cannot be provoked through MockMvc. The resolver Spring calls for it is
     * exercised directly, with the exception Spring raises.
     */
    @Test
    void anUnreadableBodyIsAnsweredWithASignedBadRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", NOTIFY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpMessageNotReadableException unreadable = new HttpMessageNotReadableException(
            "I/O error while reading input message", new IOException("connection reset"),
            new MockHttpInputMessage(new byte[0]));

        ModelAndView handled = answerExceptionResolver.resolveException(request, response, null, unreadable);

        assertNotNull(handled, "the resolver handles it");
        assertSignedAnswer(response.getStatus(), response.getContentType(), response.getContentAsString(),
            HttpStatus.BAD_REQUEST.value());

        // Any other path is not this endpoint's to answer.
        assertNull(answerExceptionResolver.resolveException(new MockHttpServletRequest("POST", "/api/v1/payment-request"),
            new MockHttpServletResponse(), null, unreadable));
    }

    /**
     * <b>X9-SIG-149</b> — an exception nothing maps is answered with a signed 500 that says nothing
     * about it, under the question's correlationId.
     *
     * <p><b>Source:</b> Ours (owner decision, Story 2.3 follow-up).
     *
     * <p><b>Why:</b> The question verified, so the payer is owed a signed answer linked to it, even
     * when that answer is "we failed". The exception's own message stays in the log.
     */
    @Test
    void anUnexpectedExceptionIsAnsweredWithASignedInternalServerError() throws Exception {
        Mockito.when(paymentNotificationQRCodeUseCase.execute(any()))
            .thenThrow(new IllegalStateException("an internal detail that must not leak"));

        UUID correlationId = UUID.randomUUID();
        String question = given().contentType("application/json")
                .header("Correlation-Id", correlationId.toString())
                .header("TTL-Seconds", "300")
                .body("""
                    {
                      "payment": { "qrcodeId": "01A0E3A12805CB382EF4687F18CDC43A", "amount": 25000,
                                   "currency": "USD", "network": "ACH" },
                      "payer": { "info": "Jane Payer" },
                      "expectedDate": "2030-10-08T06:59:59Z"
                    }
                    """)
                .when().post("/api/v1/signature/generate")
                .then().statusCode(HttpStatus.OK.value())
                .extract().body().asString();

        MockMvcResponse response = given().contentType(APPLICATION_JOSE).body(question).when().post(NOTIFY);

        JsonNode body = assertSignedAnswer(response, HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertFalse(body.toString().contains("must not leak"), body.toString());
        assertEquals(correlationId.toString(),
            String.valueOf(JWSObject.parse(response.asString()).getHeader().getCustomParam("correlationId")));
    }
}
