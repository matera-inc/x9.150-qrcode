/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo.enumerated;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

public enum NetworkEnum {
    RTP("RTP"),
    FEDNOW("FedNow"),
    ACH("ACH"),
    SOLANA("Solana");

    private final String value;

    NetworkEnum(String value) {
        this.value = value;
    }

    /** As {@link #find}, but for callers to whom an unrecognised name is an error. */
    public static NetworkEnum fromValue(String value) {
        return find(value).orElseThrow(
            () -> new ValueObjectRuleException("Unexpected value '" + value + "'"));
    }

    /**
     * The standard's rail of that name, if it is one — matched <b>case-insensitively</b>.
     *
     * <p>This resolves the {@code $.payment.network} <em>value</em> of §2.4, which is the one field
     * ANSI X9.150-2026 is genuinely ambiguous about: §2.4 introduces its list as "exact,
     * all-uppercase values" and then gives {@code FedNow}, which is not. An implementer reading that
     * section can reasonably produce {@code FEDNOW} or {@code FedNow}, and both are defensible.
     *
     * <p>That value reaches us only from <b>outside</b> — a payer's notification, a settlement
     * system's status update. Their implementations are not ours to correct, and refusing a payment
     * over the case of a string we can resolve unambiguously would be indefensible. So we resolve
     * every spelling.
     *
     * <p>This says nothing about the {@code networks} object <b>keys</b>, a different field that
     * §14.5 spells {@code fednow}/{@code rtp}/{@code ach} throughout with no contradiction. Those
     * are declared in the OpenAPI contract and are not matched here at all.
     *
     * <p>Empty is not an error: §2.4 states the notification "<b>MAY</b> also carry a network not
     * listed above for early adopters", so an unrecognised name is a network this service does not
     * interpret rather than a malformed one.
     */
    public static java.util.Optional<NetworkEnum> find(String value) {
        if (value == null) {
            return java.util.Optional.empty();
        }

        for (NetworkEnum b : NetworkEnum.values()) {
            if (b.value.equalsIgnoreCase(value)) {
                return java.util.Optional.of(b);
            }
        }

        return java.util.Optional.empty();
    }

    public String value() {
        return value;
    }

    /**
     * Whether this rail is a public blockchain, as opposed to a US bank rail.
     *
     * <p>This enum holds only the rails ANSI X9.150 itself defines — FedNow, RTP and ACH — whose
     * structure the standard fixes. Every other network is open by design (§2.4: the notification
     * "MAY also carry a network not listed above"), so it is carried by name and never enumerated
     * here. A closed enum for an open set is what made Base, XRP and Arc silently unpayable.
     */
    public boolean isBlockchain() {
        // The rails ANSI X9.150 itself defines are US bank rails. Solana is here on a different
        // authority: its own published embedding (official-spec/SOLANA-FIELDS.md), which is the bar
        // ADR-0010 sets. Anything without one is still carried by name rather than by enum.
        return this == SOLANA;
    }

}
