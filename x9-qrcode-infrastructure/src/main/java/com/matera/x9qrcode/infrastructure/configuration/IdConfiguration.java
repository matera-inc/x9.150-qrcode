/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration;

import com.matera.x9qrcode.domain.generator.IdGenerator;
import com.matera.x9qrcode.infrastructure.service.thirdparty.ulid.ULIDGenerator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class IdConfiguration {

    @Bean
    public IdGenerator<UUID> ulidGenerator() {
        return new ULIDGenerator();
    }

}
