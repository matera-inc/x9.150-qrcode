/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CertificateEndpointTypeEnum {
    NONE ("Expose certificates using x5c field on JWS"),
    PEM ("Expose certificates using x5u field with public URL on JWS"),
    JWK_SET("Expose certificates using jku field with public JWK Set URL on JWS");

    private final String description;
}
