/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.keystore;

public class KeystoreOperationFailedException extends RuntimeException {

    public KeystoreOperationFailedException(String message) {
        super(message);
    }

    public KeystoreOperationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
