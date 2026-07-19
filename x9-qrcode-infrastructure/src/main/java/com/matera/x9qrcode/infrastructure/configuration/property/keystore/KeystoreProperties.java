/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration.property.keystore;

import lombok.Data;
import lombok.Getter;
import lombok.ToString;

import java.util.Objects;

import javax.net.ssl.TrustManagerFactory;

@Data
public class KeystoreProperties {

	private String trustManagerFactoryAlgorithm = TrustManagerFactory.getDefaultAlgorithm();

	private String type = "JKS";

	private String keystoreAlias;

	private String provider;

	/**
	 * defines the class that will be used to be used as provider that need to be instantiated and set on Security.
	 */
	private Class<?> externalProviderClass;

	private KeyStoreLoadingStrategy loadStrategy = KeyStoreLoadingStrategy.LOAD_INPUT_STREAM;

	private String location;

	@ToString.Exclude
	@Getter(lombok.AccessLevel.NONE)
	private String password;

	private boolean ignoreExpiredValidation = false;

	private String alias;

	private boolean cacheCertificatesEnabled = true;

	private boolean cacheEntriesEnabled = true;

	/**
	 * if set, will consider that the keystore has only this alias.
	 */
	private String enforcedAlias;

	PKCS11Properties pkcs11 = new PKCS11Properties();

	public char[] getPasswordCharArray() {
		return Objects.isNull(password) ? null : password.toCharArray();
	}

	@Data
	public class PKCS11Properties {

		private String configFilePath;

		private String configurableBaseProvider = "SunPKCS11";

	}

	public enum KeyStoreLoadingStrategy {

		/**
		 * Keystore must be loaded by reading an InputStream.
		 */
		LOAD_INPUT_STREAM,

		/**
		 * Keystore must be loaded by calling the {@code load} method
		 * passing {@code null} at file input stream parameter, and passing the specified password. (for example,
		 * for some PKCS#11 integrations that expect the PIN be specified as the password).
		 */
		LOAD_WITH_ONLY_PASSWORD,

		/**
		 * Keystore must not be loaded.
		 * <p>This should the case only for the PrivateKey keystore,
		 * when using HSM solutions where a specific provider is used
		 * for the signature algorithm that does not require a PrivateKey
		 * to be informed.
		 */
		DO_NOT_LOAD;

	};

}
