/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.request;

import com.matera.x9qrcode.app.dto.enumerated.QRCodeStatusEnumDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.StatusUpdateDTO;
import com.matera.x9qrcode.app.usecase.updatestatus.UpdateQRCodeStatusInput;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;

import static java.util.Objects.isNull;

public final class UpdateQRCodeStatusRequestMapper {

    /**
     * Parses the revision out of an {@code If-Match} header.
     *
     * <p>Accepts what an HTTP client will actually send: a bare number, a quoted entity tag, or a
     * weak one. Absent or {@code *} means no condition — {@code *} being HTTP's "any current
     * representation", which every existing QR Code satisfies.
     *
     * <p>A malformed value is a client error rather than something to ignore: silently treating
     * {@code If-Match: abc} as unconditional would apply the very write the caller was trying to
     * make conditional.
     */
    public static String parseIfMatch(String ifMatch) {
        if (isNull(ifMatch) || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
            return null;
        }

        return ifMatch.trim().replaceFirst("^W/", "").replaceAll("^\"|\"$", "");
    }

    public static UpdateQRCodeStatusInput map(String id, StatusUpdateDTO statusUpdateDTO) {
        return map(id, statusUpdateDTO, null);
    }

    public static UpdateQRCodeStatusInput map(String id, StatusUpdateDTO statusUpdateDTO, String ifMatch) {
        String endToEndId = isNull(statusUpdateDTO.getEndToEndId())
            ? null
            : statusUpdateDTO.getEndToEndId().toString();

        String paymentNetwork = statusUpdateDTO.getNetwork();

        return new UpdateQRCodeStatusInput(
            id,
            QRCodeStatusEnumDTO.fromValue(statusUpdateDTO.getStatus().getValue()),
            endToEndId,
            paymentNetwork,
            parseIfMatch(ifMatch)
        );
    }

}
