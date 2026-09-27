/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.dto;

import com.matera.x9qrcode.app.usecase.PartialInput;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class NetworksUpdateDTO {

    private PartialInput<BankPaymentAddressDTO> fedNow;
    private PartialInput<BankPaymentAddressDTO> rtp;
    private PartialInput<BankPaymentAddressDTO> ach;
    // As in NetworksDTO: the six unpublished chains went with ADR-0010, and Solana stays in the
    // shape its Foundation published rather than as a bare wallet address.
    private PartialInput<SolanaPaymentAddressDTO> solana;
    private PartialInput<Map<String, Object>> additionalProperties;

}
