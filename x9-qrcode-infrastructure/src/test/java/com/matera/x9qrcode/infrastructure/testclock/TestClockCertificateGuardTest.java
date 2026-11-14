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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR-0033: only a deployment signing with a certificate bundled with this repository may move its
 * clock. Every bundled certificate counts, the revoked and untrusted fixtures included, because their
 * private keys are public; the negative case is a certificate generated inside the test.
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

    /**
     * A self-signed certificate generated here, now, by the JDK's own {@code keytool}, with a key that
     * exists only in a temporary directory: what a deployment with keys of its own looks like to the
     * guard. Its fingerprint differs on every run, so it can never be on the compiled-in list.
     */
    static X509Certificate freshCertificate() throws Exception {
        Path dir = Files.createTempDirectory("x9-fresh-cert");
        Path keystore = dir.resolve("fresh.p12");
        try {
            Process keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "fresh", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=payee.example.com, O=Example Payee", "-validity", "365",
                "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", "fresh-pass", "-keypass", "fresh-pass")
                .redirectErrorStream(true)
                .start();
            String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!keytool.waitFor(60, TimeUnit.SECONDS) || keytool.exitValue() != 0) {
                throw new IllegalStateException("keytool could not generate a certificate: " + output);
            }

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                keyStore.load(in, "fresh-pass".toCharArray());
            }
            return (X509Certificate) keyStore.getCertificate("fresh");
        } finally {
            Files.deleteIfExists(keystore);
            Files.deleteIfExists(dir);
        }
    }

    /** Every keystore entry with a private key that this repository ships: resource, type, password, alias. */
    private static final String[][] BUNDLED = {
        {"/certificate/x9-demo.jks", "JKS", "x9demo123", "x9-private-keystore"},
        {"/certificate/x9-test-ca.p12", "PKCS12", "x9test123", "x9-test-ca"},
        {"/certificate/x9-test-payer.p12", "PKCS12", "x9test123", "x9-test-payer"},
        {"/certificate/x9-test-payer-ec.p12", "PKCS12", "x9test123", "x9-test-payer-ec"},
        {"/certificate/x9-test-payer-revoked.p12", "PKCS12", "x9test123", "x9-test-payer-revoked"},
        {"/certificate/x9-test-untrusted-ca.p12", "PKCS12", "x9test123", "x9-test-untrusted-ca"},
        {"/certificate/x9-test-untrusted-payer.p12", "PKCS12", "x9test123", "x9-test-untrusted-payer"},
    };

    /**
     * S2.1-AC1: every certificate bundled with this repository satisfies the guard — the revoked and
     * untrusted fixtures included, because their private keys are just as public as the others'.
     */
    @ParameterizedTest
    @CsvSource({
        "/certificate/x9-demo.jks,                  JKS,    x9demo123, x9-private-keystore",
        "/certificate/x9-test-ca.p12,               PKCS12, x9test123, x9-test-ca",
        "/certificate/x9-test-payer.p12,            PKCS12, x9test123, x9-test-payer",
        "/certificate/x9-test-payer-ec.p12,         PKCS12, x9test123, x9-test-payer-ec",
        "/certificate/x9-test-payer-revoked.p12,    PKCS12, x9test123, x9-test-payer-revoked",
        "/certificate/x9-test-untrusted-ca.p12,     PKCS12, x9test123, x9-test-untrusted-ca",
        "/certificate/x9-test-untrusted-payer.p12,  PKCS12, x9test123, x9-test-untrusted-payer"
    })
    void aBundledCertificateIsAccepted(String resource, String type, String password, String alias) throws Exception {
        X509Certificate cert = certificate(resource, type, password, alias);

        assertDoesNotThrow(() -> TestClockCertificateGuard.requireBundled(cert));
    }

    /** The compiled-in list is exactly the bundled certificates: nothing missing, nothing extra. */
    @Test
    void theListIsExactlyTheBundledCertificates() throws Exception {
        Set<String> bundled = new HashSet<>();
        for (String[] entry : BUNDLED) {
            bundled.add(TestClockCertificateGuard.sha256(certificate(entry[0], entry[1], entry[2], entry[3])));
        }

        assertThat(TestClockCertificateGuard.BUNDLED_FINGERPRINTS).containsExactlyInAnyOrderElementsOf(bundled);
    }

    /** S2.1-AC2: a certificate that is not bundled — here, one this test has just generated — is refused. */
    @Test
    void anyOtherCertificateIsRefused() throws Exception {
        X509Certificate cert = freshCertificate();

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
        runnerSigningWith(freshCertificate())
            .withPropertyValues("x9.testing.clock-control=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not a bundled certificate");
            });
    }

    /** S2.1-AC3: with the flag off, the clock does not exist, whatever the certificate. */
    @Test
    void withTheFlagOffNothingIsCreated() throws Exception {
        runnerSigningWith(freshCertificate())
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
