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
    POLYGON("Polygon"),
    SOLANA("Solana"),
    ETHEREUM("Ethereum"),
    BITCOIN("Bitcoin"),
    BASE("Base"),
    XRP("XRP"),
    ARC("Arc");

    private final String value;

    NetworkEnum(String value) {
        this.value = value;
    }

    public static NetworkEnum fromValue(String value) {
        for (NetworkEnum b : NetworkEnum.values()) {
            if (b.value.equals(value)) {
                return b;
            }
        }

        throw new ValueObjectRuleException("Unexpected value '" + value + "'");
    }

    public String value() {
        return value;
    }

    /**
     * Whether this rail is a public blockchain, as opposed to a US bank rail.
     *
     * <p>Deliberately an exhaustive switch expression rather than a list: adding a rail to this enum
     * without classifying it becomes a COMPILE error, not a silent fall-through. A silent
     * fall-through is exactly how Base, XRP and Arc came to be accepted and ignored.
     */
    public boolean isBlockchain() {
        return switch (this) {
            case RTP, FEDNOW, ACH -> false;
            case POLYGON, SOLANA, ETHEREUM, BITCOIN, BASE, XRP, ARC -> true;
        };
    }

}
