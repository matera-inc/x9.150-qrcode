/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.testing.database;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MongoDBContainer;

public class MongoDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final MongoDBContainer mongoDBContainer;

    static {
        mongoDBContainer = new MongoDBContainer("mongo:latest").withExposedPorts(27017);
        mongoDBContainer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(mongoDBContainer::stop));
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        TestPropertyValues
            .of(
                "spring.data.mongodb.uri=" + mongoDBContainer.getReplicaSetUrl()
            ).applyTo(applicationContext.getEnvironment());
    }

}
