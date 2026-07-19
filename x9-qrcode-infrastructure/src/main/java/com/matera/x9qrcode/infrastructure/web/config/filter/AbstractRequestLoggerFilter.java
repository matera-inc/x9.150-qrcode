/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.config.filter;

import com.matera.x9qrcode.infrastructure.web.config.dto.RequestLoggerFilterDTO;
import com.matera.x9qrcode.infrastructure.web.config.property.RestLoggerFilterProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

@Slf4j
public abstract class AbstractRequestLoggerFilter extends OncePerRequestFilter {

    @Autowired
    protected RestLoggerFilterProperties properties;

    @Autowired
    protected AfterRequestLoggerFilterHook afterRequestLoggerFilterHook;

    @Override
    protected void doFilterInternal(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, FilterChain filterChain)
        throws ServletException, IOException {

        RequestLoggerFilterDTO requestLoggerFilterDTO = RequestLoggerFilterDTO.builder()
            .start(Instant.now())
            .httpMethod(httpServletRequest.getMethod())
            .requestURL(httpServletRequest.getRequestURL())
            .httpStatus(httpServletResponse.getStatus())
            .requestServletPath(httpServletRequest.getServletPath())
            .build();

        try {
            logStartedExecution(requestLoggerFilterDTO);
            filterChain.doFilter(httpServletRequest, httpServletResponse);
        } finally {
            long duration = logFinishedExecution(requestLoggerFilterDTO);
            afterRequestLoggerFilterHook.execute(requestLoggerFilterDTO, duration);
        }
    }

    protected abstract void logStartedExecution(RequestLoggerFilterDTO requestLoggerFilterDTO);
    protected abstract long logFinishedExecution(RequestLoggerFilterDTO requestLoggerFilterDTO);
}
