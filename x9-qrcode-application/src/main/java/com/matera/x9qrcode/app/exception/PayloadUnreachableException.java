/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.exception;

/**
 * The payload could not be fetched at all — no answer, or an answer that was itself a failure.
 *
 * <p>This is the only case in which "try again later" is the right advice to a payer, which is why
 * it is kept apart from {@link PayloadRefusedException}. A decode that cannot tell the two apart
 * sends payers to retry a settled bill and to shrug at a tampered one.
 *
 * <p>Deliberately carries <b>no address</b>. The location being fetched is resolved against this
 * deployment's own internals before the call — on a single-host deployment it reads
 * {@code http://localhost:8080/...} — and that is nobody's business outside the process. It goes to
 * the log, where the operator who needs it can see it.
 */
public class PayloadUnreachableException extends ServiceException {

    public PayloadUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }

}
