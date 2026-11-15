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

    /**
     * <b>X9-TIME-539</b> — starting the clock listener logs a WARN.
     *
     * <p>S2.1-AC1: a WARN is logged when the clock listener starts.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void startingLogsAWarning(CapturedOutput output) throws Exception {
        start(TestClockCertificateGuardTest.demoCertificate());

        assertThat(output.getAll()).contains("WARN").contains("TEST CLOCK CONTROL IS ON");
    }

    /**
     * <b>X9-TIME-540</b> — the clock server refuses to start with a certificate that is not bundled.
     *
     * <p>S2.1-AC2: the server itself refuses to start with a certificate that is not bundled.
     */
    @Test
    void theServerRefusesToStartWithARealCertificate() throws Exception {
        X509Certificate fresh = TestClockCertificateGuardTest.freshCertificate();
        server = new TestClockServer("127.0.0.1:0", objectMapper, () -> fresh);

        assertThrows(IllegalStateException.class, server::start);
        assertEquals(false, server.isRunning());
    }

    /** <b>X9-TIME-541</b> — PUT an offset in seconds, GET it back, DELETE it. */
    @Test
    void putOffsetSecondsThenGetThenDelete() throws Exception {
        start(TestClockCertificateGuardTest.demoCertificate());

        HttpResponse<String> put = send("PUT", "/test/clock", "{\"offsetSeconds\": 86400}");
        assertEquals(200, put.statusCode());
        assertEquals(86_400_000, objectMapper.readTree(put.body()).get("offsetMillis").asLong());
        assertEquals(86_400_000, BusinessClock.offsetMillis());

        JsonNode got = objectMapper.readTree(send("GET", "/test/clock", null).body());
        assertEquals(86_400_000, got.get("offsetMillis").asLong());
        Instant now = Instant.parse(got.get("now").asText());
        assertThat(Duration.between(Instant.now().plusSeconds(86400), now).abs()).isLessThan(Duration.ofSeconds(2));
        assertThat(got.get("now").asText()).endsWith("Z");

        HttpResponse<String> deleted = send("DELETE", "/test/clock", null);
        assertEquals(200, deleted.statusCode());
        assertEquals(0, objectMapper.readTree(deleted.body()).get("offsetMillis").asLong());
        assertEquals(0, BusinessClock.offsetMillis());
    }

    /** <b>X9-TIME-542</b> — PUT {@code at} moves the clock to that instant. */
    @Test
    void putAtMovesTheClockToThatInstant() throws Exception {
        start(TestClockCertificateGuardTest.demoCertificate());

        HttpResponse<String> put = send("PUT", "/test/clock", "{\"at\": \"2031-12-31T23:59:50Z\"}");

        assertEquals(200, put.statusCode());
        Instant now = Instant.parse(objectMapper.readTree(put.body()).get("now").asText());
        assertThat(Duration.between(Instant.parse("2031-12-31T23:59:50Z"), now).abs()).isLessThan(Duration.ofSeconds(2));
    }

    /**
     * <b>X9-TIME-543</b> — the offset can be set in milliseconds.
     *
     * <p>Story 2.1 follow-up: the offset is in milliseconds, and can be set in milliseconds.
     */
    @Test
    void putOffsetMillis() throws Exception {
        start(TestClockCertificateGuardTest.demoCertificate());

        HttpResponse<String> put = send("PUT", "/test/clock", "{\"offsetMillis\": -1001}");

        assertEquals(200, put.statusCode());
        assertEquals(-1001, objectMapper.readTree(put.body()).get("offsetMillis").asLong());
        assertEquals(-1001, BusinessClock.offsetMillis());
    }

    /**
     * <b>X9-TIME-544</b> — PUT {@code at} keeps its milliseconds, and the clock never reads earlier.
     *
     * <p>Story 2.1 follow-up: {@code at} keeps its milliseconds; the clock never reads earlier than it.
     */
    @Test
    void putAtKeepsMilliseconds() throws Exception {
        start(TestClockCertificateGuardTest.demoCertificate());
        Instant at = Instant.parse("2031-12-31T23:59:59.999Z");

        HttpResponse<String> put = send("PUT", "/test/clock", "{\"at\": \"2031-12-31T23:59:59.999Z\"}");

        assertEquals(200, put.statusCode());
        Instant now = Instant.parse(objectMapper.readTree(put.body()).get("now").asText());
        assertThat(now).isAfterOrEqualTo(at);
        assertThat(Duration.between(at, now)).isLessThan(Duration.ofMillis(500));
    }

    /** <b>X9-TIME-545</b> — a bad PUT is refused and leaves the clock where it was. */
    @Test
    void aBadPutIsRefusedAndLeavesTheClockAlone() throws Exception {
        start(TestClockCertificateGuardTest.demoCertificate());

        assertEquals(400, send("PUT", "/test/clock", "{}").statusCode());
        assertEquals(400, send("PUT", "/test/clock", "{\"at\": \"2031-12-31T23:59:50Z\", \"offsetSeconds\": 1}").statusCode());
        assertEquals(400, send("PUT", "/test/clock", "{\"at\": \"tomorrow\"}").statusCode());
        assertEquals(400, send("PUT", "/test/clock", "{\"offsetSeconds\": 1.5}").statusCode());
        assertEquals(400, send("PUT", "/test/clock", "{\"offsetSeconds\": 999999999999}").statusCode());
        assertEquals(400, send("PUT", "/test/clock", "not json").statusCode());
        assertEquals(400, send("PUT", "/test/clock", "{\"offsetMillis\": 1, \"offsetSeconds\": 1}").statusCode());
        assertEquals(400, send("PUT", "/test/clock", "{\"offsetMillis\": 1.5}").statusCode());
        assertEquals(400, send("PUT", "/test/clock", "{\"offsetMillis\": 999999999999999}").statusCode());
        assertEquals(400, send("PUT", "/test/clock", "{\"offsetSeconds\": 9223372036854775807}").statusCode());
        assertEquals(400, send("PUT", "/test/clock", "{\"at\": \"2031-12-31T23:59:50.0005Z\"}").statusCode());

        assertEquals(0, BusinessClock.offsetMillis());
    }

    /** <b>X9-TIME-546</b> — other methods and paths are refused. */
    @Test
    void otherMethodsAndPathsAreRefused() throws Exception {
        start(TestClockCertificateGuardTest.demoCertificate());

        assertEquals(405, send("POST", "/test/clock", "{}").statusCode());
        assertEquals(404, send("GET", "/test/clock/extra", null).statusCode());
    }

    /**
     * <b>X9-TIME-547</b> — a real certificate swapped in at runtime stops the clock moving.
     *
     * <p>The keystore can be reloaded at runtime: a real certificate swapped in later stops the clock moving.
     */
    @Test
    void aCertificateSwappedForARealOneStopsTheClockMoving() throws Exception {
        AtomicReference<X509Certificate> current = new AtomicReference<>(TestClockCertificateGuardTest.demoCertificate());
        server = new TestClockServer("127.0.0.1:0", objectMapper, current::get);
        server.start();

        current.set(TestClockCertificateGuardTest.freshCertificate());

        assertEquals(403, send("PUT", "/test/clock", "{\"offsetSeconds\": 60}").statusCode());
        assertEquals(0, BusinessClock.offsetMillis());
    }

    /** <b>X9-TIME-548</b> — stopping the clock server resets the clock. */
    @Test
    void stoppingResetsTheClock() throws Exception {
        start(TestClockCertificateGuardTest.demoCertificate());
        send("PUT", "/test/clock", "{\"offsetSeconds\": 60}");

        server.stop();
        server = null;

        assertEquals(0, BusinessClock.offsetMillis());
    }

    /** <b>X9-TIME-549</b> — clock listen addresses are parsed. */
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
