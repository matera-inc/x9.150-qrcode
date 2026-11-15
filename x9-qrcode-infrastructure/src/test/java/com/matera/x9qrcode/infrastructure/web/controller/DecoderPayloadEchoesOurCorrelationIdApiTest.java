/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.app.dto.SignatureInputDataDTO;
import com.matera.x9qrcode.app.dto.enumerated.SignatureTypeEnumDTO;
import com.matera.x9qrcode.app.service.JwkService;
import com.matera.x9qrcode.app.service.QRCodeExternalPayloadService;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.app.usecase.decodeemv.DecodeEmvOutput;
import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.jwk.JWK;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * The decoder's payload is an answer to the request this service sent, and must say so.
 *
 * <p>Owner decision, 2026-11-15 (ADR-0027, amendment of that date): "Always check that the payee's
 * answer echoes our correlationId. No correlationId, I can't trust the other side." The payload's
 * JWS must carry in its header exactly the correlationId the payload request went out under: the
 * caller's {@code correlationID} when it gave one, otherwise the one this service generated for the
 * request. Missing or different, the payload is refused in the words used for any payload whose
 * signature does not verify, and nothing of it is presented.
 *
 * <p>The payload gateway is stubbed; it answers with a payload signed by this deployment's key,
 * which the test configuration trusts, so each wrong answer fails for the one reason the test names.
 */
class DecoderPayloadEchoesOurCorrelationIdApiTest extends AbstractIntegrationTest {

    private static final String DECODE = "/api/v1/qrcode-emv-decoder";
    private static final String CREATE = "/api/v1/payment-request";
    private static final String REFUSED = "Payload JWS signature is invalid.";
    private static final String PAYLOAD_ID = "0196cb82afab46f1bf8dfff2f9c8a541";

    @MockitoBean
    private QRCodeExternalPayloadService qrCodeExternalPayloadService;

    @Autowired
    private QRCodeSignatureService qrCodeSignatureService;

    @Autowired
    private JwkService<JWK, JWSSigner> jwkService;

    private static String payload() {
        return """
            {
              "id": "%s",
              "status": "ACTIVE",
              "revision": 1,
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": { "name": "Banco Exemplo" },
              "bill": { "description": "echo", "amountDue": { "amount": 1250, "currency": "USD" } },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1250,
                  "networks": { "ach": { "routingNumber": "021000021", "accountNumber": "1234567890",
                                         "protectionType": "plaintext" } } }
              ]
            }
            """.formatted(PAYLOAD_ID);
    }

    private String signed(UUID correlationId) {
        try {
            return qrCodeSignatureService.signData(new SignatureInputDataDTO(SignatureTypeEnumDTO.X9,
                objectMapper.readTree(payload()), correlationId, null, null)).jwsToken();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Signed under {@code correlationId}, then re-signed with no correlationId in the header or in crit. */
    private String signedWithoutCorrelationId(UUID correlationId) {
        try {
            JWSObject original = JWSObject.parse(signed(correlationId));
            JWSHeader header = original.getHeader();
            Map<String, Object> params = new HashMap<>(header.getCustomParams());
            params.remove("correlationId");
            Set<String> crit = new HashSet<>(header.getCriticalParams());
            crit.remove("correlationId");
            JWSObject rewritten = new JWSObject(
                new JWSHeader.Builder(header).customParams(params).criticalParams(crit).build(),
                original.getPayload());
            rewritten.sign(jwkService.retrieveSigner());
            return rewritten.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A syntactically valid EMV with a payload URL; which QR Code it names does not matter. */
    private String anEmvThatParses() {
        String body = """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Echo Test", "phone": "+14155550100", "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "echo", "amountDue": { "amount": 1250, "currency": "USD" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1250,
                  "networks": { "ach": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" } } }
              ]
            }
            """;

        return given().contentType("application/json").body(body)
            .when().post(CREATE)
            .then().statusCode(HttpStatus.CREATED.value())
            .extract().path("qrCode");
    }

    /** The payee answers with {@code answer} applied to the correlationId the request was sent under. */
    private void payeeAnswers(Function<UUID, String> answer) {
        Mockito.when(qrCodeExternalPayloadService.retrievePayload(anyString(), anyString(), any(), any()))
            .thenAnswer(asked -> new DecodeEmvOutput(
                URI.create("https://other-bank.example.com/loc/1"), 200, answer.apply(asked.getArgument(3))));
    }

    private MockMvcResponse decode(UUID callersCorrelationId) {
        String body = callersCorrelationId == null
            ? "{\"qrCode\": \"%s\"}".formatted(anEmvThatParses())
            : "{\"qrCode\": \"%s\", \"correlationID\": \"%s\"}".formatted(anEmvThatParses(), callersCorrelationId);

        return given().contentType("application/json").body(body).when().post(DECODE);
    }

    private static void assertRefused(MockMvcResponse response) {
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        assertTrue(response.asString().contains(REFUSED), response.asString());
        assertFalse(response.asString().contains(PAYLOAD_ID), "nothing of the payload is presented: " + response.asString());
    }

    /**
     * <b>X9-DEC-022</b> — without a caller's correlationID, the payload request goes out under one this
     * service generates, and a payload that echoes it decodes.
     *
     * <p><b>Source:</b> Ours. ADR-0027, amendment of 2026-11-15 (owner): every signed answer from
     * another party must echo our correlationId. ANSI X9.150-2026 §10.3.2 has the responder echo it.
     *
     * <p><b>Why:</b> The control for X9-DEC-023: the id the payee must echo is the one actually sent,
     * so a caller that gives none still gets a payload it can trust.
     */
    @Test
    void withoutACallersCorrelationIdThePayloadEchoesTheGeneratedOne() {
        payeeAnswers(this::signed);

        MockMvcResponse response = decode(null);

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        assertEquals(PAYLOAD_ID, response.jsonPath().getString("id"), response.asString());
        ArgumentCaptor<UUID> sent = ArgumentCaptor.forClass(UUID.class);
        Mockito.verify(qrCodeExternalPayloadService).retrievePayload(anyString(), anyString(), any(), sent.capture());
        assertNotNull(sent.getValue(), "the request went out under a correlationId");
    }

    /**
     * <b>X9-DEC-023</b> — a payload signed under another correlationId than the request's is refused,
     * whether the caller gave the id or this service generated it.
     *
     * <p><b>Source:</b> Ours. ADR-0027, amendment of 2026-11-15 (owner). Before it, the id was compared
     * only when the caller gave one.
     *
     * <p><b>Why:</b> A validly signed payload under another id is an answer to another request — a
     * replay, or a payee mixing its answers up. Its signature proves who wrote it, not that it answers us.
     */
    @Test
    void aPayloadUnderAnotherCorrelationIdIsRefused() {
        payeeAnswers(sent -> signed(UUID.randomUUID()));

        assertRefused(decode(null));
        assertRefused(decode(UUID.randomUUID()));
    }

    /**
     * <b>X9-DEC-024</b> — a payload whose header carries no correlationId is refused, whether or not the
     * caller gave one.
     *
     * <p><b>Source:</b> Ours. ADR-0027, amendment of 2026-11-15 (owner): "No correlationId, I can't
     * trust the other side." ANSI X9.150-2026 §10.7 step 3 requires the header.
     *
     * <p><b>Why:</b> An answer that names no request cannot be tied to ours, however good its signature.
     */
    @Test
    void aPayloadWithoutACorrelationIdIsRefused() {
        payeeAnswers(this::signedWithoutCorrelationId);

        assertRefused(decode(null));
        assertRefused(decode(UUID.randomUUID()));
    }
}
