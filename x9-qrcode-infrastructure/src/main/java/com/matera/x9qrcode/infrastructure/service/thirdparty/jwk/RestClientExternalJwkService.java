/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.jwk;

import com.matera.x9qrcode.app.service.PEMService;
import com.matera.x9qrcode.app.service.QRCodeExternalJwkService;
import com.matera.x9qrcode.app.service.QRCodeLocationService;
import com.matera.x9qrcode.app.usecase.validatesignature.ExternalCertificateInput;
import com.matera.x9qrcode.app.usecase.validatesignature.ExternalCertificateOutput;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.apache.commons.lang3.StringUtils.isBlank;

import static java.util.Objects.isNull;

@Slf4j
@RequiredArgsConstructor
public class RestClientExternalJwkService implements QRCodeExternalJwkService {

    private final RestClient restClient;
    private final QRCodeLocationService qrCodeLocationService;
    private final PEMService pemService;

    @Override
    public ExternalCertificateOutput retrieveCertificate(ExternalCertificateInput input) {
        log.info("Getting Certificate type {} from URL: {}", input.endpointTypeEnum(), input.url());

        Optional<Map<String, Object>> externalJwkSet = Optional.empty();

        try {
            switch (input.endpointTypeEnum()) {
                case PEM -> {
                    return new ExternalCertificateOutput(
                        input.endpointTypeEnum(),
                        downloadCertificate(input.url()),
                        Map.of());
                }
                case JWK_SET -> {
                    return new ExternalCertificateOutput(
                        input.endpointTypeEnum(),
                        List.of(),
                        retrieveJwkSet(input.url()));
                }
                default -> throw new BusinessRuleException(
                    "Unsupported Certificate Endpoint Type: %s".formatted(input.endpointTypeEnum()));
            }
        } catch (Exception e) {
            throw new BusinessRuleException("Error retrieving Certificate Type %s from URL: %s"
                .formatted(input.endpointTypeEnum(), input.url()));
        }
    }

    private List<X509Certificate> downloadCertificate(String certificateUrl) {
        try {
            return pemService.parse(retrieveExternalCertificateData(certificateUrl, byte[].class).orElseThrow());
        } catch (Exception ex) {
            throw new BusinessRuleException(ex, "Error retrieving certificate data from URL: %s".formatted(certificateUrl));
        }
    }

    private Map<String, Object> retrieveJwkSet(String certificateUrl) {
        Map<String, Object> response = retrieveExternalCertificateData(certificateUrl, Map.class).orElseThrow();

        if (response.isEmpty()) {
            throw new BusinessRuleException("Received invalid JWK Set for URL: {}", certificateUrl);
        } else {
            return response;
        }
    }

    private <T> Optional<T> retrieveExternalCertificateData(String certificateUrl, Class<T> responseType) {
        if (isBlank(certificateUrl)) {
            log.warn("No Certificate URL given for download...");

            return Optional.empty();
        }

        try {
            ResponseEntity<T> certResponse =
                restClient.get()
                          .uri(qrCodeLocationService.parseLocation(certificateUrl, true))
                          .retrieve()
                          .toEntity(responseType);

            T response = certResponse.getBody();

            if (isNull(response)) {
                log.warn("Received null certificate data for URL: {}", certificateUrl);
            }

            return Optional.ofNullable(response);
        } catch (Exception ex) {
            throw new BusinessRuleException(ex, "Error retrieving external certificate data from URL: %s".formatted(certificateUrl));
        }
    }

}
