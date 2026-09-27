/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.jwk;

import com.matera.x9qrcode.app.service.PrivateKeyRetriever;
import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyOperation;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.Security;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import static com.matera.x9qrcode.app.service.SignatureConstants.X9_KEY_ID;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

@Getter
@RequiredArgsConstructor
public class JwkSetFacadeBean {

    private final X9Properties properties;

    private JWK jwkInformation;
    private JWSSigner signer;

    public void fill(PrivateKeyRetriever jwsEncryptionKeystoreRetriever,
                     CertificateChainTransformer transformer) {
        this.jwkInformation = createAndUpdateJwkInformation(jwsEncryptionKeystoreRetriever, transformer);
        this.signer = createAndUpdateJwsSigner(jwsEncryptionKeystoreRetriever);
    }

    /**
     * This deployment's own key, as a JWK — built from whichever key type the certificate holds.
     *
     * <p>ANSI X9.150-2026 names no signature algorithm: {@code alg} must come from the X9-approved
     * suite (SD-34), and the standard's own Annex A examples sign with {@code ES256}. So an X9-issued
     * identity may perfectly well be EC, and a deployment holding one has to be able to run. This
     * parsed the certificate as RSA unconditionally, so such a deployment could not even start.
     */
    private JWK createAndUpdateJwkInformation(PrivateKeyRetriever privateKeyRetriever,
                                              CertificateChainTransformer transformer){
        KeyStore.PrivateKeyEntry privateKeyEntry = privateKeyRetriever.getPrivateKeyEntry();
        X509Certificate cert = privateKeyRetriever.getCertificate();

        try {
            Certificate[] chain = privateKeyRetriever.getCertificateChain();
            PrivateKey privateKey = privateKeyEntry.getPrivateKey();
            JWK certificateKey = JWK.parse(cert);
            JWSAlgorithm algorithm = signingAlgorithmFor(certificateKey);

            Base64URL sha256Thumbprint = Base64URL.encode(DigestUtils.getSha256Digest().digest(cert.getEncoded()));
            Base64URL sha1Thumbprint = Base64URL.encode(DigestUtils.getSha1Digest().digest(cert.getEncoded()));
            List<com.nimbusds.jose.util.Base64> certChain = transformer.apply(chain);

            return switch (certificateKey) {
                case RSAKey rsaKey -> new RSAKey.Builder(rsaKey)
                    .privateKey(privateKey)
                    .keyID(X9_KEY_ID)
                    .algorithm(algorithm)
                    .x509CertSHA256Thumbprint(sha256Thumbprint)
                    .x509CertThumbprint(sha1Thumbprint)
                    .x509CertChain(certChain)
                    .keyUse(KeyUse.SIGNATURE)
                    .keyOperations(Set.of(KeyOperation.VERIFY))
                    .build();
                case ECKey ecKey -> new ECKey.Builder(ecKey)
                    .privateKey(privateKey)
                    .keyID(X9_KEY_ID)
                    .algorithm(algorithm)
                    .x509CertSHA256Thumbprint(sha256Thumbprint)
                    .x509CertThumbprint(sha1Thumbprint)
                    .x509CertChain(certChain)
                    .keyUse(KeyUse.SIGNATURE)
                    .keyOperations(Set.of(KeyOperation.VERIFY))
                    .build();
                default -> throw new IllegalStateException(
                    "Unsupported signing key type: %s".formatted(certificateKey.getKeyType()));
            };
        } catch (JOSEException | CertificateEncodingException | KeyStoreException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    /**
     * The configured {@code alg}, checked against the key that must actually produce the signature.
     *
     * <p>A mismatch is a misconfiguration, not something to paper over: silently substituting an
     * algorithm would sign with something the operator did not choose, and silently continuing would
     * fail later at the first signature with a far less obvious error. Failing at startup names both
     * halves of the contradiction while somebody is still looking at the config.
     */
    private JWSAlgorithm signingAlgorithmFor(JWK certificateKey) {
        JWSAlgorithm configured = JWSAlgorithm.parse(properties.getCertificate().getJwkAlgorithm());

        List<JWSAlgorithm> permitted = KeyType.EC.equals(certificateKey.getKeyType())
            ? List.of(JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512, JWSAlgorithm.ES256K)
            : List.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
                      JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512);

        if (!permitted.contains(configured)) {
            throw new IllegalStateException(
                ("Configured x9.certificate.jwk-algorithm is %s but the signing key is %s. "
                 + "Use one of: %s.")
                    .formatted(configured, certificateKey.getKeyType(), permitted));
        }

        return configured;
    }

    private JWSSigner createAndUpdateJwsSigner(PrivateKeyRetriever jwsEncryptionKeystoreRetriever) {
        PrivateKey privateKey = jwsEncryptionKeystoreRetriever.getPrivateKey();

        JWSSigner signer;

        try {
            signer = switch (privateKey) {
                case ECPrivateKey ecPrivateKey -> new ECDSASigner(ecPrivateKey);
                case RSAPrivateKey rsaPrivateKey -> new RSASSASigner(rsaPrivateKey);
                default -> new RSASSASigner(privateKey);
            };
        } catch (JOSEException e) {
            throw new IllegalStateException("Unable to build a JWS signer for the configured key", e);
        }

        fillSignerCustomProvider(signer);

        return signer;
    }

    private void fillSignerCustomProvider(JWSSigner signer) {
        if (isNotBlank(properties.getCertificate().getCustomProvider())) {
            signer.getJCAContext().setProvider(Security.getProvider(properties.getCertificate().getCustomProvider()));
        }
    }

}
