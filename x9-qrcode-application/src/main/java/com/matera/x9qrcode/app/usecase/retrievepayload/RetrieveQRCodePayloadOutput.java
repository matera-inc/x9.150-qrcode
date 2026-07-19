/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.retrievepayload;

import com.matera.x9qrcode.app.dto.BillDTO;
import com.matera.x9qrcode.app.dto.CreditorDTO;
import com.matera.x9qrcode.app.dto.FormulaResultDTO;
import com.matera.x9qrcode.app.dto.PaymentMethodDTO;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RetrieveQRCodePayloadOutput(
    UUID id,
    UUID locationId,
    Integer revision,
    String qrCodeContent,
    OffsetDateTime createdAt,
    OffsetDateTime revisedAt,
    OffsetDateTime sentAt,
    OffsetDateTime validUntil,
    String status,
    CreditorDTO creditor,
    BillDTO billDTO,
    String unstructured,
    Map<String, String> additionalInformation,
    URI paymentNotification,
    List<PaymentMethodDTO> paymentMethods,
    FormulaResultDTO formulaResult
) {

}
