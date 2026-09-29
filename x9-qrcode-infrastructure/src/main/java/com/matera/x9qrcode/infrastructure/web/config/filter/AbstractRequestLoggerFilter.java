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
            .requestServletPath(httpServletRequest.getServletPath())
            .build();

        boolean threw = false;

        try {
            logStartedExecution(requestLoggerFilterDTO);
            filterChain.doFilter(httpServletRequest, httpServletResponse);
        } catch (Exception | Error e) {
            // An exception escaping the chain becomes a 500, but the CONTAINER sets that during the
            // ERROR dispatch — after this filter has unwound. Reading the response here would still
            // see the servlet default of 200, which is how a 500 came to be logged as a success.
            // Reading it late was only half the fix; this is the other half.
            threw = true;
            throw e;
        } finally {
            int status = httpServletResponse.getStatus();
            requestLoggerFilterDTO.setHttpStatus(
                threw && status < HttpServletResponse.SC_BAD_REQUEST
                    ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                    : status);

            long duration = logFinishedExecution(requestLoggerFilterDTO);
            afterRequestLoggerFilterHook.execute(requestLoggerFilterDTO, duration);
        }
    }

    protected abstract void logStartedExecution(RequestLoggerFilterDTO requestLoggerFilterDTO);
    protected abstract long logFinishedExecution(RequestLoggerFilterDTO requestLoggerFilterDTO);
}
