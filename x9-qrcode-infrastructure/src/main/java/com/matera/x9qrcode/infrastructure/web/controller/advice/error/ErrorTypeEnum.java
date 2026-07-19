/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.advice.error;

import org.springframework.http.HttpStatus;

import java.net.URI;

public enum ErrorTypeEnum {
    METHOD_ARGUMENT_NOT_VALID("Validation Failed", URI.create("https://x9.matera.com/api/validation-error"), "One or more fields failed validation.", HttpStatus.BAD_REQUEST),
    CONSTRAINT_VALIDATION("Validation Failed", URI.create("https://x9.matera.com/api/validation-error"), "Constraint violation occurred.", HttpStatus.BAD_REQUEST),
    INVALID_HTTP_HEADER("Validation Failed", URI.create("https://x9.matera.com/api/validation-error"), "One or more headers failed validation.", HttpStatus.BAD_REQUEST),
    HTTP_MESSAGE_NOT_READABLE("Malformed JSON", URI.create("https://x9.matera.com/api/malformed-json"), "The request body is not readable or is incorrectly formatted.", HttpStatus.BAD_REQUEST),
    BUSINESS_RULE("Business Rule Violation", URI.create("https://x9.matera.com/api/business-rule-violation"), "A business rule was violated.", HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND("Resource not found", URI.create("https://x9.matera.com/api/resource-not-found"), "A resource was not found.", HttpStatus.NOT_FOUND);

    private final String title;
    private final URI uriType;
    private final String description;
    private final HttpStatus status;

    ErrorTypeEnum(String title, URI uriType, String description, HttpStatus status) {
        this.title = title;
        this.uriType = uriType;
        this.description = description;
        this.status = status;
    }

    public String title() {
        return title;
    }

    public URI uriType() {
        return uriType;
    }

    public String description() {
        return description;
    }

    public HttpStatus status() {
        return status;
    }
}
