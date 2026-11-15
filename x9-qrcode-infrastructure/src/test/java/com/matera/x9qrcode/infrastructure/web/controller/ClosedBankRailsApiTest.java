/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSObject;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bank rails we generate: closed, and holding what the biller wrote.
 *
 * <p>{@code fednow}, {@code rtp} and {@code ach} share the standard's {@code BankAddress} fields:
 * {@code routingNumber}, {@code accountNumber} and {@code protectionType}. As with {@code solana}
 * (#162), an unknown field inside one of them is refused on create and PATCH by its path, rather than
 * dropped with a 201 (ADR-0012, amendment of 2026-11-15: strict about what we generate).
 *
 * <p>And {@code protectionType}, which X9.150 §14.5.x.3 makes a value the payee sets from an enum,
 * is read back as it was sent. The Java build used to answer {@code tokenized} whatever was stored.
 */
class ClosedBankRailsApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";
    private static final String BANK_FIELDS = "routingNumber, accountNumber and protectionType";

    private static String bank(String protectionType, String extra) {
        return """
            { "routingNumber": "021000021", "accountNumber": "1234567890", "protectionType": "%s"%s }"""
            .formatted(protectionType, extra);
    }

    private static String body(String rail, String railObject) {
        return """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Bank Rail Test", "phone": "+14155550100", "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": { "description": "bank rail", "amountDue": { "amount": 1250, "currency": "USD" } },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1250,
                  "networks": { "%s": %s } }
              ]
            }
            """.formatted(rail, railObject);
    }

    private static String patch(String rail, String railObject) {
        return """
            { "paymentMethods": [
                { "currency": "USD", "validUntil": "2030-12-31T23:59:59Z", "amount": 1250,
                  "networks": { "%s": %s } } ] }
            """.formatted(rail, railObject);
    }

    private JsonPath create(String rail, String railObject) {
        String created = given().contentType("application/json").body(body(rail, railObject))
            .when().post(CREATE)
            .then().statusCode(HttpStatus.CREATED.value())
            .extract().asString();
        return JsonPath.from(created);
    }

    private JsonPath get(String id) {
        return given().when().get(CREATE + "/" + id)
            .then().statusCode(HttpStatus.OK.value())
            .extract().jsonPath();
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

    private JsonNode signedPayload(JsonPath created) throws Exception {
        MockMvcResponse response = given().contentType("application/jose")
            .body(sign("{\"qrCodeContent\": \"%s\"}".formatted(created.getString("qrCodeB64"))))
            .when().post("/pub/api/v1/loc/" + created.getString("location.id"));

        assertEquals(HttpStatus.OK.value(), response.statusCode(), response.asString());
        return objectMapper.readTree(JWSObject.parse(response.asString()).getPayload().toString());
    }

    private static Stream<Arguments> railsAndProtectionTypes() {
        return Stream.of("fednow", "rtp", "ach").flatMap(rail ->
            Stream.of("tokenized", "encrypted", "plaintext").map(type -> Arguments.of(rail, type)));
    }

    /**
     * <b>X9-RAIL-056</b> — an unknown field inside {@code fednow}, {@code rtp} or {@code ach} is
     * refused at create, naming its path.
     *
     * <p><b>Source:</b> Ours. ADR-0012, amendment of 2026-11-15: what we generate is closed, so the
     * bank rails are {@code additionalProperties: false} like {@code solana} (R-I9-03).
     *
     * <p><b>Why:</b> The Java build answered 201 and dropped the field, so a biller who mistyped
     * {@code accountNumber} as {@code acountNumber} beside the right one, or added a field they expected
     * a payer to see, printed a QR Code without it and was never told.
     */
    @ParameterizedTest(name = "an unknown field in {0} is refused at create")
    @ValueSource(strings = {"fednow", "rtp", "ach"})
    void anUnknownFieldInABankRailIsRefusedAtCreate(String rail) {
        String response = given().contentType("application/json")
            .body(body(rail, bank("tokenized", ", \"x\": 1")))
            .when().post(CREATE)
            .then().statusCode(HttpStatus.BAD_REQUEST.value())
            .extract().asString();

        assertEquals(List.of("paymentMethods[0].networks.%s.x: is not a field of %s, whose fields are %s"
                .formatted(rail, rail, BANK_FIELDS)),
            JsonPath.from(response).getList("violations"), response);
    }

    /**
     * <b>X9-RAIL-057</b> — an unknown field inside a bank rail is refused on PATCH, naming its path,
     * and nothing changes.
     *
     * <p><b>Source:</b> Ours. ADR-0012, amendment of 2026-11-15: a PATCH replaces the same closed
     * object.
     *
     * <p><b>Why:</b> A refusal at create that a PATCH lets through is no refusal.
     */
    @ParameterizedTest(name = "an unknown field in {0} is refused on PATCH")
    @ValueSource(strings = {"fednow", "rtp", "ach"})
    void anUnknownFieldInABankRailIsRefusedOnPatch(String rail) {
        String id = create(rail, bank("tokenized", "")).getString("id");

        String response = given().contentType("application/json")
            .body(patch(rail, bank("plaintext", ", \"x\": 1")))
            .when().patch(CREATE + "/" + id)
            .then().statusCode(HttpStatus.BAD_REQUEST.value())
            .extract().asString();

        assertEquals(List.of("paymentMethods[0].networks.%s.x: is not a field of %s, whose fields are %s"
                .formatted(rail, rail, BANK_FIELDS)),
            JsonPath.from(response).getList("violations"), response);
        JsonPath after = get(id);
        assertEquals(0, after.getInt("revision"), "a refused PATCH must change nothing: " + after.prettify());
        assertEquals("tokenized", after.getString("paymentMethods[0].networks.%s.protectionType".formatted(rail)));
    }

    /**
     * <b>X9-RAIL-085</b> — {@code protectionType} is read back from GET as it was sent, on each bank
     * rail, for each allowed value.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §14.5.x.3: the protection type is a value the payee sets, from
     * an enum ({@code tokenized}, {@code encrypted}, {@code plaintext}).
     *
     * <p><b>Why:</b> The Java build answered {@code tokenized} whatever was sent, so a biller who sent a
     * plaintext account number read back a claim that it was tokenized. Story 9.6 found it; Go returns
     * what was stored.
     */
    @ParameterizedTest(name = "{0} with protectionType {1} is read back from GET")
    @MethodSource("railsAndProtectionTypes")
    void protectionTypeIsReadBackFromGet(String rail, String protectionType) {
        String id = create(rail, bank(protectionType, "")).getString("id");

        assertEquals(protectionType,
            get(id).getString("paymentMethods[0].networks.%s.protectionType".formatted(rail)));
    }

    /**
     * <b>X9-RAIL-086</b> — {@code protectionType} is in the signed payload as it was sent, on each bank
     * rail, for each allowed value.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §14.5.x.3.
     *
     * <p><b>Why:</b> The payload is what a payer reads and signs against; a value the payee did not set
     * there is a signed statement the payee never made.
     */
    @ParameterizedTest(name = "{0} with protectionType {1} is in the signed payload")
    @MethodSource("railsAndProtectionTypes")
    void protectionTypeIsInTheSignedPayload(String rail, String protectionType) throws Exception {
        JsonPath created = create(rail, bank(protectionType, ""));

        assertEquals(protectionType,
            signedPayload(created).at("/paymentMethods/0/networks/%s/protectionType".formatted(rail)).asText());
    }

    /**
     * <b>X9-RAIL-087</b> — a PATCH that changes only {@code protectionType} changes it.
     *
     * <p><b>Source:</b> ANSI X9.150-2026 §14.5.x.3; ADR-0016 (a revision is a version of the request).
     *
     * <p><b>Why:</b> Once stored, the value is part of the request: a PATCH must be able to move it, and
     * GET must show the new one.
     */
    @ParameterizedTest(name = "a PATCH moves {0}''s protectionType")
    @ValueSource(strings = {"fednow", "rtp", "ach"})
    void aPatchChangesProtectionType(String rail) {
        String id = create(rail, bank("tokenized", "")).getString("id");

        given().contentType("application/json").body(patch(rail, bank("encrypted", "")))
            .when().patch(CREATE + "/" + id)
            .then().statusCode(HttpStatus.OK.value());

        assertEquals("encrypted", get(id).getString("paymentMethods[0].networks.%s.protectionType".formatted(rail)));
    }

}
