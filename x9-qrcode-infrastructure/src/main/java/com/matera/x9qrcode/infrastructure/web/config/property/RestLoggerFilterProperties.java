/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.config.property;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@Getter
@Setter
@ConfigurationProperties(prefix = "x9.observability.rest.logger-filter")
public class RestLoggerFilterProperties {

    private boolean enabled = true;
    private RegexFilterRules regexRules = new RegexFilterRules();

    @Getter
    @Setter
    @NoArgsConstructor
    public static class RegexFilterRules {
        private boolean logUnmatchedAsDebug = true;
        private Set<String> includedEndpoints = Set.of(".*?");
        private Set<String> excludedEndpoints = Set.of(".*?/actuator/health$", ".*?/actuator/prometheus$");
    }

}