/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration;

import com.matera.x9qrcode.app.service.PrivateKeyRetriever;
import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;
import com.matera.x9qrcode.infrastructure.service.thirdparty.keystore.DefaultPrivateKeyRetriever;
import com.matera.x9qrcode.infrastructure.service.thirdparty.keystore.DefaultTruststoreRetriever;
import com.matera.x9qrcode.infrastructure.service.thirdparty.keystore.KeystoreRetrieverUpdater;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

@Configuration(proxyBeanMethods = false)
public class KeystoresConfiguration {

    public static final String PRIVATE_KEY_KEYSTORE_DESCRIPTION = "X9-private-key";
    public static final String TRUSTSTORE_DESCRIPTION = "X9-truststore";

    @Bean
    public DefaultTruststoreRetriever truststoreRetriever(X9Properties x9Properties, ResourceLoader resourceLoader) {
        return new DefaultTruststoreRetriever(x9Properties.getCertificate().getTruststore(),
            TRUSTSTORE_DESCRIPTION, resourceLoader);
    }

    @Bean
    public PrivateKeyRetriever privateKeyRetriever(X9Properties x9Properties, ResourceLoader resourceLoader) {
        return new DefaultPrivateKeyRetriever(x9Properties.getCertificate().getPrivateKeystore(),
            PRIVATE_KEY_KEYSTORE_DESCRIPTION, resourceLoader, truststoreRetriever(x9Properties, resourceLoader));
    }

    @Bean
    public KeystoreRetrieverUpdater jwsEncryptionRetrieverUpdater() {
        return new KeystoreRetrieverUpdater();
    }

}
