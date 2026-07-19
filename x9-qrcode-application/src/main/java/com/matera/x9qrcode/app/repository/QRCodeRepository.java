/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.repository;

import com.matera.x9qrcode.app.exception.QRCodeEntityNotFoundException;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.vo.LocationIdVO;
import com.matera.x9qrcode.domain.vo.QRCodeIdVO;

import java.util.Optional;

public interface QRCodeRepository {

    QRCodeEntity save(QRCodeEntity qrCodeEntity) throws BusinessRuleException;

    QRCodeEntity findById(QRCodeIdVO id) throws QRCodeEntityNotFoundException, BusinessRuleException;

    QRCodeEntity findByIdAndRevision(QRCodeIdVO id, Integer revision) throws QRCodeEntityNotFoundException, BusinessRuleException;

    QRCodeEntity findByLocationId(LocationIdVO id) throws QRCodeEntityNotFoundException, BusinessRuleException;

    Optional<QRCodeEntity> findOptionalByLocation(LocationIdVO id);

}
