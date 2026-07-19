/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.dto.enumerated;

import lombok.AllArgsConstructor;

import static com.matera.x9qrcode.app.service.SignatureConstants.X9_SIGNATURE_EXTERNAL_LABEL;
import static com.matera.x9qrcode.app.service.SignatureConstants.X9_SIGNATURE_PAYLOAD_LABEL;

@AllArgsConstructor
public enum SignatureTypeEnumDTO {

    X9(X9_SIGNATURE_PAYLOAD_LABEL),
    EXTERNAL(X9_SIGNATURE_EXTERNAL_LABEL);

    public final String value;

    public static SignatureTypeEnumDTO fromValue(String value) {
        for (SignatureTypeEnumDTO b : SignatureTypeEnumDTO.values()) {
            if (b.value.equals(value)) {
                return b;
            }
        }

        throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }

}
