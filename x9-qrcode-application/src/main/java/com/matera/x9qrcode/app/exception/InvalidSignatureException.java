/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.exception;

/**
 * The JWS accompanying a request did not verify, so nothing in it may be acted on.
 *
 * <p>Distinct from a business-rule failure on purpose. An unverifiable signature is not a malformed
 * request that a caller can fix by correcting a field — it is a failure of authenticity, and the
 * payload behind it is unauthenticated input that may be an exploit attempt. It maps to 401 rather
 * than 400 so an operator can tell "your JSON is wrong" from "your signature is forged", which are
 * very different things to see in a log.
 */
public class InvalidSignatureException extends RuntimeException {

    public InvalidSignatureException(String message) {
        super(message);
    }

    public InvalidSignatureException(String message, Throwable cause) {
        super(message, cause);
    }

}
