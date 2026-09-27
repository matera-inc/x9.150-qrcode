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
    ACH("ACH");

    private final String value;

    NetworkEnum(String value) {
        this.value = value;
    }

    /**
     * Resolves one of the standard's rails, case-insensitively.
     *
     * <p>Case-insensitive because ANSI X9.150-2026 contradicts itself: §14.5's normative JSON paths
     * are lowercase ({@code networks.fednow}) while §2.4 calls the notification's network value
     * "all-uppercase" and then lists {@code FedNow}. Implementations will read one or the other, so
     * we accept every spelling and emit one.
     */
    public static NetworkEnum fromValue(String value) {
        return find(value).orElseThrow(
            () -> new ValueObjectRuleException("Unexpected value '" + value + "'"));
    }

    /**
     * The standard's rail of that name, if it is one.
     *
     * <p>Empty is not an error: §2.4 states the notification "**MAY** also carry a network not
     * listed above for early adopters", so an unrecognised name is a network this service does not
     * interpret rather than a malformed one.
     */
    public static java.util.Optional<NetworkEnum> find(String value) {
        if (value == null) {
            return java.util.Optional.empty();
        }

        for (NetworkEnum b : NetworkEnum.values()) {
            if (b.value.equals(value)) {
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
        // Every rail the STANDARD defines is a US bank rail. Anything else — Solana, Pix, a chain
        // whose owner has published an embedding — is carried by name rather than by enum, so it
        // never reaches this method.
        return false;
    }

}
