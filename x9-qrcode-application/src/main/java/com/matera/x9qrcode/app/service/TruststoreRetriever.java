/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.service;

import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.TrustManagerFactory;

/**
 * Provides access to a Keystore.
 */
public interface TruststoreRetriever extends KeystoreRetriever {

	/**
	 * Get the certificate associated with the alias.
	 * @param alias the alias of the certificate.
	 * @return the certificate associated with the alias or null if not found.
	 */
	X509Certificate getCertificate(String alias);

	/**
	 * Get all trusted certificates of the keystore
	 *
	 * @return all {@link X509Certificate} contained at the keystore.
	 */
	List<X509Certificate> getAllTrustedCertificates();

	/**
	 * Initializes the TrustManagerFactory with the keystore. TrustManagerFactory must not be null.
	 * @param trustManagerFactory the TrustManagerFactory to be initialized.
	 */
	void initTrustManagerFactory(TrustManagerFactory trustManagerFactory);

}
