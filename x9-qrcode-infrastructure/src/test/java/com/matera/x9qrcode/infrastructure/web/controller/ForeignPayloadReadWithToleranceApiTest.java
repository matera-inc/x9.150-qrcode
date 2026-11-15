/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.app.dto.SignatureInputDataDTO;
import com.matera.x9qrcode.app.dto.enumerated.SignatureTypeEnumDTO;
import com.matera.x9qrcode.app.service.QRCodeExternalPayloadService;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.app.usecase.decodeemv.DecodeEmvOutput;
import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Decoding another bank's QR Code: strict about what we generate, tolerant about what we read.
 *
 * <p>Owner decision, 2026-11-15 (ADR-0012, amendment of that date): a payload issued by another
 * bank may name a currency this deployment does not settle, offer a rail this service does not
 * interpret, or carry a field this service has never heard of. None of those is a reason to refuse
 * the decode. We parse, present what we know, carry an unknown network intact (INTERPRETATION I-9),
 * and ignore an unknown field. "That's the soul of interoperability of X9.150."
 *
 * <p>Every case here was already decoded by the Java build before the decision was written down;
 * these tests pin that, so the strictness added on the generating side (the closed rails, the
 * currency allow list) cannot leak into the reading side. The payload gateway is stubbed; everything
 * after it — signature validation, deserialisation, the decoder's mapping and serialisation — is real.
 */
class ForeignPayloadReadWithToleranceApiTest extends AbstractIntegrationTest {

    private static final String DECODE = "/api/v1/qrcode-emv-decoder";
    private static final String CREATE = "/api/v1/payment-request";

    @MockitoBean
    private QRCodeExternalPayloadService qrCodeExternalPayloadService;

    @Autowired
    private QRCodeSignatureService qrCodeSignatureService;

    /**
     * Another bank's payload: a USD method on ACH with fields we do not know, a BRL method on Pix only,
     * a USDC method on Solana with a field we do not know, and unknown fields at every level above.
     */
    private static String foreignPayload() {
        return """
            {
              "id": "0196cb82afab46f1bf8dfff2f9c8a53f",
              "status": "ACTIVE",
              "revision": 3,
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": { "name": "Banco Exemplo", "loyaltyId": "L-1" },
              "bill": { "description": "foreign", "amountDue": { "amount": 1250, "currency": "USD" } },
              "bankExtension": { "channel": "branch", "flags": [1, 2] },
              "paymentMethods": [
                {
                  "currency": "USD",
                  "validUntil": "2030-12-31T23:59:59Z",
                  "amount": 1250,
                  "priority": 1,
                  "networks": {
                    "ach": { "routingNumber": "021000021", "accountNumber": "1234567890",
                             "protectionType": "plaintext", "accountType": "checking" }
                  }
                },
                {
                  "currency": "BRL",
                  "validUntil": "2030-12-31T23:59:59Z",
                  "amount": 6500,
                  "networks": { "pix": { "pixKey": "loja@example.com" } }
                },
                {
                  "currency": "USDC",
                  "validUntil": "2030-12-31T23:59:59Z",
                  "amount": 12500000,
                  "networks": {
                    "solana": { "recipient": "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM",
                                "referenceKeys": ["abc"] }
                  }
                }
              ]
            }
            """;
    }

    /**
     * The payee's answer: {@code payload}, signed with this deployment's key (which the test
     * configuration trusts) under {@code correlationId}, the one the request went out under.
     */
    private String sign(String payload, UUID correlationId) {
        try {
            return qrCodeSignatureService.signData(new SignatureInputDataDTO(SignatureTypeEnumDTO.X9,
                objectMapper.readTree(payload), correlationId, null, null)).jwsToken();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A syntactically valid EMV with a payload URL; which QR Code it names does not matter. */
    private String anEmvThatParses() {
        String body = """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Tolerance Test", "phone": "+14155550100", "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "tolerance", "amountDue": { "amount": 1250, "currency": "USD" } },
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

    private JsonPath decode() {
        Mockito.when(qrCodeExternalPayloadService.retrievePayload(anyString(), anyString(), any(), any()))
            .thenAnswer(asked -> new DecodeEmvOutput(
                URI.create("https://other-bank.example.com/loc/1"), 200, sign(foreignPayload(), asked.getArgument(3))));

        return given().contentType("application/json")
            .body("{\"qrCode\": \"%s\"}".formatted(anEmvThatParses()))
            .when().post(DECODE)
            .then().statusCode(HttpStatus.OK.value())
            .extract().jsonPath();
    }

    /**
     * <b>X9-DEC-017</b> — another bank's payload with an unknown rail, an unknown currency and unknown
     * fields decodes: 200, every payment method presented, in order.
     *
     * <p><b>Source:</b> Ours. ADR-0012, amendment of 2026-11-15; INTERPRETATION I-9.
     *
     * <p><b>Why:</b> A refusal here would make every QR Code from a bank that offers one more rail, or
     * one more currency, unpayable through this service, although the payer may well be able to pay
     * the parts it knows.
     */
    @Test
    void aForeignPayloadWithUnknownsDecodes() {
        JsonPath payload = decode();

        assertEquals("0196cb82afab46f1bf8dfff2f9c8a53f", payload.getString("id"), payload.prettify());
        assertEquals(java.util.List.of("USD", "BRL", "USDC"), payload.getList("paymentMethods.currency"),
            payload.prettify());
    }

    /**
     * <b>X9-DEC-018</b> — a currency this deployment does not settle is presented as the issuer wrote it.
     *
     * <p><b>Source:</b> Ours. ADR-0012, amendment of 2026-11-15: {@code supported-currencies.json}
     * governs what we generate, not what we read.
     *
     * <p><b>Why:</b> The allow list exists because this deployment's rails cannot settle BRL; another
     * bank's can, and its payer is not ours to second-guess.
     */
    @Test
    void anUnknownCurrencyIsPresented() {
        JsonPath payload = decode();

        assertEquals("BRL", payload.getString("paymentMethods[1].currency"), payload.prettify());
        assertEquals(6500, payload.getInt("paymentMethods[1].amount"), payload.prettify());
        assertEquals("loja@example.com", payload.getString("paymentMethods[1].networks.pix.pixKey"),
            "an unknown rail is carried intact (R-I9-02): " + payload.prettify());
    }

    /**
     * <b>X9-DEC-019</b> — an unknown field inside a rail we interpret is ignored, and the rail's known
     * fields are presented, {@code protectionType} as the issuer wrote it.
     *
     * <p><b>Source:</b> Ours. ADR-0012, amendment of 2026-11-15: the payload-side rails
     * ({@code PayloadFedNow}, {@code PayloadRTP}, {@code PayloadACH}, {@code PayloadSolana}) are open,
     * unlike the request side's.
     *
     * <p><b>Why:</b> Closing a rail is how we keep our own QR Codes honest; applied to another bank's,
     * it would refuse a payload for a field its owner was entitled to add.
     */
    @Test
    void anUnknownFieldInsideAKnownRailIsIgnored() {
        JsonPath payload = decode();

        Map<String, Object> ach = payload.getMap("paymentMethods[0].networks.ach");
        assertEquals("021000021", ach.get("routingNumber"), payload.prettify());
        assertEquals("1234567890", ach.get("accountNumber"), payload.prettify());
        assertEquals("plaintext", ach.get("protectionType"), payload.prettify());
        assertFalse(ach.containsKey("accountType"), "an unknown field is ignored, not carried: " + ach);

        Map<String, Object> solana = payload.getMap("paymentMethods[2].networks.solana");
        assertEquals("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", solana.get("recipient"), payload.prettify());
        assertFalse(solana.containsKey("referenceKeys"), "an unknown field is ignored, not carried: " + solana);
    }

    /**
     * <b>X9-DEC-020</b> — an unknown field on a payment method, or at the top of the payload, is
     * ignored.
     *
     * <p><b>Source:</b> Ours. ADR-0012, amendment of 2026-11-15; INTERPRETATION I-9 (outside
     * {@code networks}, an unknown key is not carried).
     *
     * <p><b>Why:</b> The decode must not fail on it, and must not pass it on as if X9.150 defined it.
     */
    @Test
    void anUnknownFieldOnAMethodOrAtTheTopIsIgnored() {
        JsonPath payload = decode();

        assertNull(payload.get("bankExtension"), payload.prettify());
        assertNull(payload.get("paymentMethods[0].priority"), payload.prettify());
        assertNull(payload.get("creditor.loyaltyId"), payload.prettify());
        assertTrue(payload.getMap("paymentMethods[0].networks").containsKey("ach"), payload.prettify());
    }

    /**
     * <b>X9-DEC-021</b> — tolerance stops at integrity: the same foreign payload, its signature broken,
     * is refused and nothing of it is presented.
     *
     * <p><b>Source:</b> Ours. ADR-0012, amendment of 2026-11-15 (tolerant about content, never about
     * integrity); ADR-0027 (the payload's JWS is verified before anything in it is read).
     *
     * <p><b>Why:</b> Tolerating unknown content is safe only because the content is the issuer's,
     * attested by its signature. A payload whose signature fails is nobody's.
     */
    @Test
    void aForeignPayloadWhoseSignatureIsBrokenIsRefused() {
        // Signed under the correlationId asked, so the broken signature is the one thing wrong.
        Mockito.when(qrCodeExternalPayloadService.retrievePayload(anyString(), anyString(), any(), any()))
            .thenAnswer(asked -> {
                String[] parts = sign(foreignPayload(), asked.getArgument(3)).split("\\.");
                String sig = parts[2];
                String broken = parts[0] + "." + parts[1] + "." + (sig.charAt(0) == 'A' ? 'B' : 'A') + sig.substring(1);
                return new DecodeEmvOutput(URI.create("https://other-bank.example.com/loc/1"), 200, broken);
            });

        var response = given().contentType("application/json")
            .body("{\"qrCode\": \"%s\"}".formatted(anEmvThatParses()))
            .when().post(DECODE)
            .then().extract().response();

        assertTrue(response.statusCode() >= 400 && response.statusCode() < 500,
            response.statusCode() + " " + response.asString());
        assertFalse(response.asString().contains("0196cb82afab46f1bf8dfff2f9c8a53f"), response.asString());
    }

}
