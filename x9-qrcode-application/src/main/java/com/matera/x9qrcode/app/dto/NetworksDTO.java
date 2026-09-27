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
    // Solana is the one chain with a published embedding (official-spec/SOLANA-FIELDS.md), so it is
    // the one carried in a typed field. The six that used to sit here — polygon, ethereum, bitcoin,
    // base, xrp, arc — were modelled ahead of any publication and removed with ADR-0010; nothing
    // replaced them, because a rail we cannot validate is one we refuse (ADR-0012).
    private SolanaPaymentAddressDTO solana;
    private Map<String, Object> additionalProperties;

}
