/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.service;

import java.util.List;

/**
 * Provides access to a Keystore.
 */
public interface KeystoreRetriever {

	/**
	 * Gets the description of the keystore.
	 * 
	 * @return the Keystore description.
	 */
	String getDescription();

	/**
	 * Alias of this keystore. It is not some alias inside the keystore,
	 * it is the alias of the keystore itself in the application.
	 * 
	 * @return the alias of the keystore.
	 */
	String getKeystoreAlias();

	/**
	 * Get a key from the keystore.
	 *
	 * @return the key associated with the alias.
	 * @param alias the alias of the key.
	 * @param password the password of the key.
	 */

	/**
	 * Get all alias from the keystore.
	 *
	 * @return the aliases from the keystore.
	 */
	List<String> getAliases();

	/**
	 * Reload the keystore if its underlying resource was updated since
	 * the last time it was loaded.
	 *
	 * @return true if the keystore was updated.
	 */
	boolean reloadIfNotUpToDate();

}
