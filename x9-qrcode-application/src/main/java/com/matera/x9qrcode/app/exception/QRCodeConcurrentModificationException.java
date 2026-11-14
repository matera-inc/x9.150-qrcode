/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.exception;

/**
 * A save lost a race: the QR Code was written by somebody else between this request reading it and
 * saving it, so the save was refused rather than overwriting their change.
 *
 * <p>Thrown by the repository adapter in place of its store's own optimistic-lock failure, so the
 * application layer can react (re-read and decide again) without knowing which store it runs on.
 */
public class QRCodeConcurrentModificationException extends RuntimeException {

    public QRCodeConcurrentModificationException(String message, Throwable cause) {
        super(message, cause);
    }

}
