/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.app.dto.enumerated.SignatureTypeEnumDTO;
import com.matera.x9qrcode.app.usecase.generatesignature.GenerationSignatureUseCase;
import com.matera.x9qrcode.app.usecase.generatesignature.SignatureGenerationInput;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationInput;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationOutput;
import com.matera.x9qrcode.app.usecase.validatesignature.ValidateSignatureUseCase;
import com.matera.x9qrcode.infrastructure.generated.api.SignatureFeaturesApi;
import com.matera.x9qrcode.infrastructure.generated.dto.SignatureValidationRequestDTO;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SignatureFeaturesController implements SignatureFeaturesApi {

    private final ValidateSignatureUseCase validateSignatureUseCase;
    private final GenerationSignatureUseCase generationSignatureUseCase;

    @Override
    public ResponseEntity<String> generate(UUID correlationId, Integer ttLSeconds, Map<String, Object> requestBody) {
        SignatureGenerationInput signatureGenerationInput =
            new SignatureGenerationInput(correlationId, ttLSeconds, requestBody);

        return ResponseEntity.ok().body(generationSignatureUseCase.execute(signatureGenerationInput));
    }

    @Override
    public ResponseEntity<Void> validate(SignatureValidationRequestDTO requestDTO) {
        SignatureValidationInput signatureValidationInput =
            new SignatureValidationInput(SignatureTypeEnumDTO.EXTERNAL, null, requestDTO.getContent(), null);

        SignatureValidationOutput result = validateSignatureUseCase.execute(signatureValidationInput);

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Correlation-Id", result.correlationId().toString());

        return ResponseEntity.ok().headers(responseHeaders).build();
    }

}
