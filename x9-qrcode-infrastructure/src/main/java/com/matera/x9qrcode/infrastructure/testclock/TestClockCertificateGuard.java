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
        "c9e0987d26423d15eb173ea9f40e06f64a6b8434a70c781fea774603d839ea85",
        // src/test/resources/certificate/x9-test-payer-no-revocation.p12 — leaf of the test CA naming no CRL or OCSP (BUG-003)
        "f38f6315e0b4292e4b18ef7ab41275d67376c231102cf2f90a9c93f2c0b255f3",
        // src/test/resources/certificate/x9-test-payer-crl-unreachable.p12 — leaf with an unreachable CRL (BUG-003)
        "8f8313bb8f5e244ea5da488d494bfa36c526fc1026d7c587da8dad11858382c9",
        // src/test/resources/certificate/x9-test-payer-ocsp-unreachable.p12 — leaf with an unreachable OCSP responder (BUG-003)
        "c8a9cbc56445d06186396fe4f06487a125dc40b1dc626168c1edd4f320be1afd",
        // src/test/resources/certificate/x9-test-payer-both-unreachable.p12 — leaf with both unreachable (BUG-003)
        "a08335e6d2da20d12050cc50bec23bd866aec5e961ec37890bb4ed7bf4bcfce1",
        // src/test/resources/certificate/x9-test-payer-crl-unreadable.p12 — leaf whose CRL is not a CRL
        "932511c2e41a398f833082dc382397214c56190821a07d727c6f18e54f2c1987",
        // src/test/resources/certificate/x9-test-payer-crl-unreadable-payload.p12 — the same, for the payload endpoint
        "33f87ee4a486580f29b3206e26485c23416944a9efa1c5290a6398674383a8a6",
        // src/test/resources/certificate/x9-test-payer-crl-unreadable-ocsp-unreachable.p12 — that CRL, plus an unreachable OCSP responder
        "2367e5f868d04bfc6c95c8e7ed47b894add82c20d1e11bf88c49ae3aa6e7c9ab",
        // src/test/resources/certificate/x9-test-payer-crl-unreadable-ocsp-unreachable-payload.p12 — the same, for the payload endpoint
        "62c5a14884abcf0c7af25e335b669b62966ecac688bc01951a7f4c89adc469cd",
        // src/test/resources/certificate/x9-test-ca-digital-signature.pem — the test CA re-issued to itself with
        // digitalSignature; its key is x9-test-ca.p12's, so it is as public as the others
        "8a93437dd246941e1cf6ad761808b87188fb69d52bd4caecc649e2b34bdc73cf"
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
