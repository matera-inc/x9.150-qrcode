/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.response;

import com.matera.x9qrcode.app.usecase.createqrcode.CreateQRCodeOutput;
import com.matera.x9qrcode.app.dto.LocationDTO;
import com.matera.x9qrcode.domain.utils.UUIDUtils;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestLocationDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestResponseDTO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CreateQRCodeResponseMapper {

    private static final Base64.Encoder ENCODER = Base64.getEncoder();

    public static PaymentRequestResponseDTO map(CreateQRCodeOutput createQRCodeOutput) {
        String qrCodeContent = createQRCodeOutput.qrCodeContent();

        return new PaymentRequestResponseDTO()
                .id(UUIDUtils.toShortenString(createQRCodeOutput.id()))
                .qrCode(qrCodeContent)
                .qrCodeB64(ENCODER.encodeToString(qrCodeContent.getBytes(StandardCharsets.UTF_8)))
                .location(buildLocation(createQRCodeOutput.location()));
    }

    private static PaymentRequestLocationDTO buildLocation(LocationDTO locationDTO) {
        return new PaymentRequestLocationDTO()
                .id(UUIDUtils.toShortenString(locationDTO.id()))
                .endpoint(locationDTO.endpoint());
    }

}
