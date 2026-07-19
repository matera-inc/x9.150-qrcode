/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.validatesignature;

import com.matera.x9qrcode.app.dto.enumerated.SignatureTypeEnumDTO;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;

import java.util.UUID;

import static org.apache.commons.lang3.StringUtils.isBlank;

import static java.util.Objects.isNull;

public record SignatureValidationInput(
    SignatureTypeEnumDTO signatureTypeEnumDTO,
    UUID correlationId,
    String jwsToken,
    String locationId) {

    public SignatureValidationInput {
        validateCommonFields(signatureTypeEnumDTO, jwsToken);
    }

    private void validateCommonFields(SignatureTypeEnumDTO signatureTypeEnumDTO, String jwsToken) {
        if (isNull(signatureTypeEnumDTO)) {
            throw new BusinessRuleException("signatureTypeEnumDTO", "Should inform the Signature Type for validation !!");
        }

        if (isBlank(jwsToken)) {
            throw new BusinessRuleException("jwsToken", "Should inform the JWS Token for validation !!");
        }
    }
}
