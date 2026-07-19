/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.decodeemv;

import com.matera.x9qrcode.app.dto.enumerated.SignatureTypeEnumDTO;
import com.matera.x9qrcode.app.service.QRCodeEMVService;
import com.matera.x9qrcode.app.service.QRCodeExternalPayloadService;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.app.usecase.UseCase;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationInput;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationOutput;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static java.util.Objects.nonNull;

@Slf4j
@RequiredArgsConstructor
public class DecoveEmvUseCase extends UseCase<DecodeEmvInput, DecodeEmvOutput> {

    private final QRCodeExternalPayloadService qrCodeExternalPayloadService;
    private final QRCodeEMVService qrCodeEMVService;
    private final QRCodeSignatureService qrCodeSignatureService;

    @Override
    public DecodeEmvOutput execute(DecodeEmvInput input) {
        String payloadLocation = qrCodeEMVService.extractPayloadUrl(input.emv());

        if (nonNull(payloadLocation)) {
            DecodeEmvOutput response = qrCodeExternalPayloadService
                .retrievePayload(input.emv(), payloadLocation, input.dateForPayment(), input.correlationId());

            SignatureValidationInput signatureValidationInput =
                new SignatureValidationInput(
                    SignatureTypeEnumDTO.X9,
                    input.correlationId(),
                    response.jwsToken(),
                    null);

            SignatureValidationOutput validationResult = qrCodeSignatureService.validateSignature(signatureValidationInput);

            if (validationResult.isValid()) {
                return response;
            }

            throw new BusinessRuleException("Payload JWS signature is invalid.");
        } else {
            throw new BusinessRuleException("EMV QR Code does not contain a valid payload URL.");
        }
    }

}
