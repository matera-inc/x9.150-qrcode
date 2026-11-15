/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service;

import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultQRCodeLocationServiceTest {

    private static final String OWN_HOST = "x9-150.example.com";

    private static DefaultQRCodeLocationService service() {
        X9Properties properties = new X9Properties();
        properties.getPublicEndpoints().setHost(OWN_HOST);
        DefaultQRCodeLocationService service = new DefaultQRCodeLocationService(properties, new MockEnvironment());
        ReflectionTestUtils.setField(service, "localPort", 8080);
        return service;
    }

    /**
     * <b>X9-LOC-481</b> — a payload or JWK set on another host is fetched over {@code https},
     * whatever scheme the QR Code or the payload wrote, or none.
     *
     * <p><b>Source:</b> Ours. ENDPOINTS.md: a payer normalises any host that is not its own to
     * {@code https} before fetching a payload or a JWK set. X9.150's tag-26 URL carries no scheme, so
     * the scheme is the reader's decision.
     *
     * <p><b>Why:</b> The payload carries the payee's bank details and the JWK set the key that
     * vouches for them. Fetched over plain {@code http}, anyone on the path could substitute both,
     * and the payer would verify the substitute's signature with the substitute's key. Only this
     * deployment's own host is read internally, over {@code http://localhost} (the control below).
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "payee.example.org/pub/api/v1/loc/68D389223C4E428EB1D6C87122C94EB5",
        "http://payee.example.org/pub/api/v1/loc/68D389223C4E428EB1D6C87122C94EB5",
        "HTTP://payee.example.org/pub/api/v1/loc/68D389223C4E428EB1D6C87122C94EB5",
        "https://payee.example.org/pub/api/v1/loc/68D389223C4E428EB1D6C87122C94EB5"})
    void anotherHostIsFetchedOverHttpsOnly(String written) throws Exception {
        URI fetched = service().parseLocation(written, true);

        assertEquals(URI.create("https://payee.example.org/pub/api/v1/loc/68D389223C4E428EB1D6C87122C94EB5"),
            fetched, written);
    }

    /**
     * <b>X9-LOC-482</b> — this deployment's own host is read internally, on its local port.
     *
     * <p><b>Source:</b> Mechanism. The control for X9-LOC-481: the {@code https} rule is about other
     * hosts, and this is the one case it does not apply to.
     *
     * <p><b>Why:</b> Without the control, a service that rewrote every URL to {@code https} would pass
     * X9-LOC-481 and then fail to read its own payloads behind a TLS-terminating edge.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        OWN_HOST + "/pub/api/v1/loc/68D389223C4E428EB1D6C87122C94EB5",
        "https://" + OWN_HOST + "/pub/api/v1/loc/68D389223C4E428EB1D6C87122C94EB5"})
    void theOwnHostIsReadInternally(String written) throws Exception {
        assertEquals(URI.create("http://localhost:8080/pub/api/v1/loc/68D389223C4E428EB1D6C87122C94EB5"),
            service().parseLocation(written, true), written);
    }

}
