/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.ulid;

import com.matera.x9qrcode.domain.generator.IdGenerator;

import com.github.f4b6a3.ulid.UlidCreator;

import java.util.UUID;

public class ULIDGenerator implements IdGenerator<UUID> {

    @Override
    public UUID generate() {
        return UlidCreator.getMonotonicUlid().toUuid();
    }

}
