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

    /**
     * The MongoDB the whole integration suite runs against.
     *
     * <p><b>Pinned, and pinned to 7.0 on purpose.</b> MongoDB 8.x refuses to start on Linux kernels
     * 6.19 and newer — a deliberate startup check, not a crash: its bundled tcmalloc violates the
     * kernel's rseq ABI, so it exits rather than risk corruption (SERVER-121912). Because this
     * container starts in a static initialiser, an 8.x image means the entire suite fails before a
     * single test runs, and the failure reads as a broken test harness rather than as a MongoDB
     * version problem.
     *
     * <p>That kernel is not exotic — it arrives on Ubuntu 24.04 through the HWE track, which rolls
     * forward unattended — so this is currently intermittent across contributors' machines and will
     * become the common case.
     *
     * <p><b>Keep this tag identical to the one in {@code docker-compose.yml} and
     * {@code docker-compose.prod.yml}.</b> Testing against a different MongoDB from the one the
     * compose files tell people to run is a difference that only shows up in somebody else's
     * deployment.
     */
    private static final String MONGODB_IMAGE = "mongo:7.0.45";

    private static final MongoDBContainer mongoDBContainer;

    static {
        mongoDBContainer = new MongoDBContainer(MONGODB_IMAGE).withExposedPorts(27017);
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
