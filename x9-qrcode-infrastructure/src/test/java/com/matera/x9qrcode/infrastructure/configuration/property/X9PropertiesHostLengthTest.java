/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration.property;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The configured host is bound with the shipped {@code application.yml} (its payload path and EMV
 * settings) exactly as the service binds it at startup, and nothing else is started.
 */
class X9PropertiesHostLengthTest {

    private static final String HOST_37 = "a".repeat(37 - ".example.com".length()) + ".example.com";
    private static final String HOST_38 = "a".repeat(38 - ".example.com".length()) + ".example.com";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(X9Properties.class)
    static class Binding { }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Binding.class);

    /**
     * <b>X9-LOC-480</b> — a configured host of 38 characters stops the service at startup, naming
     * the 37-character limit; 37 characters start.
     *
     * <p><b>Source:</b> Ours. ENDPOINTS.md: the loc URL lives in EMV tag 26, whose value is at most 99
     * characters (EMV QRCPS), so {@code 0006org.x9} + {@code 01LL} + host + {@code /pub/api/v1/loc/} + a
     * 32-character location leaves the host 37. The arithmetic is pinned by X9-LOC-425.
     *
     * <p><b>Why:</b> A longer host would make every QR Code this deployment issues overflow tag 26,
     * and every scanner would reject it. That is a configuration error, so it is caught once, at
     * startup, before a single unreadable QR Code is printed. The black-box suite cannot show it: it
     * runs against one deployment, already started.
     */
    @Test
    void aHostLongerThan37CharactersStopsTheServiceAtStartup() {
        assertThat(HOST_37).hasSize(37);
        assertThat(HOST_38).hasSize(38);

        runner.withPropertyValues("x9.public-endpoints.host=" + HOST_37)
            .run(context -> assertThat(context).hasNotFailed());

        runner.withPropertyValues("x9.public-endpoints.host=" + HOST_38)
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("x9.public-endpoints.host")
                .hasMessageContaining("37 limit"));
    }

    /**
     * <b>X9-LOC-485</b> — a configured host with a character outside printable ASCII, or a space,
     * stops the service at startup, naming the character; a plain host starts.
     *
     * <p><b>Source:</b> Ours (owner decision 2026-11-15, ADR-0027 EMV section). EMVCo's merchant-presented
     * mode limits tag 26's sub-tags to its alphanumeric special set, which is printable ASCII; the Go
     * build refuses the same hosts ({@code emv.CheckHost}).
     *
     * <p><b>Why:</b> Java refused a space (through {@code java.net.URI}) and started with a non-ASCII host,
     * then printed every QR Code with a tag 26 that a scanner may reject, or that counts its length in
     * a unit the next reader does not. A configuration error, caught once, before a single code is
     * printed.
     */
    @Test
    void aHostOutsidePrintableAsciiStopsTheServiceAtStartup() {
        runner.withPropertyValues("x9.public-endpoints.host=x9.example.com")
            .run(context -> assertThat(context).hasNotFailed());

        for (String host : new String[] {"x9-éxample.com", "x9 example.com", "x9\texample.com", "x9\u007fexample.com",
            "x9-с.example.com"}) {
            runner.withPropertyValues("x9.public-endpoints.host=" + host)
                .run(context -> assertThat(context).hasFailed()
                    .getFailure().rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("x9.public-endpoints.host")
                    .hasMessageContaining("printable ASCII"));
        }
    }

}
