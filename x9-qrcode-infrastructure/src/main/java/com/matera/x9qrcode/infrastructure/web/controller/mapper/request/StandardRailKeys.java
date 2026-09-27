/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.web.controller.mapper.request;

import com.matera.x9qrcode.domain.exception.BusinessRuleException;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Map;

import static java.util.Objects.isNull;

/**
 * Refuses any network this service does not interpret.
 *
 * <p>Casing needs no handling here. The OpenAPI contract declares the rails as {@code fednow},
 * {@code rtp} and {@code ach} — §14.5's normative paths, which the standard spells that way
 * throughout — so a caller working from the contract sends lower-case and binds to the generated
 * properties. Anything else, including a rail written {@code FedNow}, simply is not one of those
 * properties and lands in {@code additionalProperties}, where this refuses it by name. One rule
 * covers an unsupported network and a mis-spelled supported one, and the message names the
 * keys that do work.
 *
 * <p>(§2.4 does contradict itself on case, but that governs {@code $.payment.network} — the string
 * value in a payment notification, a different field from these object keys.)
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StandardRailKeys {

    /**
     * Refuses any network left over once the contract's own rail properties have been bound.
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
            "Unsupported network(s): %s. This service supports fednow, rtp, ach and solana."
                .formatted(String.join(", ", leftovers.keySet())));
    }

}
