/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service;

import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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

    /**
     * <b>X9-LOC-483</b> — a URL is this deployment's own only when its host is the deployment's
     * host, exactly: a URL that merely mentions that host anywhere else is another host's, fetched
     * over {@code https} from the host it names, without its user information.
     *
     * <p><b>Source:</b> Ours. ADR-0027, "Certificate fetching": HTTPS only, except the deployment's
     * own host; no credentials. Go's fetcher (Story 8.3) compares the parsed host, port included,
     * case-insensitively, and drops a user name or password; Java now gives the same answer.
     *
     * <p><b>Why:</b> A {@code jku} or {@code x5u} header is unauthenticated input. The check used to
     * be "the URL contains the public host": {@code http://evil.example/?h=<own host>} passed it,
     * had the host string in its query replaced, and was fetched from {@code evil.example} over
     * plain {@code http}, where anyone on the path could substitute the certificate (known-bugs.md,
     * BUG-005).
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // the own host in the query string
        "http://evil.example/?h=" + OWN_HOST + "                    | https://evil.example/?h=" + OWN_HOST,
        // the own host in the path
        "http://evil.example/" + OWN_HOST + "/cert.pem               | https://evil.example/" + OWN_HOST + "/cert.pem",
        // the own host as the user information: the host is evil.example
        "http://" + OWN_HOST + "@evil.example/cert.pem               | https://evil.example/cert.pem",
        // the own host as a subdomain of another host
        "http://" + OWN_HOST + ".evil.example/cert.pem               | https://" + OWN_HOST + ".evil.example/cert.pem",
        // another host ending with the own host's name
        "http://evil" + OWN_HOST + "/cert.pem                        | https://evil" + OWN_HOST + "/cert.pem",
        "http://evil-" + OWN_HOST + "/cert.pem                       | https://evil-" + OWN_HOST + "/cert.pem",
        // the own host's name on another port is another authority
        "http://" + OWN_HOST + ":8443/cert.pem                       | https://" + OWN_HOST + ":8443/cert.pem",
        // no scheme written, the own host in the query
        "evil.example/cert.pem?h=" + OWN_HOST + "                    | https://evil.example/cert.pem?h=" + OWN_HOST})
    void aUrlThatOnlyMentionsTheOwnHostIsAnotherHostsFetchedOverHttps(String written, String expected)
            throws Exception {
        URI fetched = service().parseLocation(written, true);

        assertEquals(URI.create(expected), fetched, written);
        assertEquals("https", fetched.getScheme(), written);
        assertNotEquals("localhost", fetched.getHost(), written);
    }

    /**
     * <b>X9-LOC-484</b> — the own host is recognised case-insensitively, and with user information
     * dropped, and is still read internally.
     *
     * <p><b>Source:</b> Mechanism. The control for X9-LOC-483: a host name is case-insensitive
     * (RFC 3986 §3.2.2), and Go's fetcher compares it so.
     *
     * <p><b>Why:</b> An exact comparison that forgot case would send the deployment's own
     * certificate fetch out over the network to itself, behind the TLS-terminating edge.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "HTTP://X9-150.EXAMPLE.COM/pub/.well-known/certificate/x9.pem",
        "https://X9-150.Example.Com/pub/.well-known/certificate/x9.pem",
        "http://someone@" + OWN_HOST + "/pub/.well-known/certificate/x9.pem"})
    void theOwnHostIsRecognisedWhateverItsCase(String written) throws Exception {
        assertEquals(URI.create("http://localhost:8080/pub/.well-known/certificate/x9.pem"),
            service().parseLocation(written, true), written);
    }

}
