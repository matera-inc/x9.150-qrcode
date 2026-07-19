/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.config;

import com.matera.x9qrcode.infrastructure.service.thirdparty.jackson.ObjectMapperFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class RestBaseConfiguration {

    @Bean
    @Primary
    public RestClient restClient() {
        return RestClient.create();
    }

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return ObjectMapperFactory.getInstance();
    }

}
