/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.exception;

/**
 * The payee's whole answer was not received: it could not be reached, closed the connection without
 * answering, or answered too late. The payee may still have received the notification, so this does
 * not mean that nothing was delivered; retrying is safe, because a payee must tolerate seeing the same
 * notification again.
 *
 * <p>Separate from a refusal on purpose. "The payee said no" and "we never got an answer" look alike
 * from a distance and call for opposite things: one is a decision to act on, the other is a delivery
 * to attempt again. Collapsing them would leave a payer unable to tell a rejection from an outage.
 *
 * <p>Also raised, with its own {@link #detail()}, for an answer that cannot be tied to the question:
 * a pre-commit notification whose answer carries no correlationId, or another one, has no answer
 * this service can trust (owner, 2026-11-15; ADR-0027).
 */
public class NotificationUndeliverableException extends ServiceException {

    /** The detail of a pre-commit answer that does not carry the correlationId sent. */
    public static final String DETAIL_BAD_CORRELATION_ID =
        "failed to pay - payee is not responding (bad correlationId)";

    private final String detail;

    public NotificationUndeliverableException(String message, Throwable cause) {
        super(message, cause);
        this.detail = null;
    }

    /**
     * @param detail the problem's detail, in place of the one for an answer not received
     */
    public NotificationUndeliverableException(String message, String detail) {
        super(message);
        this.detail = detail;
    }

    /** The problem's detail when it is not the usual one; null for the usual one. */
    public String detail() {
        return detail;
    }

}
