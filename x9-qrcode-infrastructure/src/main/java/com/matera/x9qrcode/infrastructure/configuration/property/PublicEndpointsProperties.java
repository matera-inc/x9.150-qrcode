/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration.property;

import lombok.Data;

import java.net.URI;

@Data
public class PublicEndpointsProperties {

    private Long jwsTtlSeconds;
    private String host;
    private String basePath;
    private String payloadPath;
    private String certificatePath;
    private String jwkSetPath;
    private String paymentNotificationPath;

    public String getPayloadPath() {
        if (this.payloadPath.endsWith("/")) {
            return this.payloadPath;
        }
        return this.payloadPath + "/";
    }

    public String getPayloadDomain() {
        return host + getPayloadPath();
    }

    public String getCertificateUriPrefix() {
        String payloadDomain = host + certificatePath;

        if (payloadDomain.endsWith("/")) {
            return payloadDomain;
        }

        return payloadDomain + "/";
    }

    public URI getJwkSetUri() {
        return URI.create(host + jwkSetPath);
    }

    public URI getPaymentNotificationUri() {
        return URI.create(host + paymentNotificationPath);
    }

}
