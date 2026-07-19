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
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyOperation;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
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

    private JWK createAndUpdateJwkInformation(PrivateKeyRetriever privateKeyRetriever,
                                              CertificateChainTransformer transformer){
        KeyStore.PrivateKeyEntry privateKeyEntry = privateKeyRetriever.getPrivateKeyEntry();
        X509Certificate cert = privateKeyRetriever.getCertificate();

        try {
            Certificate[] chain = privateKeyRetriever.getCertificateChain();

            return new RSAKey.Builder(RSAKey.parse(cert))
                .privateKey(privateKeyEntry.getPrivateKey())
                .keyID(X9_KEY_ID)
                .algorithm(JWSAlgorithm.parse(properties.getCertificate().getJwkAlgorithm()))
                .x509CertSHA256Thumbprint(Base64URL.encode(DigestUtils.getSha256Digest().digest(cert.getEncoded())))
                .x509CertThumbprint(Base64URL.encode(DigestUtils.getSha1Digest().digest(cert.getEncoded())))
                .x509CertChain(transformer.apply(chain))
                .keyUse(KeyUse.SIGNATURE)
                .keyOperations(Set.of(KeyOperation.VERIFY))
                .build();
        } catch (JOSEException | CertificateEncodingException | KeyStoreException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    private JWSSigner createAndUpdateJwsSigner(PrivateKeyRetriever jwsEncryptionKeystoreRetriever) {
        var signer = new RSASSASigner(jwsEncryptionKeystoreRetriever.getPrivateKey());
        fillSignerCustomProvider(signer);
        return signer;
    }

    private void fillSignerCustomProvider(RSASSASigner signer) {
        if (isNotBlank(properties.getCertificate().getCustomProvider())) {
            signer.getJCAContext().setProvider(Security.getProvider(properties.getCertificate().getCustomProvider()));
        }
    }

}
