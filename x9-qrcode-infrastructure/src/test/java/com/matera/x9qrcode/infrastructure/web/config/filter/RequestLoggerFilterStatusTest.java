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

    /**
     * <b>X9-SIG-060</b> — a refusal is logged as a refusal.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> The status was read BEFORE the filter chain ran, so every request was logged as 200 —
     * including refusals. An adopter reported a 500 our own access log showed as a success, which is
     * the worst state for an operator: the evidence disagrees with reality.
     */
    @Test
    void aRefusalIsLoggedAsARefusal() throws Exception {
        assertEquals(401, statusLoggedFor(401),
                "an unverifiable JWS is refused; the log must not call that a success");
    }

    /**
     * <b>X9-SIG-061</b> — a failure is logged as a failure.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> 500s invisible in the access log means nobody goes looking until a counterparty complains.
     */
    @Test
    void aFailureIsLoggedAsAFailure() throws Exception {
        assertEquals(500, statusLoggedFor(500),
                "the case that misled a debugging session: a 500 reported as 200");
    }

    /**
     * <b>X9-SIG-062</b> — a success is still logged as a success.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> The control. A filter that logged everything as 500 would satisfy the two above.
     */
    @Test
    void aSuccessIsStillLoggedAsASuccess() throws Exception {
        assertEquals(200, statusLoggedFor(200));
    }

    /**
     * <b>X9-SIG-063</b> — an exception escaping the chain is logged as a 500.
     *
     * <p><b>Source:</b> Mechanism.
     *
     * <p><b>Why:</b> The first fix covered handled errors only. An exception escaping the chain still logged 200,
     * so the half-fix left exactly the case an operator most needs — found by an adopter, not by us.
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
