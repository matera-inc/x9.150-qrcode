/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.validatesignature;

import com.matera.x9qrcode.app.exception.ServiceException;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.app.usecase.UseCase;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ValidateSignatureUseCase extends UseCase<SignatureValidationInput, SignatureValidationOutput> {

    private final QRCodeSignatureService qrCodeSignatureService;

    @Override
    public SignatureValidationOutput execute(SignatureValidationInput input) {
        try {
            SignatureValidationOutput result = qrCodeSignatureService.validateSignature(input);

            if (!result.isValid()) {
                throw new BusinessRuleException("Signature validation failed for content !!!");
            }

            return result;
        } catch (ServiceException e) {
            throw new BusinessRuleException("Error validating signature: " + e.getMessage());
        }
    }

}
