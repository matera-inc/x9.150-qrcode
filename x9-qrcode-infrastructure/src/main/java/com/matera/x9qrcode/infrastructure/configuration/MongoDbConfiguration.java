/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration;

import com.matera.x9qrcode.app.repository.QRCodeRepository;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.QRCodeMongoRepository;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.repository.PaymentEventMongoModelRepository;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.repository.QRCodeMongoModelRepository;
import com.matera.x9qrcode.infrastructure.service.events.PaymentEventDrain;
import com.matera.x9qrcode.infrastructure.service.events.PaymentEventDrainLock;
import com.matera.x9qrcode.infrastructure.service.events.PaymentEventReader;

import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.TransientTransactionRetry;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.isNull;

@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableMongoRepositories(basePackages = "com.matera.x9qrcode.infrastructure.persistence.mongodb.repository")
public class MongoDbConfiguration {

    /**
     * Retries a transaction MongoDB labelled {@code TransientTransactionError}.
     *
     * <p>Wired explicitly rather than component-scanned, so the one thing that makes it correct —
     * that it orders <em>outside</em> Spring's transaction advice — is visible here next to the
     * transaction manager rather than hidden in an annotation on the class.
     */
    /**
     * The lease that keeps exactly one instance draining.
     *
     * <p>Without it, {@code replicaCount: 1} is correctness rather than capacity — see ADR-0014.
     */
    @Bean
    public PaymentEventDrainLock paymentEventDrainLock(MongoTemplate mongoTemplate) {
        return new PaymentEventDrainLock(mongoTemplate);
    }

    @Bean
    public TransientTransactionRetry transientTransactionRetry() {
        log.info("Initializing transient transaction retry (max {} attempts).",
            TransientTransactionRetry.MAX_ATTEMPTS);

        return new TransientTransactionRetry();
    }

    @Bean
    public QRCodeRepository qrCodeMongoRepository(QRCodeMongoModelRepository qrCodeMongoModelRepository) {
        log.info("Initializing MongoDB QRCodeRepository.");
        return new QRCodeMongoRepository(qrCodeMongoModelRepository);
    }

    @Bean
    public PaymentEventDrain paymentEventDrain(MongoTemplate mongoTemplate,
                                               PaymentEventMongoModelRepository eventRepository) {
        return new PaymentEventDrain(mongoTemplate, eventRepository, paymentEventDrainLock(mongoTemplate));
    }

    @Bean
    public PaymentEventReader paymentEventReader(PaymentEventMongoModelRepository eventRepository) {
        return new PaymentEventReader(eventRepository);
    }

    @Bean
    public MongoCustomConversions customConversions() {
        return new MongoCustomConversions(List.of(new OffsetDateTimeToStringConverter(), new StringToOffsetDateTimeConverter()));
    }

    @Bean
    public MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }

    @Bean
    public ApplicationRunner mongoConnectionCheck(MongoTemplate mongoTemplate) {
        return args -> {
            try {
                mongoTemplate.getDb().withTimeout(2, TimeUnit.SECONDS).runCommand(new Document("ping", 1));
            } catch (Exception e) {
                throw new IllegalStateException("MongoDB is not available. Halting application startup.", e);
            }
        };
    }

    private static class OffsetDateTimeToStringConverter implements Converter<OffsetDateTime, String> {

        @Override
        public String convert(OffsetDateTime source) {
            if (isNull(source)) {
                return null;
            }

            return source.toString();
        }

    }

    private static class StringToOffsetDateTimeConverter implements Converter<String, OffsetDateTime> {

        @Override
        public OffsetDateTime convert(String source) {
            if (isNull(source)) {
                return null;
            }

            return OffsetDateTime.parse(source);
        }

    }

}
