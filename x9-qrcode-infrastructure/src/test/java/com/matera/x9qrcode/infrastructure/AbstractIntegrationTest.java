/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure;

import com.matera.x9qrcode.infrastructure.configuration.property.X9Properties;
import com.matera.x9qrcode.infrastructure.testing.database.DatabaseInitializer;
import com.matera.x9qrcode.infrastructure.testing.annotation.IntegrationTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.context.WebApplicationContext;

@Slf4j
@IntegrationTest
@ContextConfiguration(initializers = DatabaseInitializer.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    private X9Properties x9Properties;

    @Autowired
    protected WebApplicationContext context;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.webAppContextSetup(context);
    }

}
