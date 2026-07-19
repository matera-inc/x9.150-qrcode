/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.generatesignature;

import com.matera.x9qrcode.app.dto.SignatureInputDataDTO;
import com.matera.x9qrcode.app.dto.SignatureOutputDataDTO;
import com.matera.x9qrcode.app.dto.enumerated.SignatureTypeEnumDTO;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.app.usecase.UseCase;

import lombok.RequiredArgsConstructor;

import static com.matera.x9qrcode.app.service.SignatureConstants.SECONDS_TO_MILLIS_VALUE;

@RequiredArgsConstructor
public class GenerationSignatureUseCase extends UseCase<SignatureGenerationInput, String> {

    private final QRCodeSignatureService qrCodeSignatureService;

    @Override
    public String execute(SignatureGenerationInput input) {
        Long ttlMillis = input.ttlTimeSeconds() != null ? input.ttlTimeSeconds() * SECONDS_TO_MILLIS_VALUE : null;

        SignatureInputDataDTO signatureInput = new SignatureInputDataDTO(
            SignatureTypeEnumDTO.EXTERNAL,
            input.content(),
            input.correlationId(),
            ttlMillis,
            null
        );

        SignatureOutputDataDTO signatureOutputDataDTO = qrCodeSignatureService.signData(signatureInput);

        return signatureOutputDataDTO.jwsToken();
    }

}
