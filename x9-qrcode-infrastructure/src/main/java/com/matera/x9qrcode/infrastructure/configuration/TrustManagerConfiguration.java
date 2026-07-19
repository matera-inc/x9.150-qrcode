/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration;

import com.matera.x9qrcode.app.service.TruststoreRetriever;
import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;
import com.matera.x9qrcode.infrastructure.service.thirdparty.keystore.TrustManagersFacadeBean;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ResourceLoader;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

@Slf4j
@Configuration
@Order(200)
public class TrustManagerConfiguration {

    private static final KeyStore DEFAULT_JVM_TM = null;

    private TrustManagerFactory trustManagerFactory;
    private TrustManagersFacadeBean trustManagersFacadeBean;

    @Autowired
    private ResourceLoader resourceLoader;
    
    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;
    
    @Autowired
    private X9Properties x9Properties;
    
    @PostConstruct
    private void fillTmf() {
        trustManagerFactory = getTrustManagerFactoryInstance();
    }
    
    @Bean
    public TrustManagersFacadeBean createCustomTrustManagerBean(TruststoreRetriever truststoreRetriever) {
        trustManagersFacadeBean = new TrustManagersFacadeBean(getDefaultTrustManager(),
            createLocalTrustManager(truststoreRetriever, trustManagerFactory));

        return trustManagersFacadeBean;
    }

    private TrustManagerFactory getTrustManagerFactoryInstance() {
        try {
            return TrustManagerFactory.getInstance(x9Properties.getCertificate().getTruststore().getTrustManagerFactoryAlgorithm());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not create TrustManagerFactory instance", e);
        }
    }

    private X509TrustManager getDefaultTrustManager() {
        try {
            trustManagerFactory.init((KeyStore) null);
            return getX509TrustManager(trustManagerFactory);
        } catch (KeyStoreException e) {
            throw new IllegalStateException("Could not load default Trust Manager", e);
        }
    }
    
    private X509TrustManager createLocalTrustManager(TruststoreRetriever truststoreRetriever,
                                                     TrustManagerFactory trustManagerFactory) {
        truststoreRetriever.initTrustManagerFactory(trustManagerFactory);
        return getX509TrustManager(trustManagerFactory);
    }
    
    private X509TrustManager getX509TrustManager(TrustManagerFactory trustManagerFactory) {
        for (TrustManager tm : trustManagerFactory.getTrustManagers()) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        throw new IllegalStateException("No X509TrustManager found in TrustManagerFactory");
    }
    
}
