/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.response;

import com.matera.x9qrcode.app.usecase.updatestatus.UpdateQRCodeStatusOutput;
import com.matera.x9qrcode.infrastructure.generated.dto.QRCodeStatusDTO;
import com.matera.x9qrcode.infrastructure.generated.dto.StatusUpdateResponseDTO;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UpdateQRCodeStatusResponseMapper {

    public static StatusUpdateResponseDTO map(UpdateQRCodeStatusOutput updateQRCodeStatusOutput) {
        StatusUpdateResponseDTO statusUpdateResponseDTO = new StatusUpdateResponseDTO();
        statusUpdateResponseDTO.setId(updateQRCodeStatusOutput.id());
        statusUpdateResponseDTO.setStatus(
            QRCodeStatusDTO.fromValue(updateQRCodeStatusOutput.status()));

        return statusUpdateResponseDTO;
    }

}
