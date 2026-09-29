/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.config.filter;

import com.matera.x9qrcode.infrastructure.web.config.dto.RequestLoggerFilterDTO;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the access log says happened has to be what happened.
 *
 * <p>The status used to be read when the log record was built — before the request had been handled
 * — so it was the servlet's initial 200 on every line, whatever was finally sent. That is not a
 * cosmetic defect: it is a log that reports success during an outage. It cost a real debugging
 * session, where a payee returning 500 was logged as 200 and the fault was looked for on the wrong
 * side of the connection.
 */
class RequestLoggerFilterStatusTest {

    /** A minimal filter that records the DTO it was asked to log. */
    private static final class CapturingFilter extends AbstractRequestLoggerFilter {

        private final AtomicReference<Integer> loggedStatus = new AtomicReference<>();

        private CapturingFilter() {
            this.afterRequestLoggerFilterHook = (dto, duration) -> { };
        }

        @Override
        protected void logStartedExecution(RequestLoggerFilterDTO dto) {
            // nothing to record on the way in
        }

        @Override
        protected long logFinishedExecution(RequestLoggerFilterDTO dto) {
            loggedStatus.set(dto.getHttpStatus());
            return 0L;
        }
    }

    private static int statusLoggedFor(int statusTheHandlerSets) throws Exception {
        CapturingFilter filter = new CapturingFilter();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/pub/api/v1/payment-notification");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain handler = (req, res) -> ((MockHttpServletResponse) res).setStatus(statusTheHandlerSets);

        filter.doFilter(request, response, handler);

        return filter.loggedStatus.get();
    }

    @Test
    void aRefusalIsLoggedAsARefusal() throws Exception {
        assertEquals(401, statusLoggedFor(401),
                "an unverifiable JWS is refused; the log must not call that a success");
    }

    @Test
    void aFailureIsLoggedAsAFailure() throws Exception {
        assertEquals(500, statusLoggedFor(500),
                "the case that misled a debugging session: a 500 reported as 200");
    }

    @Test
    void aSuccessIsStillLoggedAsASuccess() throws Exception {
        assertEquals(200, statusLoggedFor(200));
    }

    /**
     * The half the first fix missed, found by an adopter running against a real deployment.
     *
     * <p>Reading the status late is not enough when the handler THROWS. The container turns an
     * escaped exception into a 500 during the ERROR dispatch, which happens after this filter has
     * unwound — so at the moment the filter reads it, the response still carries the servlet default
     * of 200. A genuine 500 was logged as a success, and the first version of this test never caught
     * it because its stub chain always returned normally.
     */
    @Test
    void anEscapedExceptionIsLoggedAsA500() {
        CapturingFilter filter = new CapturingFilter();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payment-request");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain explodes = (req, res) -> {
            throw new IllegalStateException("something the handler did not expect");
        };

        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response, explodes),
                "the filter must not swallow the exception it reports");

        assertEquals(500, filter.loggedStatus.get(),
                "an unhandled exception is a 500; logging it as 200 hides outages from the access log");
    }

}
