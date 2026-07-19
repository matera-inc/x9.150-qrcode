/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.config;

import com.matera.x9qrcode.infrastructure.web.config.filter.AbstractRequestLoggerFilter;
import com.matera.x9qrcode.infrastructure.web.config.filter.AfterRequestLoggerFilterHook;
import com.matera.x9qrcode.infrastructure.web.config.filter.NoopAfterRequestLoggerFilterHook;
import com.matera.x9qrcode.infrastructure.web.config.filter.RegexRequestLoggerFilter;
import com.matera.x9qrcode.infrastructure.web.config.property.RestLoggerFilterProperties;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RestLoggerFilterProperties.class)
@ConditionalOnProperty(prefix = "x9.observability.rest.logger-filter", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RestLoggerFilterConfiguration {

    private final RestLoggerFilterProperties properties;

    public RestLoggerFilterConfiguration(RestLoggerFilterProperties properties) {
        this.properties = properties;
    }

    @Bean
    @ConditionalOnMissingBean
    public AfterRequestLoggerFilterHook afterRequestLoggerFilterHook() {
        log.debug("Application could not found a AfterRequestLoggerFilterHook instance, using NoopAfterRequestLoggerFilterHook.");

        return new NoopAfterRequestLoggerFilterHook();
    }

    @Bean
    public AbstractRequestLoggerFilter getRegexRequestLoggerFilter() {
        return new RegexRequestLoggerFilter();
    }

    @Bean(name = "regexPatternIncludedEndpoints")
    public Set<Pattern> getRegexPatternIncludedEndpoints() {
        log.info("Loading pattern to SHOW into Info rest logger: {}", properties.getRegexRules().getIncludedEndpoints());
        return loadRegexPatternList(properties.getRegexRules().getIncludedEndpoints());
    }
    
    @Bean(name = "regexPatternExcludedEndpoints")
    public Set<Pattern> getRegexPatternExcludedEndpoints() {
        log.info("Loading pattern to HIDE into Info rest logger: {}", properties.getRegexRules().getExcludedEndpoints());
        return loadRegexPatternList(properties.getRegexRules().getExcludedEndpoints());
    }

    private Set<Pattern> loadRegexPatternList(Set<String> regexStrings) {
        if (Objects.isNull(regexStrings) || regexStrings.isEmpty()) {
            return Collections.emptySet();
        }

         return regexStrings.stream()
                 .filter(regex -> Objects.nonNull(regex) && !regex.isBlank())
                 .<Pattern>mapMulti((regexStr, downstream) -> {
                     try {
                         downstream.accept(Pattern.compile(regexStr));
                     } catch (PatternSyntaxException e) {
                         log.warn("Error compiling regex string '{}' for show/hide Info Rest Logger: {}", regexStr, e.getMessage());
                     }
                 })
                 .collect(Collectors.toSet());
    }

}
