/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.dto;

/** {@code protectionType} as the payee set it (X9.150 §14.5.x.3); null only for an older stored QR Code. */
public record BankPaymentAddressDTO(
    String routingNumber,
    String accountNumber,
    String protectionType
) {

    public BankPaymentAddressDTO(String routingNumber, String accountNumber) {
        this(routingNumber, accountNumber, null);
    }

}
