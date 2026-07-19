/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.retrieveqrcode;

import com.matera.x9qrcode.app.dto.BillDTO;
import com.matera.x9qrcode.app.dto.CreditorDTO;
import com.matera.x9qrcode.app.dto.LocationDTO;
import com.matera.x9qrcode.app.dto.PaymentDetailsDTO;
import com.matera.x9qrcode.app.dto.PaymentMethodDTO;
import com.matera.x9qrcode.app.dto.PaymentNotificationDTO;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RetrieveQRCodeOutput(
    UUID id,
    LocationDTO location,
    Integer revision,
    OffsetDateTime createdAt,
    OffsetDateTime revisedAt,
    OffsetDateTime sentAt,
    OffsetDateTime validUntil,
    String status,
    CreditorDTO creditor,
    BillDTO billDTO,
    String unstructured,
    Map<String, String> additionalInformation,
    PaymentNotificationDTO paymentNotification,
    List<PaymentMethodDTO> paymentMethods,
    PaymentDetailsDTO paymentDetails,
    String qrCodeContent
) {

}
