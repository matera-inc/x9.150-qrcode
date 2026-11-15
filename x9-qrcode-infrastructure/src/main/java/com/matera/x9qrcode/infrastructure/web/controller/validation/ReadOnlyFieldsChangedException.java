/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.validation;

import java.util.List;

/** A PATCH tried to change what a PATCH cannot change ({@link ReadOnlyPatchFields}): a 400 naming each. */
public class ReadOnlyFieldsChangedException extends RuntimeException {

    private final transient List<String> violations;

    public ReadOnlyFieldsChangedException(List<String> violations) {
        super(String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }

}
