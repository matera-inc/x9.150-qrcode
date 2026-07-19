/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.persistence.mongodb.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.Map;

@Data
@Document(collection = "qrcode_history")
public class QRCodeHistoryMongoPersistenceModel {

    @Id
    private QRCodeHistoryID id;

    @Field(name = "qrcode_data")
    private Map<String, Object> data;

    @Data
    @EqualsAndHashCode
    public static class QRCodeHistoryID {
        private String qrcodeId;
        private Integer revision;
    }
}
