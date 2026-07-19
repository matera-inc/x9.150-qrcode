/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.keystore;

import com.matera.x9qrcode.app.service.PrivateKeyRetriever;
import com.matera.x9qrcode.infrastructure.configuration.property.keystore.PrivateKeystoreProperties;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;

import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStore.Entry;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableEntryException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.net.ssl.KeyManagerFactory;
import javax.security.auth.x500.X500Principal;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
public class DefaultPrivateKeyRetriever extends AbstractKeystoreRetriever implements PrivateKeyRetriever {

	private DefaultTruststoreRetriever defaultTruststoreRetriever;

	private PrivateKey privateKey;

	private PrivateKeyEntry privateKeyEntry;

	private X509Certificate privateKeyCertificate;

	public DefaultPrivateKeyRetriever(PrivateKeystoreProperties privateKeyProperties,
									  String keystoreDescription,
									  ResourceLoader resourceLoader,
									  DefaultTruststoreRetriever trustStoreRetriever) {
        super(privateKeyProperties, keystoreDescription, resourceLoader);
		this.defaultTruststoreRetriever = trustStoreRetriever;
		initializePrivateKey();
	}

	@Override
	public String getPrivateKeyAlias() {
		if (nonNull(getKeyStoreProperties().getEnforcedAlias())) {
			log.info("Using enforced alias '{}' for the private key.", getKeyStoreProperties().getEnforcedAlias());
			return getKeyStoreProperties().getEnforcedAlias();
		} else {
			final List<String> aliases = getAliases();

			if (Objects.isNull(aliases) || aliases.isEmpty()) {
				throw new RuntimeException("The keystore don't have entries");
			} else if (aliases.size() != 1) {
				String baseMessage =
					"The keystore contains multiple entries (%s) but it can contain only a single entry with the Private Key."
						.formatted(String.join("|", aliases));

				throw new RuntimeException(baseMessage);
			} else {
				return aliases.get(0);
			}
		}
	}

	@Override
	public PrivateKey getPrivateKey() {
		if (Objects.isNull(privateKey) || !getKeyStoreProperties().isPrivateKeyCacheEnabled()) {
			log.debug("Private key is not cached or cache is disabled, loading the private key.");
			loadPrivateKey();
		} else {
			log.debug("Returning cached private key.");
		}
		return privateKey;
	}


	@Override
	public PrivateKeyEntry getPrivateKeyEntry() {

		if (nonNull(privateKeyEntry) && getKeyStoreProperties().isPrivateKeyCacheEnabled()) {
			log.debug("Returning cached PrivateKeyEntry.");
			return privateKeyEntry;
		}

		Entry keyEntry = getEntry(getPrivateKeyAlias(),
			getKeyStoreProperties().getPrivateKeyPasswordCharArray());

		if (keyEntry instanceof PrivateKeyEntry retrivedPrivateKeyEntry) {

			if (getKeyStoreProperties().isPrivateKeyCacheEnabled()) {
				this.privateKeyEntry = retrivedPrivateKeyEntry;
				log.debug("Caching PrivateKeyEntry.");
			}

			return retrivedPrivateKeyEntry;
		}
		throw new RuntimeException("Expecting that unique keyEntry be a PrivateKeyEntry but got %s"
			.formatted(getClassName(keyEntry)));
	}

	@Override
	public X509Certificate getCertificate() {

		if (nonNull(privateKeyCertificate) && getKeyStoreProperties().isPrivateKeyCacheEnabled()) {
			log.debug("Returning cached X509Certificate.");
			return privateKeyCertificate;
		}

		final String alias = getPrivateKeyAlias();

		try {
			final Certificate certificate =
				getKeyStoreProperties().isHsmEnabled() ?
					defaultTruststoreRetriever.getCertificate(alias) :
					super.keystore.getCertificate(getPrivateKeyAlias());

			if (certificate instanceof X509Certificate x509Certificate) {

				if (getKeyStoreProperties().isPrivateKeyCacheEnabled()) {
					this.privateKeyCertificate = x509Certificate;
					log.debug("Caching X509Certificate.");
				}

				return x509Certificate;
			}

			String errorMessage;
			if (isNull(certificate)) {
				errorMessage = "The alias %s provided does not exist in the keystore.".formatted(alias);
			} else {
				errorMessage = "The alias %s provided is not a X509Certificate. It is a %s".formatted(alias,
					getClassName(certificate));
			}
			throw new RuntimeException(errorMessage, null);
		} catch (KeyStoreException e) {
			throw new RuntimeException("Could not retrieve certificate for alias %s from keystore %s"
				.formatted(alias, keystoreDescription), e);
		}
	}

	/**
	 * Retrieves the certificate chain associated with the given alias.
	 * <p>
	 * This method attempts to construct a certificate chain starting from the certificate
	 * associated with the provided alias. If the alias does not correspond to a valid
	 * certificate or the certificate is not an X.509 certificate, an exception is thrown.
	 * The chain is built by iteratively finding the issuer of each certificate in the chain
	 * until a self-signed certificate or a certificate with no issuer in the store is reached.
	 * This is need because it's not possible to store the certificates as a chain without the private key,
	 * on a truststore the certificates need to be stored individually, so we need to build the chain manually.
	 * </p>
	 *
	 * @param alias the alias of the certificate to retrieve the chain for.
	 * @return an array of {@link Certificate} objects representing the certificate chain.
	 * @throws KeystoreOperationFailedException if the alias does not correspond to a valid
	 *         certificate or the certificate is not an X.509 certificate.
	 */
	@Override
	public X509Certificate[] getCertificateChain() {
		final Certificate firstCertificate = getCertificate();

		if (!(firstCertificate instanceof X509Certificate currentCertificate)) {
			throw new KeystoreOperationFailedException("Could not retrieve certificate chain for alias: " + getPrivateKeyAlias());
		}

		final Map<X500Principal, X509Certificate> allCertificatesBySubject = getAllCertificatesBySubject();

		List<Certificate> chain = new ArrayList<>();
		X509Certificate previousCertificate;
		do {
			chain.add(currentCertificate);
			previousCertificate = currentCertificate;
			currentCertificate = allCertificatesBySubject.get(currentCertificate.getIssuerX500Principal());
		} while (Objects.nonNull(currentCertificate) && !previousCertificate.equals(currentCertificate));

		return chain.toArray(new X509Certificate[] {});
	}

	@Override
	protected PrivateKeystoreProperties getKeyStoreProperties() {
		return (PrivateKeystoreProperties) super.getKeyStoreProperties();
	}

	@Override
	public void initKeyManagerFactory(KeyManagerFactory keyManagerFactory) {
		if (Objects.isNull(keyManagerFactory)) {
			throw new IllegalArgumentException("KeyManagerFactory must not be null");
		}

		try {
			keyManagerFactory.init(keystore, getKeyStoreProperties().getPrivateKeyPasswordCharArray());
		} catch (Exception e) {
			throw new RuntimeException("Could not initialize KeyManagerFactory for keystore " + keystoreDescription, e);
		}
	}
	@Override
	public boolean reloadIfNotUpToDate() {
		boolean keystoreReloaded = super.reloadIfNotUpToDate();

		if (keystoreReloaded) {
			clearCache();
			loadPrivateKey();
		}

		return keystoreReloaded;
	}

	@Override
	void clearCache() {
		privateKey = null;
		privateKeyEntry = null;
		privateKeyCertificate = null;
		log.debug("Cleared cached private key, private key entry and certificate.");
	}

	private void initializePrivateKey() {
		log.info("Initializing the private key");
		switch (getKeyStoreProperties().getLoadStrategy()) {

			case DO_NOT_LOAD:
			case LOAD_WITH_ONLY_PASSWORD:
				log.info("Avoiding to load the private key.");
				break;
			case LOAD_INPUT_STREAM:
			default:
				loadPrivateKey();
		}
	}

	private void loadPrivateKey() {
		String privateKeyAlias = getPrivateKeyAlias();
		Key key = getKey(privateKeyAlias, getKeyStoreProperties().getPrivateKeyPasswordCharArray());
		if (! (key instanceof PrivateKey)) {
			throw new RuntimeException("Entry %s of the keystore is not a private key. It is %s"
				.formatted(privateKeyAlias, getClassName(key), null));
		}
		privateKey = (PrivateKey) key;

	}

	private Key getKey(String alias, char[] password) {
		try {
			return keystore.getKey(alias, password);
		} catch (Exception e) {
			throw new RuntimeException("Could not retrieve key %s from keystore %s".formatted(alias, keystoreDescription), e);
		}
	}

	private Entry getEntry(String alias, char[] password) {

		if (keystoreProperties.isCacheEntriesEnabled()) {
			final Entry cachedEntry = entriesCache.get(alias);
			if (Objects.nonNull(cachedEntry)) {
				log.debug("Returning cached entry for alias {} in keystore {}", alias, keystoreDescription);
				return cachedEntry;
			}
		}

		try {
			final KeyStore.PasswordProtection passwordProtection = new KeyStore.PasswordProtection(password);
			final Entry entry = keystore.getEntry(alias, passwordProtection);

			if (keystoreProperties.isCacheEntriesEnabled() && Objects.nonNull(entry)) {
				entriesCache.put(alias, entry);
				log.debug("Cached entry for alias {} in keystore {}", alias, keystoreDescription);
			}

			return entry;
		} catch (NoSuchAlgorithmException | UnrecoverableEntryException | KeyStoreException e) {
			throw new RuntimeException("Error at get entry from keystore", e);
		}
	}

	private Map<X500Principal, X509Certificate> getAllCertificatesBySubject() {
		List<String> allCertificateAliases = defaultTruststoreRetriever.getAliases();
		Map<X500Principal, X509Certificate> certificateSubjects = new HashMap<>();

		for (String certAlias : allCertificateAliases) {
			Certificate cert = defaultTruststoreRetriever.getCertificate(certAlias);
			if (Objects.nonNull(cert) && cert instanceof X509Certificate x509Cert) {
				certificateSubjects.put(x509Cert.getSubjectX500Principal(), x509Cert);
			}
		}

		return certificateSubjects;
	}

}
