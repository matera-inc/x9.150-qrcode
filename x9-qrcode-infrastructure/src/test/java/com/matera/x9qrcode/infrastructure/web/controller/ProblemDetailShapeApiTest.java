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
import org.springframework.http.HttpStatus;

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

}
