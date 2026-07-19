/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration;

import com.matera.x9qrcode.app.service.PrivateKeyRetriever;
import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;
import com.matera.x9qrcode.infrastructure.service.thirdparty.jwk.CertificateChainTransformer;
import com.matera.x9qrcode.infrastructure.service.thirdparty.jwk.JwkSetFacadeBean;
import com.matera.x9qrcode.infrastructure.service.thirdparty.keystore.KeystoreChangedEvent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class JwkConfiguration {

    private JwkSetFacadeBean jwkSetFacadeBean;

    @Autowired
    private X9Properties properties;

    @Autowired
    private CertificateChainTransformer transformer;

    @Autowired(required = false)
    private PrivateKeyRetriever privateKeyRetriever;

    @Bean
    public JwkSetFacadeBean createJwkContextFacadeBean(PrivateKeyRetriever privateKeyRetriever, CertificateChainTransformer transformer) {
        return createAndUpdateJwsContextFacadeBean(privateKeyRetriever, transformer);
    }

    @EventListener
    public void handleKeystoreChange(KeystoreChangedEvent keystoreChangedEventDTO) {
        String keystoreAlias = keystoreChangedEventDTO.getKeystoreAlias();
        if (nonNull(keystoreAlias)) {
            if (privateKeyRetriever.getKeystoreAlias().equals(keystoreAlias)) {
                log.info("Updating JWS Signer due to update of keystore regarding {}", keystoreAlias);
                createAndUpdateJwsContextFacadeBean(privateKeyRetriever, transformer);
            } else {
                // TODO: QRCODE-1358 we should implement this reload properly instead of just logging a warning
                log.warn("Ignoring truststore change event for keystore alias {}. Should restart application !", keystoreAlias);
            }
        }
    }

    private JwkSetFacadeBean createAndUpdateJwsContextFacadeBean(PrivateKeyRetriever privateKeyRetriever,
                                                                 CertificateChainTransformer transformer) {
        if (isNull(jwkSetFacadeBean)) {
            jwkSetFacadeBean = new JwkSetFacadeBean(properties);
        }

        jwkSetFacadeBean.fill(privateKeyRetriever, transformer);

        return jwkSetFacadeBean;
    }

}
