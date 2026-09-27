/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.config.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Builder
public class RequestLoggerFilterDTO {

    private final Instant start;
    private final String httpMethod;
    private final StringBuffer requestURL;
    private final String requestServletPath;

    /**
     * The response status, which is only known once the chain has run.
     *
     * <p>Not final for that reason. It used to be read when the DTO was built — before the request
     * was handled — so it was the servlet's initial 200 every time, and the access log reported
     * success for responses that were in fact 401 or 500. Whoever is reading these lines during an
     * incident is entitled to the status that was actually sent.
     */
    @Setter
    private Integer httpStatus;

}
