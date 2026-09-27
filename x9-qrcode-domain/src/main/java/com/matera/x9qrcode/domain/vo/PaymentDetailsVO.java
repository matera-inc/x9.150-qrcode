/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;


/**
 * How a QR Code was paid.
 *
 * <p>{@code paymentNetwork} is a name rather than an enum, for the same reason the notification's is
 * (ANSI X9.150-2026 §2.4): the set of networks is open, so typing it closed would make a QR Code
 * unpayable on a rail the standard permits.
 */
public record PaymentDetailsVO(
    String endToEndId,
    String paymentNetwork
) {

}
