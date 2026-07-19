/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.service.thirdparty.jackson;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import org.openapitools.jackson.nullable.JsonNullableModule;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;

import static com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ObjectMapperFactory {

    // ANSI X9.150 Table 2 timestamp format. Per the standard:
    //   "Conforms to ISO 8601 standard. Value SHALL end with Z and follow YYYY-MM-DDThh:mm:ss[.fff]Z.
    //    Fractional seconds MAY be present (1-3 digits)."
    // So: the value MUST end with a literal "Z" (offsets such as +00:00 are rejected), and the
    // fractional part is optional but limited to 1-3 digits (".1Z"/".12Z"/".123Z" ok; ".1234Z" rejected).
    // A dedicated formatter is required because Jackson's default only accepts 0/3/6/9 fractional
    // digits, so it wrongly rejects the standard's 1- and 2-digit fractions.
    private static final DateTimeFormatter X9_UTC_TIMESTAMP = new DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 3, true).optionalEnd()
        .appendLiteral('Z')
        .parseDefaulting(ChronoField.OFFSET_SECONDS, 0)
        .toFormatter();

    private static final ObjectMapper INSTANCE = newInstance();

    public static ObjectMapper getInstance() {
        return INSTANCE;
    }

    private static ObjectMapper newInstance() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        objectMapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(WRITE_BIGDECIMAL_AS_PLAIN, true);
        objectMapper.setTimeZone(TimeZone.getDefault());

        // Serialize OffsetDateTime as UTC ISO-8601 truncated to milliseconds — "Z"-terminated with
        // up to 3 fractional-second digits (ANSI X9.150 Table 2). Avoids nanosecond output while
        // leaving whole-second timestamps (e.g. ...59Z) unchanged. Input parsing uses
        // X9_UTC_TIMESTAMP above (literal Z, 1-3 fractional digits).
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        javaTimeModule.addSerializer(OffsetDateTime.class, new JsonSerializer<>() {
            @Override
            public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
                gen.writeString(value.toInstant().truncatedTo(ChronoUnit.MILLIS).toString());
            }
        });
        javaTimeModule.addDeserializer(OffsetDateTime.class, new JsonDeserializer<>() {
            @Override
            public OffsetDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return OffsetDateTime.parse(p.getText(), X9_UTC_TIMESTAMP);
            }
        });
        objectMapper.registerModule(javaTimeModule);
        objectMapper.registerModule(new JsonNullableModule());

        return objectMapper;
    }

}
