/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testclock;

import com.matera.x9qrcode.app.service.PrivateKeyRetriever;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The movable test clock (ADR-0033). Off unless {@code X9_TESTING_CLOCKCONTROL=true}; with it off,
 * none of this exists and nothing listens on the clock address.
 *
 * <p>With it on, the application refuses to start unless the signing certificate is a bundled demo
 * or test-CA one ({@link TestClockCertificateGuard}), and unless the clock listener is on a port of
 * its own.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TestClockProperties.class)
@ConditionalOnProperty(prefix = "x9.testing", name = "clock-control", havingValue = "true")
public class TestClockConfiguration {

    @Bean
    public TestClockServer testClockServer(TestClockProperties properties,
                                           PrivateKeyRetriever privateKeyRetriever,
                                           ObjectMapper objectMapper,
                                           @Value("${server.port:8080}") int apiPort) {
        TestClockCertificateGuard.requireBundled(privateKeyRetriever.getCertificate());

        int clockPort = TestClockServer.parse(properties.getClockListen()).getPort();
        if (clockPort != 0 && clockPort == apiPort) {
            throw new IllegalStateException(
                "X9_TESTING_CLOCKLISTEN (%s) must not be the API port %d: /test/clock is never served on the API port"
                    .formatted(properties.getClockListen(), apiPort));
        }

        return new TestClockServer(properties.getClockListen(), objectMapper, privateKeyRetriever::getCertificate);
    }

}
