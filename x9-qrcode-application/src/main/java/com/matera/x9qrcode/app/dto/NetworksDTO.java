/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class NetworksDTO {

    private BankPaymentAddressDTO fedNow;
    private BankPaymentAddressDTO rtp;
    private BankPaymentAddressDTO ach;
    private CryptoWalletPaymentAddressDTO polygon;
    private CryptoWalletPaymentAddressDTO solana;
    private CryptoWalletPaymentAddressDTO ethereum;
    private CryptoWalletPaymentAddressDTO bitcoin;
    private CryptoWalletPaymentAddressDTO base;
    private CryptoWalletPaymentAddressDTO xrp;
    private CryptoWalletPaymentAddressDTO arc;
    private Map<String, Object> additionalProperties;

}
