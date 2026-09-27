/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.request;

import com.matera.x9qrcode.app.dto.BankPaymentAddressDTO;
import com.matera.x9qrcode.domain.exception.BusinessRuleException;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.isNull;

/**
 * Accepts the standard's rails under any spelling.
 *
 * <p>ANSI X9.150-2026 contradicts itself on case: §14.5's normative JSON paths are lowercase
 * ({@code networks.fednow}) while §2.4 calls the notification's network value "all-uppercase" and
 * then lists {@code FedNow}. Implementers will read one or the other, so a payer may send
 * {@code fednow}, {@code FedNow} or {@code FEDNOW} and mean the same rail.
 *
 * <p>Anything the contract does not name falls into {@code additionalProperties}, so a rail sent
 * under a non-canonical spelling lands there and would silently stop being a bank rail — carried
 * verbatim instead of validated. This lifts it back out.
 *
 * <p>Liberal in what we accept, strict in what we emit: the response always uses the configured
 * key (lowercase by default, per the normative paths).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StandardRailKeys {

    /**
     * The rail's address if it was sent under any spelling of {@code canonicalKey}, removing it from
     * {@code additionalProperties} so it is not also carried as an uninterpreted network.
     *
     * @param typed the value the contract's own property captured, when the spelling matched exactly
     */
    public static BankPaymentAddressDTO bankAddress(BankPaymentAddressDTO typed,
                                                    String canonicalKey,
                                                    Map<String, Object> additionalProperties) {
        if (!isNull(typed)) {
            return typed;
        }

        return takeCaseInsensitively(additionalProperties, canonicalKey)
            .map(StandardRailKeys::toBankAddress)
            .orElse(null);
    }

    /** Removes and returns the entry whose key matches {@code canonicalKey} ignoring case. */
    public static Optional<Map<String, Object>> takeCaseInsensitively(Map<String, Object> source,
                                                                     String canonicalKey) {
        if (isNull(source) || source.isEmpty()) {
            return Optional.empty();
        }

        return source.keySet().stream()
            .filter(key -> key.equalsIgnoreCase(canonicalKey))
            .findFirst()
            .map(source::remove)
            .filter(Map.class::isInstance)
            .map(value -> castToMap(value));
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
