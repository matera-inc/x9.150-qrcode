/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.response;

import com.matera.x9qrcode.app.dto.LocationDTO;
import com.matera.x9qrcode.app.usecase.patchqrcode.PatchQRCodeOutput;
import com.matera.x9qrcode.domain.utils.UUIDUtils;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestLocationDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentRequestResponseDTO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Base64.Encoder;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PatchQRCodeResponseMapper {

    private static final Encoder ENCODER = Base64.getEncoder();

    public static PaymentRequestResponseDTO map(PatchQRCodeOutput output) {
        String qrCodeContent = output.qrCodeContent();

        PaymentRequestResponseDTO paymentRequestResponseDTO = new PaymentRequestResponseDTO();
        paymentRequestResponseDTO.setId(UUIDUtils.toShortenString(output.id()));
        paymentRequestResponseDTO.setQrCode(qrCodeContent);
        paymentRequestResponseDTO.setQrCodeB64(
                ENCODER.encodeToString(qrCodeContent.getBytes(StandardCharsets.UTF_8)));
        paymentRequestResponseDTO.setLocation(buildLocation(output.location()));

        return paymentRequestResponseDTO;
    }

    private static PaymentRequestLocationDTO buildLocation(LocationDTO location) {
        return new PaymentRequestLocationDTO()
                .id(UUIDUtils.toShortenString(location.id()))
                .endpoint(location.endpoint());
    }

}
