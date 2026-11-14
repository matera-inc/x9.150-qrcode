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
        assertEquals(60_000, BusinessClock.offsetMillis());
        assertEquals(200, clock("DELETE", null).statusCode());
        assertEquals(0, BusinessClock.offsetMillis());
    }

    /** S2.1-AC1: never on the API port — even with the flag on. */
    @Test
    void theClockIsNeverOnTheApiPort() throws Exception {
        assertEquals(404, api("GET", "/test/clock", null).statusCode());
        assertEquals(404, api("PUT", "/test/clock", "{\"offsetSeconds\": 60}").statusCode());
        assertEquals(404, api("DELETE", "/test/clock", null).statusCode());
        assertEquals(0, BusinessClock.offsetMillis(), "a request on the API port must not move the clock");
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

    /** S2.1-AC5: with a non-zero offset every API response carries X-Test-Clock-Offset, in milliseconds; DELETE removes it. */
    @Test
    void everyApiResponseCarriesTheOffsetUntilDeleted() throws Exception {
        assertTrue(api("GET", "/pub/.well-known/jwks", null).headers().firstValue(TestClockOffsetHeaderFilter.HEADER).isEmpty());

        clock("PUT", "{\"offsetSeconds\": -" + ONE_HOUR + "}");

        HttpResponse<String> ok = api("GET", "/pub/.well-known/jwks", null);
        assertEquals(200, ok.statusCode());
        assertEquals("-3600000", ok.headers().firstValue(TestClockOffsetHeaderFilter.HEADER).orElse(null));

        HttpResponse<String> notFound = api("GET", "/api/v1/payment-request/" + UUID.randomUUID(), null);
        assertEquals("-3600000", notFound.headers().firstValue(TestClockOffsetHeaderFilter.HEADER).orElse(null),
            "error responses carry the header too");

        HttpResponse<String> deleted = clock("DELETE", null);
        JsonNode state = objectMapper.readTree(deleted.body());
        assertEquals(0, state.get("offsetMillis").asLong());
        assertThat(Duration.between(Instant.now(), Instant.parse(state.get("now").asText())).abs())
            .isLessThan(Duration.ofSeconds(2));

        assertTrue(api("GET", "/pub/.well-known/jwks", null).headers().firstValue(TestClockOffsetHeaderFilter.HEADER).isEmpty());
    }

    // ------------------------------------------------------------- edges, driven over HTTP

    private static final String EDGE = "2030-06-30T12:00:00Z";
    private static final String RECIPIENT = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
    private static final String PAYER_WALLET = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU";
    private static final long AMOUNT = 25_000_000L;

    /** A Solana QR Code whose one payment method stops being payable at {@link #EDGE}. */
    private String createQRCodeWhoseMethodEndsAtTheEdge() throws Exception {
        String body = """
            {
              "validUntil": "2030-12-31T23:59:59Z",
              "creditor": {
                "name": "Edge Payee", "phone": "+14155550100", "email": "test@example.com",
                "address": { "line1": "1 A St", "city": "Springfield", "state": "CA", "postalCode": "90001", "country": "US" },
                "MCC": "5999"
              },
              "bill": {
                "description": "edge",
                "invoice": { "number": "INV-EDGE-1", "date": "2030-01-10", "dueDate": "%s" },
                "amountDue": { "amount": %d, "currency": "USDC" }
              },
              "paymentNotification": { "kind": "DEFAULT" },
              "paymentMethods": [
                { "currency": "USDC", "validUntil": "%s", "amount": %d,
                  "networks": { "solana": { "recipient": "%s" } } }
              ]
            }
            """.formatted(EDGE, AMOUNT, EDGE, AMOUNT, RECIPIENT);

        HttpResponse<String> created = api("POST", "/api/v1/payment-request", body);
        assertEquals(201, created.statusCode(), created.body());
        return objectMapper.readTree(created.body()).get("id").asText();
    }

    /** A pre-commit for the whole amount, signed (iat in business time) and sent. */
    private HttpResponse<String> preCommit(String qrCodeId) throws Exception {
        String notification = """
            {
              "payment": { "qrcodeId": "%s", "amount": %d, "currency": "USDC", "network": "solana" },
              "blockchain": { "action": "PAYMENT_INITIATED", "to": "%s", "from": "%s" }
            }
            """.formatted(qrCodeId, AMOUNT, RECIPIENT, PAYER_WALLET);

        HttpResponse<String> signed = api("POST", "/api/v1/signature/generate", notification,
            "Correlation-Id", UUID.randomUUID().toString(), "TTL-Seconds", "300");
        assertEquals(200, signed.statusCode(), signed.body());

        return send(apiPort, "POST", "/pub/api/v1/payment-notification", "application/jose", signed.body());
    }

    /**
     * Story 2.1 follow-up (for Story 2.6's edge tests, S2.6-AC1/AC2 and plan T1/T3/T6): an edge E is
     * reachable over {@code /test/clock} to the millisecond. A payment method valid until E takes a
     * pre-commit just before E and refuses one at E + 1 ms.
     *
     * <p>The refused side is exact: the clock is put at E + 1 ms and only moves forward, so the
     * notification is judged after E. The accepted side cannot be judged at exactly E over HTTP,
     * because the clock keeps ticking (ADR-0033) while the request travels; it is judged one second
     * before E, which leaves room for that latency. The exact instant E itself is pinned by unit tests.
     */
    @Test
    void aPreCommitIsAcceptedBeforeTheEdgeAndRefusedOneMillisecondAfter() throws Exception {
        Instant edge = Instant.parse(EDGE);
        String before = createQRCodeWhoseMethodEndsAtTheEdge();
        String after = createQRCodeWhoseMethodEndsAtTheEdge();

        HttpResponse<String> moved = clock("PUT", "{\"at\": \"" + edge.minusSeconds(1) + "\"}");
        assertEquals(200, moved.statusCode(), moved.body());
        HttpResponse<String> accepted = preCommit(before);
        assertEquals(200, accepted.statusCode(), accepted.body());
        assertTrue(accepted.headers().firstValue(TestClockOffsetHeaderFilter.HEADER).isPresent());

        moved = clock("PUT", "{\"at\": \"" + edge.plusMillis(1) + "\"}");
        assertEquals(200, moved.statusCode(), moved.body());
        assertEquals("2030-06-30T12:00:00.001Z", edge.plusMillis(1).toString(), "sanity: one millisecond past E");
        assertThat(Instant.parse(objectMapper.readTree(moved.body()).get("now").asText())).isAfter(edge);

        HttpResponse<String> refused = preCommit(after);
        assertEquals(400, refused.statusCode(), refused.body());
        // The answer is a signed JWS; its payload names the rule.
        assertThat(JWSObject.parse(refused.body()).getPayload().toString())
            .contains("paymentMethods.validUntil").contains("expired at 2030-06-30T12:00Z");
    }

}
