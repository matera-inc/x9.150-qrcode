/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.app.service.QRCodeExternalPayloadService;
import com.matera.x9qrcode.app.usecase.decodeemv.DecodeEmvOutput;
import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Scanning somebody else's QR Code, which offers a rail this build does not interpret.
 *
 * <p>The two sides of this service treat an unknown network <b>oppositely</b>, and the contrast
 * looks like an inconsistency until you notice which side of the transaction each one is on.
 *
 * <p>As <b>issuer</b>, an uninterpretable network is refused at creation, by name
 * (ADR-0012): a QR Code advertising a rail we cannot validate a payment against is a promise we
 * cannot keep, and the payer would discover that at the till.
 *
 * <p>As <b>payer-side transport</b>, the opposite must hold. We do not own this payload, we are not
 * validating a payment against it, and the software that asked us to decode it may understand that
 * rail perfectly well and pay it. X9.150 does not need the syntax and semantics of every payment
 * network in the world in order to carry one — §14.5 says so itself, by deferring each network's
 * contents to its own documentation.
 *
 * <p>So the payload must reach the caller <b>intact, including the parts we cannot read</b>: opaque,
 * not stripped, not rejected, not helpfully normalised. Losing a field here would silently remove
 * the only thing the payer needed in order to pay.
 *
 * <p>The payload gateway is stubbed because the payload has to be one this service would never
 * produce. Everything downstream of it is real — signature validation, deserialisation, the decoder
 * remapping and serialisation back out — which is precisely where a field gets dropped.
 */
class OpaqueNetworkOnDecodeApiTest extends AbstractIntegrationTest {

    private static final String DECODE = "/api/v1/qrcode-emv-decoder";
    private static final String CREATE = "/api/v1/payment-request";

    @MockitoBean
    private QRCodeExternalPayloadService qrCodeExternalPayloadService;

    /**
     * A payload from another implementation: one rail we interpret, one we have never heard of, and
     * a third whose owner has published nothing.
     */
    private static String foreignPayload() {
        return """
            {
              "id": "0196cb82afab46f1bf8dfff2f9c8a53e",
              "status": "ACTIVE",
              "revision": 1,
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": { "name": "Padaria do Bairro" },
              "bill": { "description": "pao na chapa", "amountDue": { "amount": 1250, "currency": "BRL" } },
              "paymentMethods": [
                {
                  "currency": "USD",
                  "validUntil": "2030-12-31T23:59:59Z",
                  "amount": 1250,
                  "networks": {
                    "ach": { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "tokenized" },
                    "pix": { "pixKey": "padaria@example.com", "txid": "ABC123", "merchantCity": "SAO PAULO" },
                    "someFutureChain": { "addr": "zzz-not-base58-at-all", "tag": 99, "nested": { "a": [1, 2, 3] } }
                  }
                }
              ]
            }
            """;
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

    /**
     * A syntactically valid EMV, minted here so it carries a real payload URL.
     *
     * <p>Which QR Code it points at does not matter: the gateway is stubbed, and what comes back is
     * a payload this service would never have produced. The EMV only has to parse.
     */
    private String anEmvThatParses() {
        String body = """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Opaque Test", "phone": "+14155550100", "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "opaque", "amountDue": { "amount": 1250, "currency": "USD" } },
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
            .thenReturn(new DecodeEmvOutput(
                URI.create("https://payee.example.com/loc/1"), 200, sign(foreignPayload())));

        return given().contentType("application/json")
                .body("{\"qrCode\": \"%s\"}".formatted(anEmvThatParses()))
                .when().post(DECODE)
                .then().statusCode(HttpStatus.OK.value())
                .extract().jsonPath();
    }

    /**
     * <b>X9-RAIL-060</b> — a payload offering an uninterpreted rail still decodes.
     *
     * <p><b>Source:</b> Ours. INTERPRETATION I-9 — refused when we ISSUE, carried intact when we TRANSPORT.
     *
     * <p><b>Why:</b> Decoding is the PAYER side: we do not own that payload and are not validating a payment
     * against it. The software that asked us to decode it may understand that rail perfectly well.
     */
    @Test
    void aPayloadOfferingAnUninterpretedRailStillDecodes() {
        JsonPath payload = decode();

        assertEquals("0196cb82afab46f1bf8dfff2f9c8a53e", payload.getString("id"),
                "an unknown rail is not an error on the payer side: " + payload.prettify());
    }

    /**
     * <b>X9-RAIL-061</b> — a rail we do interpret is unaffected by opacity.
     *
     * <p><b>Source:</b> Ours. I-9.
     *
     * <p><b>Why:</b> Opacity is for rails we do NOT interpret. One we do should report an unknown field rather than
     * carry it, or the strictness that makes interpretation worth anything is lost.
     */
    @Test
    void theRailWeDoInterpretIsUnaffected() {
        JsonPath payload = decode();

        assertEquals("021000021",
                payload.getString("paymentMethods[0].networks.ach.routingNumber"), payload.prettify());
    }

    /**
     * <b>X9-RAIL-062</b> — an unknown network arrives with every field intact.
     *
     * <p><b>Source:</b> Ours. I-9 — "ignore" means do not INTERPRET, not discard.
     *
     * <p><b>Why:</b> Dropping the network we cannot read removes the only thing the payer needed in order to pay,
     * and does it silently: the caller receives a payload that looks complete and is not.
     */
    @Test
    void anUnknownNetworkArrivesWithEveryFieldIntact() {
        Map<String, Object> pix = decode().getMap("paymentMethods[0].networks.pix");

        assertNotNull(pix, "the unknown network must not be stripped");
        assertEquals("padaria@example.com", pix.get("pixKey"));
        assertEquals("ABC123", pix.get("txid"));
        assertEquals("SAO PAULO", pix.get("merchantCity"));
        assertEquals(3, pix.size(), "no field may be dropped on the way through: " + pix);
    }

    /**
     * <b>X9-RAIL-063</b> — an unknown network of any shape survives.
     *
     * <p><b>Source:</b> Ours. I-9.
     *
     * <p><b>Why:</b> Numbers, nested objects, arrays — shapes nothing here anticipated. A transport that only
     * survives the shapes we imagined is not a transport.
     */
    @Test
    void anUnknownNetworkOfAnyShapeSurvives() {
        Map<String, Object> chain = decode().getMap("paymentMethods[0].networks.someFutureChain");

        assertNotNull(chain, "an unpublished chain must still reach the payer");
        assertEquals("zzz-not-base58-at-all", chain.get("addr"),
                "we must not have applied our own address rules to a rail that is not ours");
        assertEquals(99, chain.get("tag"));
        assertTrue(chain.get("nested") instanceof Map, "nested structure must survive: " + chain);
    }

    /**
     * <b>X9-RAIL-064</b> — an unknown network keeps its own key spelling.
     *
     * <p><b>Source:</b> Ours. I-9.
     *
     * <p><b>Why:</b> Normalising someone else's key is editing a message that was never ours, and the owning
     * network may well be case-sensitive about it.
     */
    @Test
    void anUnknownNetworkKeepsItsOwnKeySpelling() {
        Map<String, Object> networks = decode().getMap("paymentMethods[0].networks");

        assertTrue(networks.containsKey("someFutureChain"),
                "camelCase belongs to that network's owner, not to us: " + networks.keySet());
    }

}
