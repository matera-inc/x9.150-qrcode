/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration;

import com.matera.x9qrcode.domain.service.AllowListSupportedCurrencyPolicy;
import com.matera.x9qrcode.domain.service.CurrencyMixPolicy;
import com.matera.x9qrcode.domain.service.PeggedCurrencyMixPolicy;
import com.matera.x9qrcode.domain.service.SupportedCurrencyPolicy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

/**
 * Loads the currency configuration from JSON and wires the two domain currency policies:
 * {@link CurrencyMixPolicy} (which currencies may appear together) and
 * {@link SupportedCurrencyPolicy} (which currencies are accepted at all).
 * <p>
 * Both files are strict, standard JSON — an object with one array plus an optional {@code _comment}
 * documentation key that the loader ignores; no relaxed-parser features (e.g. comment parsing) are
 * required. Their locations are overridable via {@code x9.currency.pegged-currencies-location} and
 * {@code x9.currency.supported-currencies-location}.
 */
@Configuration(proxyBeanMethods = false)
public class CurrencyConfiguration {

    static final String DEFAULT_PEGGED_CURRENCIES_LOCATION = "classpath:pegged-currencies.json";
    static final String DEFAULT_SUPPORTED_CURRENCIES_LOCATION = "classpath:supported-currencies.json";

    @Bean
    public CurrencyMixPolicy currencyMixPolicy(
        ResourceLoader resourceLoader,
        ObjectMapper objectMapper,
        @Value("${x9.currency.pegged-currencies-location:" + DEFAULT_PEGGED_CURRENCIES_LOCATION + "}") String location) {

        List<Set<String>> peggedGroups = loadPeggedCurrencyGroups(resourceLoader.getResource(location), objectMapper);

        return new PeggedCurrencyMixPolicy(peggedGroups);
    }

    @Bean
    public SupportedCurrencyPolicy supportedCurrencyPolicy(
        ResourceLoader resourceLoader,
        ObjectMapper objectMapper,
        @Value("${x9.currency.supported-currencies-location:" + DEFAULT_SUPPORTED_CURRENCIES_LOCATION + "}") String location) {

        return new AllowListSupportedCurrencyPolicy(
            loadSupportedCurrencies(resourceLoader.getResource(location), objectMapper));
    }

    /**
     * Reads the supported-currencies JSON. A missing or empty {@code supported} array leaves the
     * policy with an empty allow list, which disables the check.
     */
    static List<String> loadSupportedCurrencies(Resource resource, ObjectMapper objectMapper) {
        try (InputStream inputStream = resource.getInputStream()) {
            SupportedCurrenciesFile file = objectMapper.readValue(inputStream, SupportedCurrenciesFile.class);

            if (isNull(file) || isNull(file.supported())) {
                return List.of();
            }

            return List.copyOf(file.supported());
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Unable to load supported currencies from resource: " + resource, e);
        }
    }

    /**
     * Reads the pegged-currency-groups JSON into a list of upper-cased {@link Set}s (for
     * case-insensitive comparison). The {@code _comment} documentation key is ignored.
     */
    static List<Set<String>> loadPeggedCurrencyGroups(Resource resource, ObjectMapper objectMapper) {
        try (InputStream inputStream = resource.getInputStream()) {
            PeggedCurrenciesFile file = objectMapper.readValue(inputStream, PeggedCurrenciesFile.class);

            if (isNull(file) || isNull(file.groups())) {
                return List.of();
            }

            return file.groups().stream()
                .filter(Objects::nonNull)
                .map(CurrencyConfiguration::normalizeGroup)
                .filter(group -> !group.isEmpty())
                .collect(Collectors.toUnmodifiableList());
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Unable to load pegged currency groups from resource: " + resource, e);
        }
    }

    private static Set<String> normalizeGroup(List<String> group) {
        return group.stream()
            .filter(currency -> Objects.nonNull(currency) && !currency.isBlank())
            .map(currency -> currency.trim().toUpperCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Strict-JSON shape of the pegged-currencies file. The {@code _comment} documentation key present
     * in the file is intentionally not mapped and ignored via {@link JsonIgnoreProperties}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PeggedCurrenciesFile(List<List<String>> groups) {
    }

    /**
     * Strict-JSON shape of the supported-currencies file. The {@code _comment} documentation key is
     * intentionally not mapped and ignored via {@link JsonIgnoreProperties}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SupportedCurrenciesFile(List<String> supported) {
    }

}
