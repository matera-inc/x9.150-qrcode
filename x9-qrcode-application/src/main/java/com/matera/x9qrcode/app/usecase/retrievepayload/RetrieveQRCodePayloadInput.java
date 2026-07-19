/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.usecase.retrievepayload;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record RetrieveQRCodePayloadInput(String uuid, LocalDate dateForPayment) {

    public OffsetDateTime getZonedDateForPayment() {
        return dateForPayment != null
            ? dateForPayment.atStartOfDay().atZone(ZoneOffset.UTC).toOffsetDateTime()
            : OffsetDateTime.now(ZoneOffset.UTC);
    }

}
