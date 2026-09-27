/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration;

import com.matera.x9qrcode.infrastructure.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps this deployment off jasypt's GCM encryptor, which carries an unfixable advisory.
 *
 * <p>CVE-2026-9370 / GHSA-jgj7-c8vj-w563 is a predictable salt in
 * {@code SimpleGCMConfig.getSecretKeySaltGenerator}. It affects every published release — 4.0.4 is
 * the newest and there is no 4.0.5 — so there is nothing to upgrade to. What there is, is a choice
 * of encryptor, and we are on the other one.
 *
 * <p>{@code StringEncryptorBuilder} picks GCM if and only if one of three properties is non-null:
 *
 * <pre>
 *   private boolean isGCMConfig() {
 *       return configProps.getGcmSecretKeyString()   != null
 *           || configProps.getGcmSecretKeyLocation() != null
 *           || configProps.getGcmSecretKeyPassword() != null;
 *   }
 * </pre>
 *
 * <p>We set none of them and configure a PBE algorithm instead, so the builder takes the PBE branch
 * and the vulnerable class is never constructed. That is the whole of our protection, which is
 * exactly why it deserves a test rather than a comment: adding any one of those three properties
 * would move us onto the affected code silently, and the alert has been dismissed on the grounds
 * that we are not on it.
 *
 * <p>Note this asserts the application's own configuration. A deployment that sets one of these as
 * an environment variable would still land on the GCM path — see the Helm chart's
 * {@code secrets.configEncryption} and the "Known advisories" section of SECURITY.md.
 */
class JasyptStaysOffTheGcmPathTest extends AbstractIntegrationTest {

    @Autowired
    private Environment environment;

    /** The three properties that, individually, select the vulnerable encryptor. */
    @ParameterizedTest
    @ValueSource(strings = {
        "jasypt.encryptor.gcm-secret-key-string",
        "jasypt.encryptor.gcm-secret-key-location",
        "jasypt.encryptor.gcm-secret-key-password"
    })
    void noGcmSecretKeyPropertyIsSet(String property) {
        assertNull(environment.getProperty(property),
                "setting " + property + " moves this deployment onto SimpleGCMConfig, which has a "
                    + "predictable salt (CVE-2026-9370) and no fixed release. If this is deliberate, "
                    + "the dismissed Dependabot alert must be reopened.");
    }

    /** And the positive statement: we are on a password-based encryptor, with a modern algorithm. */
    @Test
    void thePbeAlgorithmIsTheOneConfigured() {
        String algorithm = environment.getProperty("jasypt.encryptor.algorithm");

        assertTrue("PBEWithHmacSHA512AndAES_256".equals(algorithm),
                "expected the PBE branch of StringEncryptorBuilder, but the algorithm is: " + algorithm);
    }

}
