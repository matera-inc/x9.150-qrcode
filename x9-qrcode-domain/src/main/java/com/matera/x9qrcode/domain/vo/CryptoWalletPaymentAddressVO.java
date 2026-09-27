/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import java.util.regex.Pattern;

import static java.util.Objects.isNull;

/**
 * Blockchain payment method — carries only the receiving wallet address (no memo/tag fields).
 * Shared by Ethereum, Polygon, Solana, Bitcoin, Base, XRP and Arc.
 */
public record CryptoWalletPaymentAddressVO(String walletAddress) {

    /**
     * Base58 address, 32-44 chars — Solana.
     *
     * <p>Solana is the only blockchain this service interprets, because it is the only one whose
     * owner has published how it embeds in X9.150 (ADR-0010). Address formats for other chains are
     * not validated here because those chains are not modelled: they travel uninterpreted through
     * the networks object's additionalProperties, where their shape is the payer's business.
     */
    private static final Pattern BASE58_PATTERN = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");

    public CryptoWalletPaymentAddressVO {
        if (isNull(walletAddress)) {
            throw new ValueObjectRuleException("Crypto wallet address must not be null.");
        }

        if (!BASE58_PATTERN.matcher(walletAddress).matches()) {
            throw new ValueObjectRuleException(
                "Crypto wallet address format is invalid. Expected a Base58 Solana address.");
        }
    }

}
