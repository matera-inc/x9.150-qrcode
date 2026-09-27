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
    SolanaPaymentAddressVO solana,
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
    /**
     * The destination address this QR Code publishes for {@code networkName}, or null.
     *
     * <p>Looks the network up by NAME rather than by enum, because the set of networks is open:
     * only the standard's own rails are typed, and everything else — a chain whose owner published
     * an embedding, Pix, Zelle — travels in {@code additionalProperties} exactly as received.
     *
     * <p>The address is read from a {@code walletAddress} member by convention. That convention is
     * the placeholder for a declared pointer: once a network registry exists, each network states
     * where its destination lives instead of us assuming.
     */
    @SuppressWarnings("unchecked")
    public String destinationAddressFor(String networkName) {
        if (isNull(networkName)) {
            return null;
        }

        // Solana is interpreted, so its destination comes from the field its own publication names
        // — `recipient` — rather than from a convention we invented. See SOLANA-FIELDS.md.
        if (NetworkEnum.SOLANA.value().equalsIgnoreCase(networkName)) {
            return isNull(solana) ? null : solana.recipient();
        }

        if (isNull(additionalProperties)) {
            return null;
        }

        Object entry = additionalProperties.entrySet().stream()
            .filter(e -> networkName.equalsIgnoreCase(e.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);

        if (!(entry instanceof Map<?, ?> fields)) {
            return null;
        }

        Object address = ((Map<String, Object>) fields).get("walletAddress");

        return address instanceof String value ? value : null;
    }

    /** The bank address this QR publishes for {@code network}, or null for blockchain rails. */
    public BankPaymentAddressVO bankAddressFor(NetworkEnum network) {
        return switch (network) {
            case FEDNOW -> fedNow;
            case RTP -> rtp;
            case ACH -> ach;
            // Solana is a rail this service interprets, but not a bank one: it publishes a Base58
            // recipient, not a routing and account number. Callers asking for a bank address get
            // nothing, which is the truthful answer.
            case SOLANA -> null;
        };
    }

    /** Whether this QR Code offers {@code networkName}, interpreted rail or not. */
    public boolean supports(String networkName) {
        return NetworkEnum.find(networkName)
            .map(this::supports)
            .orElseGet(() -> nonNull(destinationAddressFor(networkName)));
    }

    /** Whether this QR Code offers a rail this service interprets. */
    public boolean supports(NetworkEnum network) {
        return switch (network) {
            case FEDNOW, RTP, ACH -> nonNull(bankAddressFor(network));
            // Not a bank address, so asking bankAddressFor would always answer no.
            case SOLANA -> nonNull(solana);
        };
    }

}
