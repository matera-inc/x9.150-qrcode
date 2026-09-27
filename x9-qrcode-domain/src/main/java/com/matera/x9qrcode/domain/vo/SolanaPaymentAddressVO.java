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
import static java.util.Objects.nonNull;

/**
 * A Solana payment method: where to send the funds, and what to say when you do.
 *
 * <p>These are the fields the <b>Solana Foundation</b> published for X9.150 — see
 * {@code official-spec/SOLANA-FIELDS.md}. The standard fixes only where a network object hangs
 * (§14.5 defers the contents to "network documentation"), so the authority here is the Foundation's.
 *
 * <p>That distinction is not academic. Before the publication existed, this service modelled a
 * blockchain method as a lone {@code walletAddress} with no memo. Both halves of that guess were
 * wrong, which is exactly what ADR-0010 was written to prevent.
 */
public record SolanaPaymentAddressVO(String recipient, String memo) {

    /**
     * Base58, 32–44 characters.
     *
     * <p>The published table gives the length as 44. Read as a maximum, not an exact width: Base58
     * is variable-length, a 32-byte public key encodes to 43 or 44 characters, and roughly one
     * address in twenty-nine lands on 43. Demanding exactly 44 would reject real wallets in the name
     * of a stricter reading of the table. The floor admits the all-zero system address.
     */
    private static final Pattern BASE58_RECIPIENT = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");

    private static final int MAX_MEMO_LENGTH = 100;

    public SolanaPaymentAddressVO {
        if (isNull(recipient)) {
            throw new ValueObjectRuleException("Solana recipient must not be null.");
        }

        if (!BASE58_RECIPIENT.matcher(recipient).matches()) {
            throw new ValueObjectRuleException(
                "Solana recipient must be a Base58 address of 32 to 44 characters.");
        }

        if (nonNull(memo) && memo.length() > MAX_MEMO_LENGTH) {
            throw new ValueObjectRuleException(
                "Solana memo must not exceed %d characters.".formatted(MAX_MEMO_LENGTH));
        }
    }

}
