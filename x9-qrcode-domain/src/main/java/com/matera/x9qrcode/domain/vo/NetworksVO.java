/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;

import java.util.Map;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Payment networks for a payment method. The bank rails (FedNow, RTP, ACH) and the interpreted
 * blockchains (Polygon, Solana, Ethereum, Bitcoin, Base, XRP, Arc) are modeled explicitly; any other
 * network is accepted and stored verbatim in {@code additionalProperties} without being interpreted.
 */
public record NetworksVO(
    BankPaymentAddressVO fedNow,
    BankPaymentAddressVO ach,
    BankPaymentAddressVO rtp,
    CryptoWalletPaymentAddressVO solana,
    Map<String, Object> additionalProperties
) {

    public NetworksVO {
        if (isNull(fedNow) &&
            isNull(ach) &&
            isNull(rtp) &&
            isNull(solana) &&
            (isNull(additionalProperties) || additionalProperties.isEmpty())) {
            throw new ValueObjectRuleException("At least one network must be provided.");
        }
    }


    /**
     * The crypto wallet this QR publishes for {@code network}, or null — including for bank rails,
     * which have no wallet. Exhaustive by construction, so a new rail cannot be forgotten here.
     */
    public CryptoWalletPaymentAddressVO cryptoAddressFor(NetworkEnum network) {
        return switch (network) {
            case SOLANA -> solana;
            case FEDNOW, RTP, ACH -> null;
        };
    }

    /** The bank address this QR publishes for {@code network}, or null for blockchain rails. */
    public BankPaymentAddressVO bankAddressFor(NetworkEnum network) {
        return switch (network) {
            case FEDNOW -> fedNow;
            case RTP -> rtp;
            case ACH -> ach;
            case SOLANA -> null;
        };
    }

    /** Whether this QR offers {@code network} at all. */
    public boolean supports(NetworkEnum network) {
        return nonNull(cryptoAddressFor(network)) || nonNull(bankAddressFor(network));
    }

}
