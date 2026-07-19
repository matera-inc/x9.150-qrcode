/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration.property.keystore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import static java.util.Objects.isNull;

@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class PrivateKeystoreProperties extends KeystoreProperties {

	private boolean hsmEnabled = false;
	private String enforcedAlias;

	@ToString.Exclude
	@Getter(lombok.AccessLevel.NONE)
	private String privateKeyPassword;

	private boolean privateKeyCacheEnabled = true;

	public char[] getPrivateKeyPasswordCharArray() {
		return isNull(privateKeyPassword) ? null : privateKeyPassword.toCharArray();
	}

}
