/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.exception;

public class BusinessRuleException extends RuntimeException {

    private final String field;

    public BusinessRuleException(String message) {
        super(message);
        this.field = null;
    }

    public BusinessRuleException(Throwable throwable, String message) {
        super(message, throwable);
        this.field = null;
    }

    public BusinessRuleException(String field, String message) {
        super(message);
        this.field = field;
    }

    public BusinessRuleException(Throwable throwable, String field, String message) {
        super(message, throwable);
        this.field = field;
    }

    public String field() {
        return field;
    }

}
