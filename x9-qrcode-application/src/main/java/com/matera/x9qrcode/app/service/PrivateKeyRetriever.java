/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.service;

import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;

/**
 * Interface for retrieving default private key and related keystore information.
 * <p>
 * Provides access to the alias, private key, key entry, certificate, and certificate chain
 * associated with a private key, as well as initialization of the KeyManagerFactory.
 * </p>
 * Implementations must ensure secure and correct access to keystore data.
 */
public interface PrivateKeyRetriever extends KeystoreRetriever {

    /**
     * Returns the default alias of the private key in the keystore.
     * @return the private key alias
     */
    String getPrivateKeyAlias();

    /**
     * Retrieves the private key from the keystore considering default alias.
     * @return the private key
     */
    PrivateKey getPrivateKey();

    /**
     * Retrieves the private key entry from the keystore associated with default alias.
     * @return the private key entry
     */
    PrivateKeyEntry getPrivateKeyEntry();

    /**
     * Gets the X.509 certificate associated with the private key default alias in the truststore.
     * @return the X.509 certificate
     */
    X509Certificate getCertificate();

    /**
     * Returns the certificate chain associated with the private key using the truststore as reference.
     * @return array of certificates
     * @throws KeyStoreException if an error occurs while accessing the keystore
     */
    X509Certificate[] getCertificateChain() throws KeyStoreException;

    /**
     * Initializes the KeyManagerFactory with the private key password, keystore, and associated key.
     * The KeyManagerFactory must not be null.
     * @param keyManagerFactory the KeyManagerFactory to initialize
     */
    void initKeyManagerFactory(KeyManagerFactory keyManagerFactory);

}
