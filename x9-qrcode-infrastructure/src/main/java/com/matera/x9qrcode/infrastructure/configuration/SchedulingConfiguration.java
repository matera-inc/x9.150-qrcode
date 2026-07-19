/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@RequiredArgsConstructor
public class SchedulingConfiguration {

    @PostConstruct
    void init() {
        log.info("Scheduling configuration initialized with TaskScheduler: {}", scheduledExecutorService.getClass().getName());
    }

    @Getter
    private final TaskScheduler scheduledExecutorService;

}
