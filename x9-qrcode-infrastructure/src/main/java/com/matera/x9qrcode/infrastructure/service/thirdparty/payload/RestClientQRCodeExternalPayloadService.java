/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.payload;

import com.matera.x9qrcode.app.dto.SignatureInputDataDTO;
import com.matera.x9qrcode.app.dto.SignatureOutputDataDTO;
import com.matera.x9qrcode.app.dto.enumerated.SignatureTypeEnumDTO;
import com.matera.x9qrcode.app.service.QRCodeExternalPayloadService;
import com.matera.x9qrcode.app.service.QRCodeLocationService;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.app.usecase.decodeemv.DecodeEmvOutput;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentPayloadRequestDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.UUID;
import java.util.function.Consumer;

import static java.util.Objects.nonNull;

@Slf4j
@RequiredArgsConstructor
public class RestClientQRCodeExternalPayloadService implements QRCodeExternalPayloadService {

    private static final Encoder ENCODER = Base64.getEncoder();
    private static final String HEADER_DATE_FOR_PAYMENT = "dateForPayment";
    private static final String APPLICATION_JOSE = "application/jose";

    private final RestClient restClient;
    private final QRCodeLocationService qrCodeLocationService;
    private final QRCodeSignatureService qrCodeSignatureService;

    @Override
    public DecodeEmvOutput retrievePayload(String emv,
            String payloadLocation,
            LocalDate dateForPayment,
            UUID correlationId) {
        log.info("Retrieving payload with dateForPayment {} from location {}", dateForPayment, payloadLocation);

        URI payloadUri = qrCodeLocationService.parseLocation(payloadLocation, true);

        Consumer<HttpHeaders> headersConsumer = headers -> {
            headers.setContentType(MediaType.parseMediaType(APPLICATION_JOSE));

            if (nonNull(dateForPayment)) {
                headers.add(HEADER_DATE_FOR_PAYMENT, dateForPayment.toString());
            }
        };

        try {
            String requestJws = createPayloadRequestJws(emv, correlationId);

            ResponseEntity<String> response = restClient.post()
                    .uri(payloadUri)
                    .headers(headersConsumer)
                    .body(requestJws)
                    .retrieve()
                    .toEntity(String.class);

            return new DecodeEmvOutput(
                    qrCodeLocationService.parseLocation(payloadLocation, false),
                    response.getStatusCode().value(),
                    response.getBody());
        } catch (Exception e) {
            throw new BusinessRuleException(e, "Error retrieving payload URI: %s".formatted(payloadUri));
        }
    }

    private String createPayloadRequestJws(String emv, UUID correlationId) {
        String qrCodeB64 = ENCODER.encodeToString(emv.getBytes());

        PaymentPayloadRequestDTO requestDTO = new PaymentPayloadRequestDTO().qrCodeContent(qrCodeB64);

        SignatureOutputDataDTO signatureOutput = qrCodeSignatureService.signData(
                new SignatureInputDataDTO(SignatureTypeEnumDTO.X9, requestDTO, getCorrelationId(correlationId), null,
                        null));

        return signatureOutput.jwsToken();
    }

    private static UUID getCorrelationId(UUID correlationId) {
        return nonNull(correlationId) ? correlationId : UUID.randomUUID();
    }

}
