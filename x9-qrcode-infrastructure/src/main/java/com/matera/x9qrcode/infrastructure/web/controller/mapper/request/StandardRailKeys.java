/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.request;

import com.matera.x9qrcode.app.dto.BankPaymentAddressDTO;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;
import com.matera.x9qrcode.infrastructure.configuration.property.NetworksProperties;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Accepts the standard's rails under any spelling.
 *
 * <p>ANSI X9.150-2026 contradicts itself on case: §14.5's normative JSON paths are lowercase
 * ({@code networks.fednow}) while §2.4 calls the notification's network value "all-uppercase" and
 * then lists {@code FedNow}. Implementers will read one or the other.
 *
 * <p>That ambiguity is a reason to be forgiving <b>at the boundary we do not control</b> — a payment
 * notification from a third-party payer, which is read case-insensitively elsewhere. It is not a
 * reason to be forgiving here. A create or patch request comes from inside this ecosystem, and
 * within it there is exactly one spelling of a rail: the configured key, which is also the only one
 * we ever emit. Accepting {@code FedNow}, {@code FEDNOW} and {@code fedNow} for a field we will only
 * ever write as {@code fednow} does not prevent drift, it hides it — until some downstream consumer
 * that is not so relaxed meets a payload we accepted and it did not.
 *
 * <p>So: a rail under the wrong spelling is <b>refused, naming both spellings</b>, rather than
 * quietly promoted. The caller changes one string and is then aligned with what they will read back.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StandardRailKeys {

    /**
     * The rail's address, accepted only under {@code expectedKey} — the spelling this deployment
     * uses. Every spelling of this rail is removed from {@code others} either way, so a rail is
     * never also carried as an uninterpreted network.
     *
     * @param typed       the value the contract's own property captured, i.e. the canonical spelling
     * @param network     the rail, in any case
     * @param expectedKey the one spelling accepted, from {@link NetworksProperties#keyFor}
     * @throws BusinessRuleException if the rail was sent under any other spelling
     */
    public static BankPaymentAddressDTO bankAddress(BankPaymentAddressDTO typed,
                                                    String network,
                                                    String expectedKey,
                                                    Map<String, Object> others) {
        String canonicalKey = NetworksProperties.canonical(network);

        // Take every spelling out first, so rejectUnsupported() cannot also complain about a rail
        // we are about to give a far more useful message for.
        Map<String, Object> variants = takeAllCaseInsensitively(others, canonicalKey);

        Object underExpectedKey = variants.remove(expectedKey);

        // The generated property captures the canonical spelling and nothing else, so a non-null
        // `typed` means the caller wrote the canonical key.
        if (nonNull(typed) && !canonicalKey.equals(expectedKey)) {
            throw wrongSpelling(canonicalKey, expectedKey);
        }

        if (!variants.isEmpty()) {
            throw wrongSpelling(variants.keySet().iterator().next(), expectedKey);
        }

        if (canonicalKey.equals(expectedKey)) {
            return typed;
        }

        return isNull(underExpectedKey) ? null : toBankAddress(castToMap(underExpectedKey));
    }

    /** The refusal both mappers give for a rail written under a spelling we do not accept. */
    public static BusinessRuleException wrongSpelling(String sent, String expectedKey) {
        return new BusinessRuleException("paymentMethods.networks",
            "Network \"%s\" must be written as \"%s\". This service accepts one spelling on its own API — the one it emits."
                .formatted(sent, expectedKey));
    }

    /** Removes and returns every entry whose key matches {@code canonicalKey} ignoring case. */
    public static Map<String, Object> takeAllCaseInsensitively(Map<String, Object> source,
                                                               String canonicalKey) {
        Map<String, Object> taken = new LinkedHashMap<>();

        if (isNull(source) || source.isEmpty()) {
            return taken;
        }

        source.keySet().stream()
            .filter(key -> key.equalsIgnoreCase(canonicalKey))
            .toList()
            .forEach(key -> taken.put(key, source.remove(key)));

        return taken;
    }

    /** A mutable copy, so promoting a rail can remove it without touching the caller's map. */
    public static Map<String, Object> mutableCopy(Map<String, Object> source) {
        return isNull(source) ? new HashMap<>() : new HashMap<>(source);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** A rail's address from its raw JSON object, or null when the required fields are missing. */
    public static BankPaymentAddressDTO toBankAddress(Map<String, Object> fields) {
        Object routingNumber = fields.get("routingNumber");
        Object accountNumber = fields.get("accountNumber");

        if (!(routingNumber instanceof String routing) || !(accountNumber instanceof String account)) {
            return null;
        }

        return new BankPaymentAddressDTO(routing, account);
    }

    /**
     * Refuses any network left over once the supported rails have been taken.
     *
     * <p>Rejecting rather than ignoring matters because this mapper runs behind an ObjectMapper with
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled: an unsupported network would otherwise be dropped
     * in silence, and the biller would believe a rail was live that nothing here understands.
     *
     * <p>A network becomes supported when its owner has published an embedding and it is added to
     * the configuration — never because a caller sent it.
     */
    public static void rejectUnsupported(Map<String, Object> leftovers) {
        if (isNull(leftovers) || leftovers.isEmpty()) {
            return;
        }

        throw new BusinessRuleException(
            "paymentMethods.networks",
            "Unsupported network(s): %s. This service supports fednow, rtp and ach."
                .formatted(String.join(", ", leftovers.keySet())));
    }

    /** The canonical, lower-case name of a rail, for map lookups. */
    public static String canonical(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

}
