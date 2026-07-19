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

    /** EVM-compatible address (0x + 40 hex) — Ethereum, Polygon, Base, Arc */
    private static final Pattern EVM_PATTERN = Pattern.compile("^0x[a-fA-F0-9]{40}$");

    /** Base58 address (32-44 chars) — Solana */
    private static final Pattern BASE58_PATTERN = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");

    /** Bech32 address (bc1 prefix) — Bitcoin */
    private static final Pattern BECH32_PATTERN = Pattern.compile("^bc1[a-zA-HJ-NP-Z0-9]{25,90}$");

    /** XRP Ledger address (r prefix, Base58) */
    private static final Pattern XRP_PATTERN = Pattern.compile("^r[1-9A-HJ-NP-Za-km-z]{24,34}$");

    public CryptoWalletPaymentAddressVO {
        if (isNull(walletAddress)) {
            throw new ValueObjectRuleException("Crypto wallet address must not be null.");
        }

        if (!EVM_PATTERN.matcher(walletAddress).matches()
                && !BASE58_PATTERN.matcher(walletAddress).matches()
                && !BECH32_PATTERN.matcher(walletAddress).matches()
                && !XRP_PATTERN.matcher(walletAddress).matches()) {
            throw new ValueObjectRuleException("Crypto wallet address format is invalid.");
        }
    }

}
