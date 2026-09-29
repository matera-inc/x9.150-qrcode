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
import com.matera.x9qrcode.infrastructure.persistence.mongodb.model.QRCodeMongoPersistenceModel;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.mapper.QRCodeMongoEntityMapper;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.repository.QRCodeMongoModelRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@RequiredArgsConstructor
public class QRCodeMongoRepository implements QRCodeRepository {

    private final QRCodeMongoModelRepository qrCodeMongoModelRepository;

    @Override
    public QRCodeEntity save(QRCodeEntity qrCodeEntity) throws BusinessRuleException {
        try {
            QRCodeMongoPersistenceModel model = QRCodeMongoDocumentMapper.map(qrCodeEntity);

            preserveUndrainedOutbox(model);

            QRCodeMongoPersistenceModel saved = qrCodeMongoModelRepository.save(model);

            // Carry the new lock token back, or a second save in the same request would present a
            // token the store has already moved past and be refused as a concurrent modification by
            // the very request that made the first one.
            qrCodeEntity.applyLockVersion(saved.getLockVersion());

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

    /**
     * A save writes the whole document, so events the relay has not drained yet would be lost unless
     * they are carried forward. Reads the stored outbox and puts it in front of this unit of work's
     * new events, keeping emission order.
     *
     * <p>A drain running between this read and the write can resurrect an event it just removed.
     * That is harmless and deliberate: delivery is at-least-once, the event log keys on eventId, and
     * consumers deduplicate on it — so a resurrected event costs one redundant publish, never a
     * duplicate downstream. Losing an event would not be recoverable; re-sending one is.
     */
    private void preserveUndrainedOutbox(QRCodeMongoPersistenceModel model) {
        if (model.isNew()) {
            return;
        }

        qrCodeMongoModelRepository.findById(model.getId()).ifPresent(stored -> {
            if (isNull(stored.getOutbox()) || stored.getOutbox().isEmpty()) {
                return;
            }

            List<QRCodeMongoPersistenceModel.OutboxEvent> merged = new ArrayList<>(stored.getOutbox());
            if (nonNull(model.getOutbox())) {
                merged.addAll(model.getOutbox());
            }
            model.setOutbox(merged);
        });
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
