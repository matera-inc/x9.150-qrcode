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
 */
public class NotificationUndeliverableException extends ServiceException {

    public NotificationUndeliverableException(String message, Throwable cause) {
        super(message, cause);
    }

}
