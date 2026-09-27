/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.dto;

/**
 * A Solana payment method, in the shape the Solana Foundation published for X9.150.
 *
 * <p>Distinct from {@link CryptoWalletPaymentAddressDTO}, which carries a bare address reported on a
 * notification. This one is what a QR Code publishes: where to pay, and the memo that lets the
 * transfer be matched back to it.
 */
public record SolanaPaymentAddressDTO(String recipient, String memo) {
}
