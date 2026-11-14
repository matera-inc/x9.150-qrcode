/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testclock;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * Refuses the test clock unless this deployment signs with a certificate that ships with this
 * repository (ADR-0033). The fingerprints are compiled in: a subject name or a configuration value
 * cannot satisfy the guard, so a deployment with real keys cannot move its clock.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TestClockCertificateGuard {

    /**
     * SHA-256 of the DER encoding of every certificate whose private key is public, because it is
     * committed to this repository — all of them, the revoked and untrusted fixtures included: a key
     * anyone can read is a test key, whatever the certificate says about it. Compare with
     * {@code keytool -list -v}. A certificate generated anywhere else, real or not, is refused.
     */
    static final Set<String> BUNDLED_FINGERPRINTS = Set.of(
        // src/main/resources/certificate/x9-demo.jks — the self-signed demo identity (CN=x9.matera.com, OU=Demo NonProduction)
        "67e9eae59581e99c81d3744fe086042f7363e13c2d15e05113011d2d76e5ed1b",
        // src/test/resources/certificate/x9-test-ca.p12 — the test root CA
        "795ff346e9689636e9ba68917fe9f12db9e5e3a43a0b9fe2a39026275859292a",
        // src/test/resources/certificate/x9-test-payer.p12 — RSA leaf of the test CA
        "e08b164ebd84a0a03701d20071bfdd4d7cd8f3185958201c577e6015d51e671a",
        // src/test/resources/certificate/x9-test-payer-ec.p12 — EC leaf of the test CA
        "19241b109b737421705d65e7d6cfc2fca7bc9425f0f263fe04643daa70bcf518",
        // src/test/resources/certificate/x9-test-payer-revoked.p12 — leaf of the test CA, listed in its CRL
        "f449774d768f3324ac23b99ce858663073fe455041494754075151e3b437f875",
        // src/test/resources/certificate/x9-test-untrusted-ca.p12 — a second root, outside the test truststore
        "e95c47df5b6489ebfd1507e8b569369d92771a07d905a1865c0e653966fc676d",
        // src/test/resources/certificate/x9-test-untrusted-payer.p12 — leaf of the untrusted root
        "c9e0987d26423d15eb173ea9f40e06f64a6b8434a70c781fea774603d839ea85"
    );

    /**
     * @throws IllegalStateException unless {@code signingCertificate} is one bundled with this repository.
     */
    public static void requireBundled(X509Certificate signingCertificate) {
        if (Objects.isNull(signingCertificate)) {
            throw new IllegalStateException(
                "X9_TESTING_CLOCKCONTROL is on, but this deployment has no signing certificate to check");
        }

        String fingerprint = sha256(signingCertificate);
        if (!BUNDLED_FINGERPRINTS.contains(fingerprint)) {
            throw new IllegalStateException((
                "X9_TESTING_CLOCKCONTROL is on, but the signing certificate (%s, SHA-256 %s) is not a bundled "
                    + "certificate (one whose private key ships with this repository). A deployment with real keys "
                    + "cannot move its clock: turn "
                    + "X9_TESTING_CLOCKCONTROL off.")
                .formatted(signingCertificate.getSubjectX500Principal().getName(), fingerprint));
        }
    }

    static String sha256(X509Certificate certificate) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException("Could not fingerprint the signing certificate", e);
        }
    }

}
