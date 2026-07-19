/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration.property;

import com.matera.x9qrcode.infrastructure.configuration.property.keystore.EmvProperties;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

import static org.apache.commons.lang3.StringUtils.isBlank;

@Slf4j
@Data
@ConfigurationProperties(prefix = "x9")
public class X9Properties implements InitializingBean {

    /**
     * Length of the shortened UUID used as location ID in the EMV QR Code URL.
     * Derived from {@code UUIDUtils.toShortenString(UUID)}, which produces a
     * 32-char
     * uppercase hex string (e.g., {@code 68D389223C4E428EB1D6C87122C94EB5}).
     */
    static final int SHORTENED_UUID_LENGTH = 32;

    private PublicEndpointsProperties publicEndpoints = new PublicEndpointsProperties();

    private CertificateProperties certificate = new CertificateProperties();

    private EmvProperties emv = new EmvProperties();

    @Override
    public void afterPropertiesSet() {
        validatePayloadDomain();
    }

    private void validatePayloadDomain() {
        if (isBlank(publicEndpoints.getHost())) {
            throw new IllegalStateException(
                    "property x9.public-endpoints.host is not present, please check property value.");
        }

        int fullPayloadUrlLength = publicEndpoints.getPayloadDomain().length() + SHORTENED_UUID_LENGTH;
        if (fullPayloadUrlLength > emv.getUrlMaxSize()) {
            int domainLimit =  emv.getUrlMaxSize() - (publicEndpoints.getPayloadPath().length() + SHORTENED_UUID_LENGTH);
            String error = "[ERROR]: Property x9.public-endpoints.host should not exceeded the %d limit (current size: %d) !";
            throw new IllegalStateException(error.formatted(domainLimit, publicEndpoints.getHost().length()));
        }

        try {
            new URI(publicEndpoints.getPayloadDomain());
        } catch (Exception e) {
            throw new IllegalStateException("property x9.public-endpoints.payload-domain must be a valid URI.", e);
        }

        log.info("Using payload domain {}", publicEndpoints.getPayloadDomain());
    }

}
