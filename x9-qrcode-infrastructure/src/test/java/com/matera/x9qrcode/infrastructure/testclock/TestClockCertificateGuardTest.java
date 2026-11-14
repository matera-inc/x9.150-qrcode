/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testclock;

import com.matera.x9qrcode.app.service.PrivateKeyRetriever;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR-0033: only a deployment signing with a bundled demo or test-CA certificate may move its clock.
 */
class TestClockCertificateGuardTest {

    static X509Certificate certificate(String resource, String type, String password, String alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(type);
        try (InputStream in = TestClockCertificateGuardTest.class.getResourceAsStream(resource)) {
            keyStore.load(in, password.toCharArray());
        }
        return (X509Certificate) keyStore.getCertificate(alias);
    }

    static X509Certificate demoCertificate() throws Exception {
        return certificate("/certificate/x9-demo.jks", "JKS", "x9demo123", "x9-private-keystore");
    }

    static X509Certificate untrustedCertificate() throws Exception {
        return certificate("/certificate/x9-test-untrusted-payer.p12", "PKCS12", "x9test123", "x9-test-untrusted-payer");
    }

    /** S2.1-AC1: the bundled demo and test-CA certificates satisfy the guard. */
    @ParameterizedTest
    @CsvSource({
        "/certificate/x9-demo.jks,           JKS,    x9demo123, x9-private-keystore",
        "/certificate/x9-test-ca.p12,        PKCS12, x9test123, x9-test-ca",
        "/certificate/x9-test-payer.p12,     PKCS12, x9test123, x9-test-payer",
        "/certificate/x9-test-payer-ec.p12,  PKCS12, x9test123, x9-test-payer-ec"
    })
    void aBundledCertificateIsAccepted(String resource, String type, String password, String alias) throws Exception {
        X509Certificate cert = certificate(resource, type, password, alias);

        assertDoesNotThrow(() -> TestClockCertificateGuard.requireBundled(cert));
    }

    /** S2.1-AC2: any other certificate is refused, even one issued by a CA in this repository's test PKI. */
    @ParameterizedTest
    @CsvSource({
        "/certificate/x9-test-untrusted-payer.p12, x9-test-untrusted-payer",
        "/certificate/x9-test-untrusted-ca.p12,    x9-test-untrusted-ca",
        "/certificate/x9-test-payer-revoked.p12,   x9-test-payer-revoked"
    })
    void anyOtherCertificateIsRefused(String resource, String alias) throws Exception {
        X509Certificate cert = certificate(resource, "PKCS12", "x9test123", alias);

        IllegalStateException refused =
            assertThrows(IllegalStateException.class, () -> TestClockCertificateGuard.requireBundled(cert));
        assertThat(refused.getMessage()).contains("X9_TESTING_CLOCKCONTROL").contains("not a bundled");
    }

    @Test
    void noCertificateIsRefused() {
        assertThrows(IllegalStateException.class, () -> TestClockCertificateGuard.requireBundled(null));
    }

    private ApplicationContextRunner runnerSigningWith(X509Certificate cert) {
        PrivateKeyRetriever retriever = mock(PrivateKeyRetriever.class);
        when(retriever.getCertificate()).thenReturn(cert);
        return new ApplicationContextRunner()
            .withUserConfiguration(TestClockConfiguration.class)
            .withBean(PrivateKeyRetriever.class, () -> retriever)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("x9.testing.clock-listen=127.0.0.1:0");
    }

    /** S2.1-AC2: with the flag on and a certificate that is not bundled, the application refuses to start. */
    @Test
    void theApplicationRefusesToStartWithARealCertificate() throws Exception {
        runnerSigningWith(untrustedCertificate())
            .withPropertyValues("x9.testing.clock-control=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not a bundled demo or test-CA certificate");
            });
    }

    /** S2.1-AC3: with the flag off, the clock does not exist, whatever the certificate. */
    @Test
    void withTheFlagOffNothingIsCreated() throws Exception {
        runnerSigningWith(untrustedCertificate())
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(TestClockServer.class);
            });
    }

    /** S2.1-AC1: with the flag on and the demo certificate, the clock listener starts. */
    @Test
    void theApplicationStartsWithTheDemoCertificate() throws Exception {
        runnerSigningWith(demoCertificate())
            .withPropertyValues("x9.testing.clock-control=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(TestClockServer.class);
            });
    }

    @Test
    void theClockListenerMustNotBeTheApiPort() throws Exception {
        runnerSigningWith(demoCertificate())
            .withPropertyValues("x9.testing.clock-control=true", "server.port=8080",
                "x9.testing.clock-listen=0.0.0.0:8080")
            .run(context -> assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("must not be the API port"));
    }

}
