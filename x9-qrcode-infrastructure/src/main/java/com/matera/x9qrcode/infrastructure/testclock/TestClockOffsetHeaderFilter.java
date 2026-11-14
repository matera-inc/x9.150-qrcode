/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testclock;

import com.matera.x9qrcode.domain.utils.BusinessClock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * While the business clock is shifted, every API response says so in {@code X-Test-Clock-Offset},
 * so nobody mistakes a moved clock for a real one (ADR-0033). The value is the offset in
 * <b>milliseconds</b>, signed: {@code X-Test-Clock-Offset: 86400000} is one day ahead. With no offset —
 * always, in a deployment where the test clock is off — the header is absent.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TestClockOffsetHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Test-Clock-Offset";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        long offsetMillis = BusinessClock.offsetMillis();
        if (offsetMillis != 0) {
            response.setHeader(HEADER, Long.toString(offsetMillis));
        }
        chain.doFilter(request, response);
    }

}
