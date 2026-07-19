/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration;

import com.matera.x9qrcode.app.repository.QRCodeRepository;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.QRCodeMongoRepository;
import com.matera.x9qrcode.infrastructure.persistence.mongodb.repository.QRCodeMongoModelRepository;

import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.mongodb.MongoDatabaseFactory;
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

    @Bean
    public QRCodeRepository qrCodeMongoRepository(QRCodeMongoModelRepository qrCodeMongoModelRepository) {
        log.info("Initializing MongoDB QRCodeRepository.");
        return new QRCodeMongoRepository(qrCodeMongoModelRepository);
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
