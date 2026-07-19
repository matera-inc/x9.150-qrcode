/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.exception;

import com.matera.x9qrcode.domain.vo.LocationIdVO;
import com.matera.x9qrcode.domain.vo.QRCodeIdVO;

public class QRCodeEntityNotFoundException extends EntityNotFoundException {

    private static final String QRCODE_NOT_FOUND_WITH_ID_ERROR_MESSAGE = "Could not find QR code with id: %s";
    private static final String QRCODE_NOT_FOUND_WITH_LOCATION_ID_ERROR_MESSAGE = "Could not find QR code with locationId: %s";
    private static final String QRCODE_NOT_FOUND_WITH_ID_AND_REVISION_ERROR_MESSAGE = "Could not find QR code with id: %s and revision: %d";

    public QRCodeEntityNotFoundException(QRCodeIdVO qrCodeIdVO) {
        super(QRCODE_NOT_FOUND_WITH_ID_ERROR_MESSAGE.formatted(qrCodeIdVO.valueAsString()));
    }

    public QRCodeEntityNotFoundException(LocationIdVO locationIdVO) {
        super(QRCODE_NOT_FOUND_WITH_LOCATION_ID_ERROR_MESSAGE.formatted(locationIdVO.valueAsString()));
    }

    public QRCodeEntityNotFoundException(QRCodeIdVO qrCodeIdVO, Integer revision) {
        super(QRCODE_NOT_FOUND_WITH_ID_AND_REVISION_ERROR_MESSAGE.formatted(qrCodeIdVO.valueAsString(), revision));
    }

}
