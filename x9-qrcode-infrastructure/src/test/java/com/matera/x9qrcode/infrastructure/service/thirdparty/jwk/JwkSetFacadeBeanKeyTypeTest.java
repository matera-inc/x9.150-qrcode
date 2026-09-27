/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.jwk;

import com.matera.x9qrcode.app.service.PrivateKeyRetriever;
import com.matera.x9qrcode.infrastructure.configuration.property.CertificateProperties;
import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This deployment's own signing identity, whichever key type X9 issued it.
 *
 * <p>ANSI X9.150-2026 names no signature algorithm — {@code alg} comes from the X9-approved suite
 * (SD-34), and the standard's own Annex A examples use {@code ES256}. An EC identity is therefore an
 * ordinary thing to be issued. This class used to parse our certificate as RSA and build an
 * {@code RSASSASigner} unconditionally, so a deployment holding one could not start at all.
 */
class JwkSetFacadeBeanKeyTypeTest {

    private static final String STOREPASS = "x9test123";

    private static X9Properties propertiesFor(String algorithm) {
        CertificateProperties certificate = new CertificateProperties();
        certificate.setJwkAlgorithm(algorithm);

        X9Properties properties = new X9Properties();
        properties.setCertificate(certificate);

        return properties;
    }

    /**
     * A keystore-backed {@link PrivateKeyRetriever}, so the facade is exercised against real keys
     * rather than mocks — the whole question here is what a real key's type does to the output.
     */
    private record KeystorePayer(String alias, KeyStore.PrivateKeyEntry entry) implements PrivateKeyRetriever {

        static KeystorePayer of(String keystore, String alias) throws Exception {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = JwkSetFacadeBeanKeyTypeTest.class
                    .getResourceAsStream("/certificate/" + keystore)) {
                store.load(in, STOREPASS.toCharArray());
            }

            return new KeystorePayer(alias, (KeyStore.PrivateKeyEntry)
                store.getEntry(alias, new KeyStore.PasswordProtection(STOREPASS.toCharArray())));
        }

        @Override
        public String getPrivateKeyAlias() {
            return alias;
        }

        @Override
        public PrivateKey getPrivateKey() {
            return entry.getPrivateKey();
        }

        @Override
        public KeyStore.PrivateKeyEntry getPrivateKeyEntry() {
            return entry;
        }

        @Override
        public X509Certificate getCertificate() {
            return (X509Certificate) entry.getCertificate();
        }

        @Override
        public X509Certificate[] getCertificateChain() {
            return (X509Certificate[]) entry.getCertificateChain();
        }

        @Override
        public void initKeyManagerFactory(KeyManagerFactory keyManagerFactory) {
            throw new UnsupportedOperationException("TLS is not what this test is about");
        }

        @Override
        public String getDescription() {
            return "test keystore";
        }

        @Override
        public String getKeystoreAlias() {
            return alias;
        }

        @Override
        public List<String> getAliases() {
            return List.of(alias);
        }

        @Override
        public boolean reloadIfNotUpToDate() {
            return false;
        }
    }

    private static JwkSetFacadeBean filled(String algorithm, String keystore, String alias) throws Exception {
        JwkSetFacadeBean facade = new JwkSetFacadeBean(propertiesFor(algorithm));

        facade.fill(KeystorePayer.of(keystore, alias), new CertificateChainTransformer());

        return facade;
    }

    @Test
    void anEcIdentityProducesAnEcJwkAndAnEcdsaSigner() throws Exception {
        JwkSetFacadeBean facade = filled("ES256", "x9-test-payer-ec.p12", "x9-test-payer-ec");

        assertEquals(KeyType.EC, facade.getJwkInformation().getKeyType());
        assertEquals(JWSAlgorithm.ES256, facade.getJwkInformation().getAlgorithm());
        assertInstanceOf(ECDSASigner.class, facade.getSigner());
    }

    @Test
    void anRsaIdentityStillProducesAnRsaJwkAndAnRsassaSigner() throws Exception {
        JwkSetFacadeBean facade = filled("PS512", "x9-test-payer.p12", "x9-test-payer");

        assertEquals(KeyType.RSA, facade.getJwkInformation().getKeyType());
        assertEquals(JWSAlgorithm.PS512, facade.getJwkInformation().getAlgorithm());
        assertInstanceOf(RSASSASigner.class, facade.getSigner());
    }

    /**
     * A configured algorithm the key cannot produce is a misconfiguration, and it fails at startup
     * naming both halves.
     *
     * <p>Substituting an algorithm silently would sign with something the operator did not choose;
     * carrying on regardless would fail later, at the first signature, with a far less obvious
     * error. Neither is kind to whoever swapped the keystore and forgot the algorithm beside it.
     */
    @Test
    void anAlgorithmTheKeyCannotProduceFailsImmediatelyAndSaysWhy() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> filled("PS512", "x9-test-payer-ec.p12", "x9-test-payer-ec"));

        assertTrue(thrown.getMessage().contains("PS512"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("EC"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("ES256"),
            "name an algorithm that would work: " + thrown.getMessage());
    }

    @Test
    void theSameGuardCatchesAnEcAlgorithmOnAnRsaKey() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> filled("ES256", "x9-test-payer.p12", "x9-test-payer"));

        assertTrue(thrown.getMessage().contains("ES256") && thrown.getMessage().contains("RSA"),
            thrown.getMessage());
    }

}
