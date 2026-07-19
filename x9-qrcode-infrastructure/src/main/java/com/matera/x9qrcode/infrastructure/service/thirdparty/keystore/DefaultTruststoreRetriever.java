/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.keystore;

import com.matera.x9qrcode.app.service.KeystoreRetriever;
import com.matera.x9qrcode.app.service.TruststoreRetriever;
import com.matera.x9qrcode.infrastructure.configuration.property.keystore.KeystoreProperties;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;

import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.TrustManagerFactory;

/**
 * A {@link KeystoreRetriever} that, for the cases where the keystore
 * needs to be loaded from an {@code InputStream}, relies on a Spring's
 * {@code Resource} abstraction.
 */
@Slf4j
public class DefaultTruststoreRetriever extends AbstractKeystoreRetriever implements TruststoreRetriever, AutoCloseable {

    public static final String ERROR_TRYING_TO_READ_ITS_CREATION_TIMESTAMP =
        "Error trying to read its creation timestamp";
    private static final String ERROR_TRYING_TO_GET_FILE_BY_PATH = "Error trying to get file by resource";

    protected final Map<String, X509Certificate> trustedCertificatesCache = new ConcurrentHashMap<>();
    private final ExecutorService cacheExecutor = Executors.newSingleThreadExecutor();

    public DefaultTruststoreRetriever(KeystoreProperties keystoreProperties,
                                      String keystoreDescription,
                                      ResourceLoader resourceLoader) {
        super(keystoreProperties, keystoreDescription, resourceLoader);
    }

    @Override
    public X509Certificate getCertificate(String alias) {

        if (keystoreProperties.isCacheCertificatesEnabled() && trustedCertificatesCache.containsKey(alias)) {
            log.debug("Returning cached certificate {} for keystore {}", alias, keystoreDescription);
            return trustedCertificatesCache.get(alias);
        }

        try {
            final Certificate cert = keystore.getCertificate(alias);
            if (cert instanceof X509Certificate x509Certificate) {
                // We did not cache the certificate here because it could conflict with the getAllTrustedCertificates method
                return x509Certificate;
            }
            log.warn("Certificate with alias {} it's not an instanceof X509Certificate. It is {}", alias, getClassName(cert));
            return null;
        } catch (KeyStoreException e) {
            throw new RuntimeException("Error when getting certificate from alias %s".formatted(alias), e);
        }
    }

    @Override
    public List<X509Certificate> getAllTrustedCertificates() {

        if (keystoreProperties.isCacheCertificatesEnabled() && !trustedCertificatesCache.isEmpty()) {
            log.debug("Returning cached certificates for keystore {}", keystoreDescription);
            return new ArrayList<>(trustedCertificatesCache.values());
        }

        Map<String, X509Certificate> certificates = new HashMap<>();
        List<String> aliases = getAliases();

        for (String alias : aliases) {
            final X509Certificate cert = getCertificate(alias);
            if (cert != null) {
                certificates.put(alias, cert);
            }
        }

        if (keystoreProperties.isCacheCertificatesEnabled()) {
            cacheExecutor.execute(() -> {
                log.debug("Caching certificates for keystore {}", keystoreDescription);
                trustedCertificatesCache.putAll(certificates);
            });
        }

        return new ArrayList<>(certificates.values());
    }

    @Override
    public void initTrustManagerFactory(TrustManagerFactory trustManagerFactory) {
        if (Objects.isNull(trustManagerFactory)) {
            throw new IllegalArgumentException("TrustManagerFactory must not be null");
        }

        try {
            trustManagerFactory.init(keystore);
        } catch (Exception e) {
            throw new RuntimeException("Could not initialize TrustManagerFactory for keystore " + keystoreDescription, e);
        }
    }

    @Override
    void clearCache() {
        trustedCertificatesCache.clear();
        entriesCache.clear();
    }

    @Override
    public void close() {
        cacheExecutor.shutdown();
    }

}
