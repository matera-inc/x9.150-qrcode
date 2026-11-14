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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * {@code GET/PUT/DELETE /test/clock} on a listener of its own (ADR-0033).
 *
 * <p>This is a plain JDK HTTP server, not a Spring MVC route: it has no handler on the API port to
 * misconfigure, and it is not in {@code openapi.yaml}. It exists only when
 * {@code X9_TESTING_CLOCKCONTROL=true}; see {@link TestClockConfiguration}.
 *
 * <ul>
 *   <li>{@code PUT {"offsetSeconds": n}} or {@code PUT {"at": "2031-12-31T23:59:50Z"}} shifts the clock;</li>
 *   <li>{@code DELETE} restores real time;</li>
 *   <li>all three answer {@code {"now": "…Z", "offsetSeconds": n}}.</li>
 * </ul>
 */
@Slf4j
public class TestClockServer implements SmartLifecycle {

    static final String PATH = "/test/clock";

    private final String listen;
    private final ObjectMapper objectMapper;
    private final Supplier<X509Certificate> signingCertificate;

    private HttpServer server;
    private ExecutorService executor;

    public TestClockServer(String listen, ObjectMapper objectMapper, Supplier<X509Certificate> signingCertificate) {
        this.listen = Objects.requireNonNull(listen, "listen");
        this.objectMapper = objectMapper;
        this.signingCertificate = signingCertificate;
    }

    @Override
    public void start() {
        // Checked here as well as at bean creation, so a server built any other way is guarded too.
        TestClockCertificateGuard.requireBundled(signingCertificate.get());

        InetSocketAddress address = parse(listen);
        try {
            server = HttpServer.create(address, 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not bind the test clock listener to " + listen, e);
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext(PATH, this::handle);
        server.start();

        log.warn("TEST CLOCK CONTROL IS ON (X9_TESTING_CLOCKCONTROL=true): {} {} can move this deployment's "
            + "business time. This is a test deployment; it must never handle real payments.",
            PATH, server.getAddress());
    }

    @Override
    public void stop() {
        if (Objects.nonNull(server)) {
            server.stop(0);
            executor.close();
            server = null;
        }
        BusinessClock.reset();
    }

    @Override
    public boolean isRunning() {
        return Objects.nonNull(server);
    }

    /** The port actually bound; differs from the configured one when that is {@code 0}. */
    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!PATH.equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 404, Map.of("error", "not found"));
                return;
            }
            switch (exchange.getRequestMethod()) {
                case "GET" -> respond(exchange, 200, state());
                case "DELETE" -> {
                    BusinessClock.reset();
                    log.warn("Test clock reset to real time");
                    respond(exchange, 200, state());
                }
                case "PUT" -> put(exchange);
                default -> {
                    exchange.getResponseHeaders().set("Allow", "GET, PUT, DELETE");
                    respond(exchange, 405, Map.of("error", "method not allowed"));
                }
            }
        } catch (RuntimeException e) {
            log.error("Test clock request failed", e);
        }
    }

    private void put(HttpExchange exchange) throws IOException {
        // The keystore can be reloaded at runtime; a real certificate swapped in later stops the clock moving.
        try {
            TestClockCertificateGuard.requireBundled(signingCertificate.get());
        } catch (IllegalStateException e) {
            respond(exchange, 403, Map.of("error", e.getMessage()));
            return;
        }

        JsonNode body;
        try (InputStream in = exchange.getRequestBody()) {
            body = objectMapper.readTree(in);
        } catch (IOException e) {
            respond(exchange, 400, Map.of("error", "body must be JSON: {\"at\": \"…\"} or {\"offsetSeconds\": n}"));
            return;
        }

        boolean hasAt = Objects.nonNull(body) && body.hasNonNull("at");
        boolean hasOffset = Objects.nonNull(body) && body.hasNonNull("offsetSeconds");
        if (hasAt == hasOffset) {
            respond(exchange, 400, Map.of("error", "send exactly one of \"at\" or \"offsetSeconds\""));
            return;
        }

        try {
            if (hasAt) {
                BusinessClock.setAt(Instant.parse(body.get("at").asText()));
            } else if (body.get("offsetSeconds").canConvertToLong() && body.get("offsetSeconds").isIntegralNumber()) {
                BusinessClock.setOffsetSeconds(body.get("offsetSeconds").asLong());
            } else {
                respond(exchange, 400, Map.of("error", "offsetSeconds must be an integer"));
                return;
            }
        } catch (DateTimeParseException e) {
            respond(exchange, 400, Map.of("error", "at must be an RFC 3339 instant, e.g. 2031-12-31T23:59:50Z"));
            return;
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, Map.of("error", e.getMessage()));
            return;
        }

        log.warn("Test clock moved: offsetSeconds={}", BusinessClock.offsetSeconds());
        respond(exchange, 200, state());
    }

    private static Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("now", BusinessClock.clock().instant().truncatedTo(ChronoUnit.MILLIS).toString());
        state.put("offsetSeconds", BusinessClock.offsetSeconds());
        return state;
    }

    private void respond(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static InetSocketAddress parse(String listen) {
        int colon = listen.lastIndexOf(':');
        if (colon <= 0 || colon == listen.length() - 1) {
            throw new IllegalArgumentException(
                "X9_TESTING_CLOCKLISTEN must be host:port, e.g. 127.0.0.1:8099; got '%s'".formatted(listen));
        }
        String host = listen.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int port;
        try {
            port = Integer.parseInt(listen.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "X9_TESTING_CLOCKLISTEN must be host:port, e.g. 127.0.0.1:8099; got '%s'".formatted(listen), e);
        }
        return new InetSocketAddress(host, port);
    }

}
