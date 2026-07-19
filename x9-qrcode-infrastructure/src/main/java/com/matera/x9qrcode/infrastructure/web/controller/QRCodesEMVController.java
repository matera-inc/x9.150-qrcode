/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.app.usecase.decodeemv.DecodeEmvInput;
import com.matera.x9qrcode.app.usecase.decodeemv.DecodeEmvOutput;
import com.matera.x9qrcode.app.usecase.decodeemv.DecoveEmvUseCase;
import com.matera.x9qrcode.infrastructure.generated.api.QrcodeEmvApi;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentPayloadResponseDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.QRCodeEmvDecoderDTO;
import com.matera.x9qrcode.infrastructure.web.controller.mapper.response.DecoderResponseMapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSObject;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class QRCodesEMVController implements QrcodeEmvApi {

    private final DecoveEmvUseCase decoveEmvUseCase;
    private final ObjectMapper objectMapper;

    // TODO: check whether this parse is needed, since it strips fields that are not in our schema

    @Override
    @SneakyThrows
    public ResponseEntity<PaymentPayloadResponseDTO> decodeEmv(QRCodeEmvDecoderDTO qrCodeEmvDecoderDTO) {
        DecodeEmvOutput decodeEmvOutput =
            decoveEmvUseCase.execute(new DecodeEmvInput(qrCodeEmvDecoderDTO.getQrCode(),
                qrCodeEmvDecoderDTO.getDateForPayment(), qrCodeEmvDecoderDTO.getCorrelationID()));

        PaymentPayloadResponseDTO paymentPayloadResponseDTO = objectMapper
            .readValue(getJwsPayload(decodeEmvOutput), PaymentPayloadResponseDTO.class);

        PaymentPayloadResponseDTO response = DecoderResponseMapper.map(paymentPayloadResponseDTO);
        return ResponseEntity.ok().body(response);
    }

    @SneakyThrows
    private String getJwsPayload(DecodeEmvOutput decodeEmvOutput) {
        JWSObject jwsObject = JWSObject.parse(decodeEmvOutput.jwsToken());

        return jwsObject.getPayload().toString();
    }

}
