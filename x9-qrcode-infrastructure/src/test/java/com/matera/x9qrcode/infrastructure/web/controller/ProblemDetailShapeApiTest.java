/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import io.restassured.module.mockmvc.response.MockMvcResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import java.util.List;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire shape of an error response, pinned.
 *
 * <p>RFC 9457 puts a problem detail's extension members at the top level, and that is what
 * `BaseError` in openapi.yaml declares. Spring achieves it with `ProblemDetailJacksonMixin`, which
 * this service's hand-built ObjectMapper once lacked — so every error body nested its extensions
 * under a `properties` object and contradicted the published contract.
 *
 * <p>These tests exist because that regression is invisible: the status code, the type and the
 * title all stay correct while the members a client actually reads move one level down.
 */
class ProblemDetailShapeApiTest extends AbstractIntegrationTest {

    private static final String CREATE = "/api/v1/payment-request";

    private MockMvcResponse postInvalidPaymentRequest() {
        // No creditor, no bill, no paymentMethods -> validation failure with `violations`.
        return given().contentType("application/json")
                .body("{\"validUntil\": \"2030-12-31T23:59:59Z\"}")
                .when().post(CREATE);
    }

    /**
     * <b>X9-SIG-020</b> — problem detail extension members sit at the top level.
     *
     * <p><b>Source:</b> RFC 9457 §3.2 — extension members are top-level. Conformance.
     *
     * <p><b>Why:</b> Every adopter's error handling reads these fields. Nesting them would make a conformant client
     * see an error body it cannot parse, at exactly the moment something is already wrong.
     */
    @Test
    void shouldPlaceExtensionMembersAtTheTopLevel() {
        MockMvcResponse response = postInvalidPaymentRequest();

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        assertNotNull(response.jsonPath().getList("violations"),
                "`violations` must be a top-level member, as BaseError declares: " + response.asString());
    }

    /**
     * <b>X9-SIG-021</b> — extension members are not nested under a properties object.
     *
     * <p><b>Source:</b> RFC 9457. Conformance.
     *
     * <p><b>Why:</b> Spring's ProblemDetail serialises extensions under "properties" by default. Shipping that
     * would be non-conformant by accident, and is exactly the kind of framework default that
     * survives unnoticed because the body still looks plausible.
     */
    @Test
    void shouldNotNestExtensionMembersUnderAPropertiesObject() {
        MockMvcResponse response = postInvalidPaymentRequest();

        assertFalse(response.asString().contains("\"properties\""),
                "extension members must not be wrapped in a `properties` object: " + response.asString());
    }

    /**
     * <b>X9-SIG-022</b> — the standard problem detail members are kept.
     *
     * <p><b>Source:</b> RFC 9457 §3.1 — type, title, status, detail, instance. Conformance.
     *
     * <p><b>Why:</b> Fixing the nesting must not drop the members a client keys on.
     */
    @Test
    void shouldKeepTheStandardProblemDetailMembers() {
        MockMvcResponse response = postInvalidPaymentRequest();

        assertNotNull(response.jsonPath().getString("type"));
        assertNotNull(response.jsonPath().getString("title"));
        assertNotNull(response.jsonPath().getString("detail"));
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.jsonPath().getInt("status"));
        assertTrue(response.jsonPath().getString("instance").contains(CREATE));
    }

    /**
     * <b>X9-SIG-023</b> — a query parameter of the wrong type answers {@code violations} as an array.
     *
     * <p><b>Source:</b> Ours. {@code BaseError} in openapi.yaml declares {@code violations} as an array of
     * strings, and every other refusal here sends one.
     *
     * <p><b>Why:</b> This one refusal sent a bare string, {@code "limit has invalid value."}. A client
     * that iterates the array, as the contract tells it to, iterated the characters of the sentence or
     * failed to parse the body at all, on a 400 it was supposed to be able to read.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "/pub/api/v1/events?limit=x, limit has invalid value.",
        "/pub/api/v1/events?wait=x, wait has invalid value.",
        "/api/v1/payment-request/0123456789ABCDEF0123456789ABCDEF?revision=x, revision has invalid value."
    })
    void aQueryParameterOfTheWrongTypeAnswersViolationsAsAnArray(String path, String violation) {
        MockMvcResponse response = given().when().get(path);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.statusCode(), response.asString());
        Object violations = response.jsonPath().get("violations");
        assertTrue(violations instanceof List<?>,
                "`violations` must be an array, as BaseError declares: " + response.asString());
        assertEquals(List.of(violation), violations, response.asString());
    }

}
