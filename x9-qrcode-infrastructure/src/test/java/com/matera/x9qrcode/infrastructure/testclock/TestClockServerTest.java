/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testclock;

import com.matera.x9qrcode.domain.utils.BusinessClock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestClockServerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private TestClockServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
        BusinessClock.reset();
    }

    private TestClockServer start(X509Certificate cert) {
        server = new TestClockServer("127.0.0.1:0", objectMapper, () -> cert);
        server.start();
        return server;
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** S2.1-AC1: a WARN is logged when the clock listener starts. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void startingLogsAWarning(CapturedOutput output) throws Exception {
        start(TestClockCertificateGuardTest.demoCertificate());

        assertThat(output.getAll()).contains("WARN").contains("TEST CLOCK CONTROL IS ON");
    }

    /** S2.1-AC2: the server itself refuses to start with a certificate that is not bundled. */
    @Test
    void theServerRefusesToStartWithARealCertificate() throws Exception {
        X509Certificate untrusted = TestClockCertificateGuardTest.untrustedCertificate();
        server = new TestClockServer("127.0.0.1:0", objectMapper, () -> untrusted);

        assertThrows(IllegalStateException.class, server::start);
        assertEquals(false, server.isRunning());
    }

    @Test
    void putOffsetSecondsThenGetThenDelete() throws Exception {
        start(TestClockCertificateGuardTest.demoCertificate());

        HttpResponse<String> put = send("PUT", "/test/clock", "{\"offsetSeconds\": 86400}");
        assertEquals(200, put.statusCode());
        assertEquals(86400, objectMapper.readTree(put.body()).get("offsetSeconds").asLong());
        assertEquals(86400, BusinessClock.offsetSeconds());

        JsonNode got = objectMapper.readTree(send("GET", "/test/clock", null).body());
        assertEquals(86400, got.get("offsetSeconds").asLong());
        Instant now = Instant.parse(got.get("now").asText());
        assertThat(Duration.between(Instant.now().plusSeconds(86400), now).abs()).isLessThan(Duration.ofSeconds(2));
        assertThat(got.get("now").asText()).endsWith("Z");

        HttpResponse<String> deleted = send("DELETE", "/test/clock", null);
        assertEquals(200, deleted.statusCode());
        assertEquals(0, objectMapper.readTree(deleted.body()).get("offsetSeconds").asLong());
        assertEquals(0, BusinessClock.offsetSeconds());
    }

    @Test
    void putAtMovesTheClockToThatInstant() throws Exception {
        start(TestClockCertificateGuardTest.demoCertificate());

        HttpResponse<String> put = send("PUT", "/test/clock", "{\"at\": \"2031-12-31T23:59:50Z\"}");

        assertEquals(200, put.statusCode());
        Instant now = Instant.parse(objectMapper.readTree(put.body()).get("now").asText());
        assertThat(Duration.between(Instant.parse("2031-12-31T23:59:50Z"), now).abs()).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void aBadPutIsRefusedAndLeavesTheClockAlone() throws Exception {
        start(TestClockCertificateGuardTest.demoCertificate());

        assertEquals(400, send("PUT", "/test/clock", "{}").statusCode());
        assertEquals(400, send("PUT", "/test/clock", "{\"at\": \"2031-12-31T23:59:50Z\", \"offsetSeconds\": 1}").statusCode());
        assertEquals(400, send("PUT", "/test/clock", "{\"at\": \"tomorrow\"}").statusCode());
        assertEquals(400, send("PUT", "/test/clock", "{\"offsetSeconds\": 1.5}").statusCode());
        assertEquals(400, send("PUT", "/test/clock", "{\"offsetSeconds\": 999999999999}").statusCode());
        assertEquals(400, send("PUT", "/test/clock", "not json").statusCode());

        assertEquals(0, BusinessClock.offsetSeconds());
    }

    @Test
    void otherMethodsAndPathsAreRefused() throws Exception {
        start(TestClockCertificateGuardTest.demoCertificate());

        assertEquals(405, send("POST", "/test/clock", "{}").statusCode());
        assertEquals(404, send("GET", "/test/clock/extra", null).statusCode());
    }

    /** The keystore can be reloaded at runtime: a real certificate swapped in later stops the clock moving. */
    @Test
    void aCertificateSwappedForARealOneStopsTheClockMoving() throws Exception {
        AtomicReference<X509Certificate> current = new AtomicReference<>(TestClockCertificateGuardTest.demoCertificate());
        server = new TestClockServer("127.0.0.1:0", objectMapper, current::get);
        server.start();

        current.set(TestClockCertificateGuardTest.untrustedCertificate());

        assertEquals(403, send("PUT", "/test/clock", "{\"offsetSeconds\": 60}").statusCode());
        assertEquals(0, BusinessClock.offsetSeconds());
    }

    @Test
    void stoppingResetsTheClock() throws Exception {
        start(TestClockCertificateGuardTest.demoCertificate());
        send("PUT", "/test/clock", "{\"offsetSeconds\": 60}");

        server.stop();
        server = null;

        assertEquals(0, BusinessClock.offsetSeconds());
    }

    @Test
    void listenAddressesAreParsed() {
        assertEquals(8099, TestClockServer.parse("127.0.0.1:8099").getPort());
        assertEquals("127.0.0.1", TestClockServer.parse("127.0.0.1:8099").getHostString());
        assertEquals(8099, TestClockServer.parse("[::1]:8099").getPort());
        assertThrows(IllegalArgumentException.class, () -> TestClockServer.parse("8099"));
        assertThrows(IllegalArgumentException.class, () -> TestClockServer.parse("localhost:"));
        assertThrows(IllegalArgumentException.class, () -> TestClockServer.parse("localhost:http"));
    }

}
