/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.persistence.mongodb.repository;

import com.matera.x9qrcode.infrastructure.persistence.mongodb.model.QRCodeMongoPersistenceModel;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;
import java.util.UUID;

public interface QRCodeMongoModelRepository extends MongoRepository<QRCodeMongoPersistenceModel, UUID> {

    Optional<QRCodeMongoPersistenceModel> findByIdAndRevision(UUID id, Integer revision);

    Optional<QRCodeMongoPersistenceModel> findByLocationId(UUID id);

}
