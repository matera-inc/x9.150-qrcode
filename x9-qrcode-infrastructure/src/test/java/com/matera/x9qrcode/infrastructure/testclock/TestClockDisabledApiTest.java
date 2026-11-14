/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testclock;

import com.matera.x9qrcode.domain.utils.BusinessClock;
import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 2.1 / ADR-0033: with {@code X9_TESTING_CLOCKCONTROL} unset — the default, and production —
 * the clock cannot be moved. Uses the shared default test context, so it proves the default.
 */
class TestClockDisabledApiTest extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int apiPort;

    private HttpResponse<String> api(String method, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + apiPort + "/test/clock"))
            .header("Content-Type", "application/json")
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
            .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** S2.1-AC3: with the flag off, nothing listens on the clock address — the listener is never created. */
    @Test
    void nothingListensOnTheClockAddress() {
        assertTrue(context.getBeansOfType(TestClockServer.class).isEmpty());
        assertTrue(context.getBeansOfType(TestClockConfiguration.class).isEmpty());
    }

    /** S2.1-AC3: /test/clock on the API port answers 404, for every method, and the clock does not move. */
    @Test
    void theApiPortAnswers404() throws Exception {
        assertEquals(404, api("GET", null).statusCode());
        assertEquals(404, api("PUT", "{\"offsetSeconds\": 60}").statusCode());
        assertEquals(404, api("DELETE", null).statusCode());
        assertEquals(0, BusinessClock.offsetMillis());
    }

}
