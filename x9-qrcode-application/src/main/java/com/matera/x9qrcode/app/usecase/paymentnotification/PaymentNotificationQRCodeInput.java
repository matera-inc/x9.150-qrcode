/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.paymentnotification;

import com.matera.x9qrcode.app.dto.PaymentNotificationDataDTO;

/**
 * A payment notification, together with who signed it.
 *
 * @param signerSubject the subject of the certificate whose chain was validated when the JWS was
 *                      verified. Carried alongside the payload because the payload alone cannot
 *                      establish who sent it — {@code payer.info} is a field the sender filled in.
 *                      Null when the signature path could not name a subject, which reads as "no
 *                      identity established" and so matches no existing reservation.
 */
public record PaymentNotificationQRCodeInput(PaymentNotificationDataDTO paymentNotificationData,
                                             String signerSubject) {
}
