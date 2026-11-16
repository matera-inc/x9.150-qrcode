/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.validation;

import java.util.List;

/**
 * A body read by hand (not bound by Spring, so not bean-validated) lacks a member it must carry: a
 * 400 {@code validation-error} naming each in Bean Validation's words ({@code "payment: must not be
 * null"}), as a bound body would be answered.
 */
public class MissingRequiredFieldsException extends RuntimeException {

    private static final String MUST_NOT_BE_NULL = "%s: must not be null";

    private final transient List<String> violations;

    public MissingRequiredFieldsException(List<String> fields) {
        super(String.join("; ", fields.stream().map(MUST_NOT_BE_NULL::formatted).toList()));
        this.violations = fields.stream().map(MUST_NOT_BE_NULL::formatted).toList();
    }

    public List<String> violations() {
        return violations;
    }

}
