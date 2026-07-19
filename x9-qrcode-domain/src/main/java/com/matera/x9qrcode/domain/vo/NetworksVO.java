/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import java.util.Map;

import static java.util.Objects.isNull;

/**
 * Payment networks for a payment method. The bank rails (FedNow, RTP, ACH) and the interpreted
 * blockchains (Polygon, Solana, Ethereum, Bitcoin, Base, XRP, Arc) are modeled explicitly; any other
 * network is accepted and stored verbatim in {@code additionalProperties} without being interpreted.
 */
public record NetworksVO(
    BankPaymentAddressVO fedNow,
    BankPaymentAddressVO ach,
    BankPaymentAddressVO rtp,
    CryptoWalletPaymentAddressVO polygon,
    CryptoWalletPaymentAddressVO solana,
    CryptoWalletPaymentAddressVO ethereum,
    CryptoWalletPaymentAddressVO bitcoin,
    CryptoWalletPaymentAddressVO base,
    CryptoWalletPaymentAddressVO xrp,
    CryptoWalletPaymentAddressVO arc,
    Map<String, Object> additionalProperties
) {

    public NetworksVO {
        if (isNull(fedNow) &&
            isNull(ach) &&
            isNull(rtp) &&
            isNull(polygon) &&
            isNull(solana) &&
            isNull(ethereum) &&
            isNull(bitcoin) &&
            isNull(base) &&
            isNull(xrp) &&
            isNull(arc) &&
            (isNull(additionalProperties) || additionalProperties.isEmpty())) {
            throw new ValueObjectRuleException("At least one network must be provided.");
        }
    }

}
