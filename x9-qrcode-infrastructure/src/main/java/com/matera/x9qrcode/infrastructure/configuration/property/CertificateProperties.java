/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration.property;

import com.matera.x9qrcode.domain.dto.CertificateEndpointTypeEnum;
import com.matera.x9qrcode.infrastructure.configuration.property.keystore.KeystoreProperties;
import com.matera.x9qrcode.infrastructure.configuration.property.keystore.PrivateKeystoreProperties;

import lombok.Data;

@Data
public class CertificateProperties {

    private CertificateEndpointTypeEnum endpointType;
    private String issuerName;
    private String jwkAlgorithm;
    private String customProvider;
    private String certificateSupportedType;
    private PrivateKeystoreProperties privateKeystore;
    private KeystoreProperties truststore;

}
