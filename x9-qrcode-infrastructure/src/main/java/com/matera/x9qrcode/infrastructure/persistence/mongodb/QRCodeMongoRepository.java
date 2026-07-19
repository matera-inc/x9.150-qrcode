/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.persistence.mongodb;

import com.matera.x9qrcode.app.exception.QRCodeEntityNotFoundException;
import com.matera.x9qrcode.app.repository.QRCodeRepository;
import com.matera.x9qrcode.domain.entity.QRCodeEntity;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.domain.vo.LocationIdVO;
import com.matera.x9qrcode.domain.vo.QRCodeIdVO;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.mapper.QRCodeMongoDocumentMapper;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.mapper.QRCodeMongoEntityMapper;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.repository.QRCodeMongoModelRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class QRCodeMongoRepository implements QRCodeRepository {

    private final QRCodeMongoModelRepository qrCodeMongoModelRepository;

    @Override
    public QRCodeEntity save(QRCodeEntity qrCodeEntity) throws BusinessRuleException {
        try {
            qrCodeMongoModelRepository.save(QRCodeMongoDocumentMapper.map(qrCodeEntity));

            return qrCodeEntity;
        } catch (DuplicateKeyException ex) {
            log.error("Duplicate key error while saving QRCodeEntity: {}", qrCodeEntity, ex);

            if (ex.getMessage().contains("locationId")) {
                throw new BusinessRuleException("location",
                    "QR Code with the same Location ID already exists: " + qrCodeEntity.getLocationId().value());
            }

            throw new BusinessRuleException(ex, "Illegal duplicate QRCode persistence within Id : " + qrCodeEntity.getId());
        }
    }

    @Override
    public QRCodeEntity findById(QRCodeIdVO id) throws QRCodeEntityNotFoundException {
        return qrCodeMongoModelRepository.findById(id.value())
            .map(QRCodeMongoEntityMapper::map)
            .orElseThrow(() -> new QRCodeEntityNotFoundException(id));
    }

    @Override
    public QRCodeEntity findByIdAndRevision(QRCodeIdVO id, Integer revision) throws QRCodeEntityNotFoundException {
        return qrCodeMongoModelRepository.findByIdAndRevision(id.value(), revision)
            .map(QRCodeMongoEntityMapper::map)
            .orElseThrow(() -> new QRCodeEntityNotFoundException(id));
    }

    @Override
    public QRCodeEntity findByLocationId(LocationIdVO id) throws QRCodeEntityNotFoundException {
        return qrCodeMongoModelRepository.findByLocationId(id.value())
            .map(QRCodeMongoEntityMapper::map)
            .orElseThrow(() -> new QRCodeEntityNotFoundException(id));
    }

    @Override
    public Optional<QRCodeEntity> findOptionalByLocation(final LocationIdVO id) {
        return qrCodeMongoModelRepository.findByLocationId(id.value())
                .map(QRCodeMongoEntityMapper::map);
    }

}
