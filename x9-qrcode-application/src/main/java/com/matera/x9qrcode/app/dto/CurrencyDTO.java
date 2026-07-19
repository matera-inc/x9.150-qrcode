/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record CurrencyDTO(
    String currency,
    OffsetDateTime validUntil,
    Long amount,
    CurrencyEditableDTO currencyEditableDTO,
    List<PaymentMethodDTO> paymentMethodDTOList
) {

}
