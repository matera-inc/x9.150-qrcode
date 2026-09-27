/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.service;

import com.matera.x9qrcode.app.dto.PaymentNotificationDataDTO;

import java.net.URI;
import java.util.UUID;

/**
 * Delivers a payment notification to the payee, signed.
 *
 * <p>The mirror of the endpoint that receives them. A payer's software composes the notification, and
 * this signs it with the deployment's own X9 certificate and posts it — so a PSP integrating against
 * X9.150 never has to build a JWS, manage a keystore, or learn the {@code crit} header rules.
 *
 * <p>Distinct from the event stream, which stays pull-only
 * ({@code docs/adr/0001-events-leave-by-pull-not-push.md}). That carries events to the system this
 * deployment serves; this carries a notification to <em>somebody else's</em> deployment. Different
 * direction, different counterparty, different contract.
 */
public interface QRCodeOutboundNotificationService {

    /**
     * @param notification the notification body, in the shape ANSI X9.150 §2 defines
     * @param endpoint     where the payee said to send it, taken from the payload the payer fetched
     * @param correlationId ties this call to the payer's own request
     * @return the payee's verdict, verbatim
     */
    OutboundNotificationResult send(PaymentNotificationDataDTO notification, URI endpoint, UUID correlationId);

    /**
     * What the payee answered.
     *
     * <p>Returned rather than interpreted. A pre-payment is a request for permission and its status
     * is the answer the payer must act on; turning a refusal into an exception here would oblige
     * every caller to unpick it again.
     */
    record OutboundNotificationResult(int statusCode, String body) {

        public boolean accepted() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

}
