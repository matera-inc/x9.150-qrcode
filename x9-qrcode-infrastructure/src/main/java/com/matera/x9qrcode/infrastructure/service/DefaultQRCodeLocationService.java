/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service;

import com.matera.x9qrcode.app.exception.ServiceException;
import com.matera.x9qrcode.app.service.QRCodeLocationService;
import com.matera.x9qrcode.domain.utils.UUIDUtils;
import com.matera.x9qrcode.domain.vo.LocationIdVO;
import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.PropertyResolver;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import static org.apache.commons.lang3.math.NumberUtils.INTEGER_ZERO;

import static java.util.Objects.nonNull;

@Slf4j
@RequiredArgsConstructor
public class DefaultQRCodeLocationService implements QRCodeLocationService {

    public static final String HTTP = "http://";
    public static final String HTTPS = "https://";

    private final X9Properties x9Properties;
    private final PropertyResolver propertyResolver;

    @Value("${server.port:0}")
    private Integer localPort;

    public URI generateLocation(LocationIdVO locationId, boolean relative) throws ServiceException {
        try {
            String relativeLocation =
                x9Properties.getPublicEndpoints().getPayloadDomain() + UUIDUtils.toShortenString(locationId.value());

            if (relative) {
                return URI.create(relativeLocation);
            } else {
                return parseLocation(relativeLocation, false);
            }
        } catch (Exception e) {
            throw new ServiceException(e.getMessage());
        }
    }

    public URI parseLocation(String endpoint, boolean normalize) throws ServiceException {
        try {
            URL url;
            try {
                if (normalize) {
                    url = normalizeHost(endpoint).toURL();
                } else {
                    url = new URL(endpoint);
                }
            } catch (MalformedURLException e) {
                return buildAbsoluteUri(endpoint, normalize);
            }
            return url.toURI();
        } catch (URISyntaxException e) {
            throw new ServiceException("Invalid URI syntax: " + endpoint, e);
        }
    }

    @Override
    public URI retrievePaymentNotificationEndpoint() throws ServiceException {
        return parseLocation(x9Properties.getPublicEndpoints().getPaymentNotificationUri().toString(), false);
    }

    private URI buildAbsoluteUri(String originalUri, boolean normalize) {
        if (normalize) {
            return normalizeHost(originalUri);
        }
        return URI.create(HTTPS + originalUri);
    }

    private URI normalizeHost(String originalUri) {
        String publicHost = x9Properties.getPublicEndpoints().getHost();

        if (String.valueOf(originalUri).contains(publicHost) && !String.valueOf(originalUri).contains(retrieveLocalHost())) {
            log.info("Replacing public host {} with {} for internal access", publicHost, retrieveLocalHost());
            return replaceSchema(originalUri.replace(publicHost, retrieveLocalHost()), HTTP);
        }

        return replaceSchema(originalUri, HTTPS);
    }

    private static URI replaceSchema(String uriString, String replaceSchema) {
        URI uri = URI.create(uriString);

        if (nonNull(uri.getHost())) {
            String originalSchema = StringUtils.substringBefore(uriString, uri.getHost());
            return URI.create(StringUtils.replaceIgnoreCase(uriString, originalSchema, replaceSchema));
        } else {
            log.debug("URI host is null indicating that is a relative URI. Prepending schema: {}", replaceSchema);
            return URI.create(replaceSchema + uriString);
        }
    }

    private String retrieveLocalHost() {
        if (INTEGER_ZERO.equals(localPort)) {
            return "localhost:" + propertyResolver.getProperty("local.server.port", "8080");
        }
        return "localhost:" + localPort;
    }

}
