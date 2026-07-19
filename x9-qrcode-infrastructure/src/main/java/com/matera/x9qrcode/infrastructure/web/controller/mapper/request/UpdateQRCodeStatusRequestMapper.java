/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.request;

import com.matera.x9qrcode.app.dto.enumerated.NetworkEnumDTO;
import com.matera.x9qrcode.app.dto.enumerated.QRCodeStatusEnumDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.StatusUpdateDTO;
import com.matera.x9qrcode.app.usecase.updatestatus.UpdateQRCodeStatusInput;

import static java.util.Objects.isNull;

public final class UpdateQRCodeStatusRequestMapper {

    public static UpdateQRCodeStatusInput map(String id, StatusUpdateDTO statusUpdateDTO) {
        String endToEndId = isNull(statusUpdateDTO.getEndToEndId())
            ? null
            : statusUpdateDTO.getEndToEndId().toString();

        NetworkEnumDTO paymentNetwork = isNull(statusUpdateDTO.getNetwork())
            ? null
            : NetworkEnumDTO.fromValue(statusUpdateDTO.getNetwork().getValue());

        return new UpdateQRCodeStatusInput(
            id,
            QRCodeStatusEnumDTO.fromValue(statusUpdateDTO.getStatus().getValue()),
            endToEndId,
            paymentNetwork
        );
    }

}
