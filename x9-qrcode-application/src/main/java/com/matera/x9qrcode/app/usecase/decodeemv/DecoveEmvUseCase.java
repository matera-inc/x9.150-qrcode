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
import com.matera.x9qrcode.app.usecase.validatesignature.CorrelationEcho;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationInput;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationOutput;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import static java.util.Objects.nonNull;

@Slf4j
@RequiredArgsConstructor
public class DecoveEmvUseCase extends UseCase<DecodeEmvInput, DecodeEmvOutput> {

    /** The refusal of a payload that does not carry the correlationId it was asked under. */
    public static final String BAD_CORRELATION_ID = "QR Code is invalid (bad correlationId)";

    private final QRCodeExternalPayloadService qrCodeExternalPayloadService;
    private final QRCodeEMVService qrCodeEMVService;
    private final QRCodeSignatureService qrCodeSignatureService;

    @Override
    public DecodeEmvOutput execute(DecodeEmvInput input) {
        String payloadLocation = qrCodeEMVService.extractPayloadUrl(input.emv());

        if (nonNull(payloadLocation)) {
            // The payee's answer must echo the correlationId of the request we sent, whether the
            // caller chose it or we did: an answer under no correlationId, or under another one, is
            // not an answer to our request (ADR-0027, amendment of 2026-11-15).
            UUID sent = nonNull(input.correlationId()) ? input.correlationId() : UUID.randomUUID();

            DecodeEmvOutput response = qrCodeExternalPayloadService
                .retrievePayload(input.emv(), payloadLocation, input.dateForPayment(), sent);

            SignatureValidationInput signatureValidationInput =
                new SignatureValidationInput(
                    SignatureTypeEnumDTO.X9,
                    sent,
                    response.jwsToken(),
                    null);

            SignatureValidationOutput validationResult = qrCodeSignatureService.validateSignature(signatureValidationInput);

            if (validationResult.isValid()) {
                return response;
            }

            // Owner, 2026-11-15 (ADR-0027): a payload under no correlationId, or another one, gets its
            // own words. Trying again may succeed; nothing in the caller's input can fix it.
            if (CorrelationEcho.notEchoed(validationResult.problem(),
                    qrCodeSignatureService.headerCorrelationId(response.jwsToken()), sent)) {
                throw new BusinessRuleException(BAD_CORRELATION_ID);
            }

            throw new BusinessRuleException("Payload JWS signature is invalid.");
        } else {
            throw new BusinessRuleException("EMV QR Code does not contain a valid payload URL.");
        }
    }

}
