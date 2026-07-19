/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller;

import com.matera.x9qrcode.app.dto.SignatureInputDataDTO;
import com.matera.x9qrcode.app.dto.SignatureOutputDataDTO;
import com.matera.x9qrcode.app.dto.enumerated.SignatureTypeEnumDTO;
import com.matera.x9qrcode.app.service.QRCodeSignatureService;
import com.matera.x9qrcode.app.usecase.paymentnotification.PaymentNotificationQRCodeUseCase;
import com.matera.x9qrcode.app.usecase.retrievepayload.RetrieveQRCodePayloadInput;
import com.matera.x9qrcode.app.usecase.retrievepayload.RetrieveQRCodePayloadOutput;
import com.matera.x9qrcode.app.usecase.retrievepayload.RetrieveQRCodePayloadUseCase;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationInput;
import com.matera.x9qrcode.app.usecase.validatesignature.SignatureValidationOutput;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.infrastructure.generated.api.PublicEndpointsApi;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentNotificationDataDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.PaymentPayloadResponseDTO;
import com.matera.x9qrcode.infrastructure.web.controller.mapper.request.PaymentNotificationRequestMapper;
import com.matera.x9qrcode.infrastructure.web.controller.mapper.response.RetrieveQRCodePayloadResponseMapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static com.matera.x9qrcode.app.service.SignatureConstants.CONTENT_DISPOSITION_HEADER;
import static com.matera.x9qrcode.app.service.SignatureConstants.CONTENT_TYPE_HEADER;
import static com.matera.x9qrcode.app.service.SignatureConstants.HEADER_PAYLOAD_BASED_DATE;
import static com.matera.x9qrcode.app.service.SignatureConstants.PEM_ATTACHMENT_FILENAME_HEADER_VALUE;
import static com.matera.x9qrcode.app.service.SignatureConstants.PEM_CONTENT_TYPE_HEADER_VALUE;

@Slf4j
@RestController
@RequiredArgsConstructor
public class PublicEndpointsController implements PublicEndpointsApi {

    private final RetrieveQRCodePayloadUseCase retrieveQRCodePayloadUseCase;
    private final PaymentNotificationQRCodeUseCase paymentNotificationQRCodeUseCase;
    private final QRCodeSignatureService qrCodeSignatureService;
    private final ObjectMapper objectMapper;

    @Override
    public ResponseEntity<Resource> getCertificate(String fileName) {
        try {
            ByteArrayResource pemResource =
                new ByteArrayResource(qrCodeSignatureService.retrieveDigitalSignatureCertificate());

            return ResponseEntity.ok()
                                 .header(CONTENT_DISPOSITION_HEADER, PEM_ATTACHMENT_FILENAME_HEADER_VALUE.formatted(fileName))
                                 .header(CONTENT_TYPE_HEADER, PEM_CONTENT_TYPE_HEADER_VALUE)
                                 .body(pemResource);
        } catch (Exception e) {
            throw new BusinessRuleException(e, "Error reading certificate.");
        }
    }

    @Override
    public ResponseEntity<Map<String, Object>> getJwkSet() {
        return ResponseEntity.ok(qrCodeSignatureService.retrieveDigitalSignatureJwkSet());
    }

    @Override
    public ResponseEntity<Void> processPaymentNotification(String body) {
        try {
            SignatureValidationInput validationInput = new SignatureValidationInput(SignatureTypeEnumDTO.X9, null, body, null);
            SignatureValidationOutput validationResult = qrCodeSignatureService.validateSignature(validationInput);
            if (!validationResult.isValid()) {
                throw new BusinessRuleException("Payment notification JWS signature is invalid.");
            }

            JWSObject jwsObject = JWSObject.parse(body);
            PaymentNotificationDataDTO notificationData = objectMapper.readValue(
                jwsObject.getPayload().toString(), PaymentNotificationDataDTO.class);

            paymentNotificationQRCodeUseCase.execute(PaymentNotificationRequestMapper.map(notificationData));

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            throw new BusinessRuleException(e, "Error processing payment notification.");
        }
    }

    @Override
    public ResponseEntity<String> retrievePaymentPayloadByLocation(String id, String body, LocalDate dateForPayment) {
        try {
            SignatureValidationInput signatureValidationInput =
                new SignatureValidationInput(
                    SignatureTypeEnumDTO.X9,
                    null,
                    body,
                    id
                );

            SignatureValidationOutput validationResult = qrCodeSignatureService.validateSignature(signatureValidationInput);

            if (validationResult.isValid()) {
                RetrieveQRCodePayloadOutput retrieveQRCodePayloadOutput =
                    retrieveQRCodePayloadUseCase.execute(new RetrieveQRCodePayloadInput(id, dateForPayment));

                LocalDate payloadBasedDate =
                    Optional.ofNullable(dateForPayment).orElse(retrieveQRCodePayloadOutput.sentAt().toLocalDate());

                PaymentPayloadResponseDTO responseDTO =
                    RetrieveQRCodePayloadResponseMapper.map(retrieveQRCodePayloadOutput);

                SignatureInputDataDTO signatureInputDataDTO =
                    new SignatureInputDataDTO(SignatureTypeEnumDTO.X9, responseDTO, validationResult.correlationId(),
                        null, HttpStatus.OK.value());

                SignatureOutputDataDTO signatureOutputDataDTO = qrCodeSignatureService.signData(signatureInputDataDTO);

                return ResponseEntity.ok()
                                     .header(HEADER_PAYLOAD_BASED_DATE, payloadBasedDate.toString())
                                     .body(signatureOutputDataDTO.jwsToken());
            }
        } catch (Exception e) {
            log.error("Error processing payment payload retrieve: {}", e.getLocalizedMessage(), e);

            throw new BusinessRuleException(e, "Error processing payment payload retrieve.");
        }

        throw new BusinessRuleException("Invalid JWS signature for payment payload request.");
    }

}
