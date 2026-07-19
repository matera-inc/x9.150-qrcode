/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.service;

import com.matera.x9qrcode.app.exception.ServiceException;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;

public interface QRCodeEMVService {

    /**
     * Generates the EMV string content for the QR Code.
     */
    String generateQrCodeContent(QRCodeEntity qrCodeEntity) throws ServiceException;

    String extractPayloadUrl(String emv) throws ServiceException;

}
