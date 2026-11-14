/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testclock;

import com.matera.x9qrcode.domain.utils.BusinessClock;
import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 2.1 / ADR-0033: a test deployment's clock can be moved over HTTP, on its own listener.
 *
 * <p>Runs the whole application with {@code X9_TESTING_CLOCKCONTROL=true} and the bundled demo
 * signing certificate. The clock listener binds an ephemeral port so parallel runs cannot collide.
 */
@TestPropertySource(properties = {
    "x9.testing.clock-control=true",
    "x9.testing.clock-listen=127.0.0.1:0"
})
class TestClockControlApiTest extends AbstractIntegrationTest {

    private static final long ONE_HOUR = 3_600;

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private TestClockServer testClockServer;

    @LocalServerPort
    private int apiPort;

    @AfterEach
    void resetClock() {
        BusinessClock.reset();
    }

    private HttpResponse<String> send(int port, String method, String path, String contentType, String body,
                                      String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (headers.length > 0) {
            request.headers(headers);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> clock(String method, String body) throws Exception {
        return send(testClockServer.port(), method, "/test/clock", body == null ? null : "application/json", body);
    }

    private HttpResponse<String> api(String method, String path, String body, String... headers) throws Exception {
        return send(apiPort, method, path, body == null ? null : "application/json", body, headers);
    }

    private String sign() throws Exception {
        HttpResponse<String> signed = api("POST", "/api/v1/signature/generate", "{\"hello\":\"world\"}",
            "Correlation-Id", UUID.randomUUID().toString(), "TTL-Seconds", "300");
        assertEquals(200, signed.statusCode(), signed.body());
        return signed.body();
    }

    /** S2.1-AC1: GET/PUT/DELETE /test/clock are served on the clock listener. */
    @Test
    void theClockIsServedOnItsOwnListener() throws Exception {
        assertNotEquals(apiPort, testClockServer.port());

        assertEquals(200, clock("GET", null).statusCode());
        assertEquals(200, clock("PUT", "{\"offsetSeconds\": 60}").statusCode());
        assertEquals(60, BusinessClock.offsetSeconds());
        assertEquals(200, clock("DELETE", null).statusCode());
        assertEquals(0, BusinessClock.offsetSeconds());
    }

    /** S2.1-AC1: never on the API port — even with the flag on. */
    @Test
    void theClockIsNeverOnTheApiPort() throws Exception {
        assertEquals(404, api("GET", "/test/clock", null).statusCode());
        assertEquals(404, api("PUT", "/test/clock", "{\"offsetSeconds\": 60}").statusCode());
        assertEquals(404, api("DELETE", "/test/clock", null).statusCode());
        assertEquals(0, BusinessClock.offsetSeconds(), "a request on the API port must not move the clock");
    }

    /** S2.1-AC4: a JWS signed under the offset carries an iat that includes it. */
    @Test
    void aJwsSignedUnderTheOffsetCarriesTheShiftedIat() throws Exception {
        clock("PUT", "{\"offsetSeconds\": " + 24 * ONE_HOUR + "}");

        long iat = ((Number) JWSObject.parse(sign()).getHeader().getCustomParam("iat")).longValue();

        long expected = Instant.now().plusSeconds(24 * ONE_HOUR).toEpochMilli();
        assertTrue(Math.abs(iat - expected) < 5_000, "iat must be real time plus one day; off by " + (iat - expected) + " ms");
    }

    /** S2.1-AC4: verification reads the shifted clock too — a JWS whose iat + ttl has passed in business time is expired. */
    @Test
    void verificationUsesTheShiftedClock() throws Exception {
        String jws = sign();
        String validate = "{\"content\":\"" + jws + "\"}";
        assertEquals(200, api("POST", "/api/v1/signature/validate", validate).statusCode());

        clock("PUT", "{\"offsetSeconds\": " + ONE_HOUR + "}");
        HttpResponse<String> expired = api("POST", "/api/v1/signature/validate", validate);
        // The only thing that changed between the two calls is business time; the body is a generic
        // business-rule violation, the log says "JWS message has expired".
        assertEquals(400, expired.statusCode(), expired.body());

        clock("DELETE", null);
        assertEquals(200, api("POST", "/api/v1/signature/validate", validate).statusCode());
    }

    /** S2.1-AC5: with a non-zero offset every API response carries X-Test-Clock-Offset; DELETE removes it. */
    @Test
    void everyApiResponseCarriesTheOffsetUntilDeleted() throws Exception {
        assertTrue(api("GET", "/pub/.well-known/jwks", null).headers().firstValue(TestClockOffsetHeaderFilter.HEADER).isEmpty());

        clock("PUT", "{\"offsetSeconds\": -" + ONE_HOUR + "}");

        HttpResponse<String> ok = api("GET", "/pub/.well-known/jwks", null);
        assertEquals(200, ok.statusCode());
        assertEquals("-3600", ok.headers().firstValue(TestClockOffsetHeaderFilter.HEADER).orElse(null));

        HttpResponse<String> notFound = api("GET", "/api/v1/payment-request/" + UUID.randomUUID(), null);
        assertEquals("-3600", notFound.headers().firstValue(TestClockOffsetHeaderFilter.HEADER).orElse(null),
            "error responses carry the header too");

        HttpResponse<String> deleted = clock("DELETE", null);
        JsonNode state = objectMapper.readTree(deleted.body());
        assertEquals(0, state.get("offsetSeconds").asLong());
        assertThat(Duration.between(Instant.now(), Instant.parse(state.get("now").asText())).abs())
            .isLessThan(Duration.ofSeconds(2));

        assertTrue(api("GET", "/pub/.well-known/jwks", null).headers().firstValue(TestClockOffsetHeaderFilter.HEADER).isEmpty());
    }

}
