/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.patchqrcode;

import com.matera.x9qrcode.app.dto.BillUpdateDTO;
import com.matera.x9qrcode.app.dto.PaymentMethodUpdateDTO;
import com.matera.x9qrcode.app.usecase.PartialInput;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record PatchQRCodeInput(
    String id,
    PartialInput<String> locationId,
    PartialInput<OffsetDateTime> validUntil,
    PartialInput<BillUpdateDTO> billUpdateDTO,
    PartialInput<String> unstructured,
    PartialInput<Map<String, String>> additionalInformationMap,
    List<PaymentMethodUpdateDTO> paymentMethodUpdateDTOList
) {

}
