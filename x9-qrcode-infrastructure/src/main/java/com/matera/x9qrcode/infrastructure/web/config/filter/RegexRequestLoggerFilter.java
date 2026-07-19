/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.config.filter;


import com.matera.x9qrcode.infrastructure.web.config.dto.RequestLoggerFilterDTO;
import com.matera.x9qrcode.infrastructure.web.config.property.RestLoggerFilterProperties;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class RegexRequestLoggerFilter extends AbstractRequestLoggerFilter {

    @Autowired
    private AfterRequestLoggerFilterHook afterRequestLoggerFilterHook;

    @Autowired
    private Set<Pattern> regexPatternIncludedEndpoints;

    @Autowired
    private Set<Pattern> regexPatternExcludedEndpoints;

    @Autowired
    private RestLoggerFilterProperties properties;

    private boolean includeAllUrls;
    private boolean excludeAllUrls;
    private boolean excludeIsEmpty;

    private enum LogAction { INFO, DEBUG_EXCLUDED, DEBUG_UNDEFINED, NONE }

    @PostConstruct
    public void init() {
        this.includeAllUrls = containsMatchAllPattern(regexPatternIncludedEndpoints);

        this.excludeAllUrls = containsMatchAllPattern(regexPatternExcludedEndpoints);

        this.excludeIsEmpty = Objects.isNull(regexPatternExcludedEndpoints) ||
                              regexPatternExcludedEndpoints.isEmpty();

        log.info("RequestLoggerFilter initialized. Included all urls?: {}, Excluded all urls?: {}, Exclusion patterns is empty: {}",
            this.includeAllUrls, this.excludeAllUrls, this.excludeIsEmpty);
    }

    protected void logStartedExecution(RequestLoggerFilterDTO requestLoggerFilterDTO) {
        LogAction action = determineLogAction(requestLoggerFilterDTO.getRequestURL());

        switch (action) {
            case INFO:
                log.info("Started {} - {}", requestLoggerFilterDTO.getHttpMethod(), requestLoggerFilterDTO.getRequestURL());
                break;
            case DEBUG_EXCLUDED:
                log.debug("Started {} - {} but required HIDE into Info Rest Logger", requestLoggerFilterDTO.getHttpMethod(), requestLoggerFilterDTO.getRequestURL());
                break;
            case DEBUG_UNDEFINED:
                log.debug("Started {} - {} but undefined Show/Hide Info Rest Logger", requestLoggerFilterDTO.getHttpMethod(), requestLoggerFilterDTO.getRequestURL());
                break;
        }
    }

    protected long logFinishedExecution(RequestLoggerFilterDTO requestLoggerFilterDTO) {
        LogAction action = determineLogAction(requestLoggerFilterDTO.getRequestURL());
        long duration = Duration.between(requestLoggerFilterDTO.getStart(), Instant.now()).toMillis();

        switch (action) {
            case INFO:
                log.info("Finished {} - {} took {}ms with status {}",
                    requestLoggerFilterDTO.getHttpMethod(), requestLoggerFilterDTO.getRequestURL(), duration,
                    requestLoggerFilterDTO.getHttpStatus());
                break;
            case DEBUG_EXCLUDED:
                log.debug("Finished request {} - {} took {}ms with status {} but required HIDE into Info Rest Logger",
                    requestLoggerFilterDTO.getHttpMethod(), requestLoggerFilterDTO.getRequestURL(), duration,
                    requestLoggerFilterDTO.getHttpStatus());
                break;
            case DEBUG_UNDEFINED:
                log.debug("Finished request {} - {} took {}ms with status {} but undefined Show/Hide Info Rest Logger",
                    requestLoggerFilterDTO.getHttpMethod(), requestLoggerFilterDTO.getRequestURL(), duration,
                    requestLoggerFilterDTO.getHttpStatus());
                break;
        }
        return duration;
    }

    private boolean containsMatchAllPattern(Set<Pattern> patterns) {
        Set<String> matchAllLiterals = Set.of(".*",".+","(?s).*","[\\s\\S]*","[\\d\\D]*","[\\w\\W]*");

        if (Objects.isNull(patterns) || patterns.isEmpty()) {
            return false;
        }

        return patterns.stream()
                       .anyMatch(pattern -> matchAllLiterals.contains(pattern.pattern()));
    }

    private LogAction determineLogAction(CharSequence url) {
        boolean isIncluded = this.includeAllUrls || urlMatchesAny(url, regexPatternIncludedEndpoints);
        boolean isExcluded = this.excludeAllUrls ||
                             (!this.excludeIsEmpty && urlMatchesAny(url, regexPatternExcludedEndpoints));

        if (isIncluded && !isExcluded) {
            return LogAction.INFO;
        }

        if(properties.getRegexRules().isLogUnmatchedAsDebug()) {
            if (isExcluded) {
                return LogAction.DEBUG_EXCLUDED;
            } else {
                return LogAction.DEBUG_UNDEFINED;
            }
        }

        return LogAction.NONE;
    }

    private boolean urlMatchesAny(CharSequence url, Set<Pattern> patterns) {

        if (!StringUtils.hasText(url)) {
            return false;
        }

        if (Objects.isNull(patterns) || patterns.isEmpty()) {
            return false;
        }

        for (Pattern pattern : patterns) {
            if (pattern == null) continue;

            Matcher matcher = pattern.matcher(url);
            if (matcher.matches()) {
                log.trace("URL {} matches pattern {}", url, pattern.pattern());
                return true;
            }
        }

        return false;
    }
}
