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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.PropertyResolver;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.apache.commons.lang3.math.NumberUtils.INTEGER_ZERO;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

@Slf4j
@RequiredArgsConstructor
public class DefaultQRCodeLocationService implements QRCodeLocationService {

    public static final String HTTP = "http://";
    public static final String HTTPS = "https://";

    /** A URL that starts with a scheme: {@code scheme "://"} (RFC 3986 §3.1). */
    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://");

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
        if (normalize) {
            return normalizeHost(endpoint);
        }
        try {
            URL url;
            try {
                url = new URL(endpoint);
            } catch (MalformedURLException e) {
                return URI.create(HTTPS + endpoint);
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

    /**
     * The URL a payload, a JWK set ({@code jku}) or a certificate chain ({@code x5u}) is fetched
     * from. These URLs are unauthenticated input (ADR-0027, "Certificate fetching"):
     *
     * <ul>
     *   <li>a URL whose host, port included, <em>is</em> this deployment's public host
     *       ({@code x9.public-endpoints.host}, compared case-insensitively) is read internally,
     *       over {@code http://localhost:<port>}, keeping its path and query;</li>
     *   <li>every other URL is fetched over {@code https} from the host it names, whatever scheme
     *       was written, or none. A URL that merely mentions the public host (in its query, its
     *       path, its user information, or as part of a longer host name) is another host's
     *       (BUG-005);</li>
     *   <li>user information is dropped, so no credential is ever sent, and so is the fragment;</li>
     *   <li>a scheme other than {@code http} or {@code https}, or no host, is refused.</li>
     * </ul>
     *
     * <p>Go's fetcher (Story 8.3, {@code internal/pki/fetch.go}) gives the same answer.
     */
    private URI normalizeHost(String originalUri) throws ServiceException {
        String written = String.valueOf(originalUri).trim();
        URI uri;
        try {
            uri = new URI(SCHEME.matcher(written).lookingAt() ? written : HTTPS + written);
        } catch (URISyntaxException e) {
            throw new ServiceException("Invalid URI syntax: " + originalUri, e);
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ServiceException("The scheme %s is not http or https: %s".formatted(scheme, originalUri));
        }
        String authority = uri.getRawAuthority();
        if (isBlank(authority)) {
            throw new ServiceException("The URL has no host: " + originalUri);
        }
        // host[:port], without user information: the last '@' ends it, as Go's url.Parse reads it.
        String hostAndPort = authority.substring(authority.lastIndexOf('@') + 1);
        if (isBlank(hostAndPort)) {
            throw new ServiceException("The URL has no host: " + originalUri);
        }
        String pathAndQuery = Objects.toString(uri.getRawPath(), "")
            + (nonNull(uri.getRawQuery()) ? "?" + uri.getRawQuery() : "");

        if (hostAndPort.equalsIgnoreCase(x9Properties.getPublicEndpoints().getHost().trim())) {
            log.info("Reading {} from this deployment's own host, internally at {}", hostAndPort, retrieveLocalHost());
            return URI.create(HTTP + retrieveLocalHost() + pathAndQuery);
        }

        return URI.create(HTTPS + hostAndPort + pathAndQuery);
    }

    private String retrieveLocalHost() {
        if (INTEGER_ZERO.equals(localPort)) {
            return "localhost:" + propertyResolver.getProperty("local.server.port", "8080");
        }
        return "localhost:" + localPort;
    }

}
