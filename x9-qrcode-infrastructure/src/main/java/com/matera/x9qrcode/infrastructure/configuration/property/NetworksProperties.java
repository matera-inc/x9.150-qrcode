/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.infrastructure.configuration.property;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How network names are written on the wire.
 *
 * <p>ANSI X9.150-2026 is not self-consistent about case. The normative JSON paths in §14.5 are
 * lowercase — {@code $.paymentMethods[].networks.fednow} — and the standard's own example payload
 * agrees. But §2.4, defining the notification's {@code $.payment.network}, calls its values
 * "all-uppercase" and then lists {@code FedNow}, which is not.
 *
 * <p>An ambiguous standard produces implementations that disagree, and a disagreement about a key
 * name is an interoperability failure with a trivial cause. So the emitted name is configuration:
 * when a partner turns out to expect {@code FedNow}, that is a config change and a restart, not a
 * release. Defaults follow the normative paths, which is the reading with no contradiction in it.
 *
 * <p>Input is always accepted case-insensitively regardless of what we emit — liberal in what we
 * accept, strict in what we send.
 */
@Data
public class NetworksProperties {

    /**
     * Canonical (lower-case) network name to the exact string to emit as the object key.
     *
     * <p>An explicit string rather than a case *style*, because a style cannot settle every case:
     * "camel" gives no answer for FedNow — {@code fedNow}? {@code FedNow}? — and the standard
     * happens to want neither.
     */
    private Map<String, String> emittedKeys = new LinkedHashMap<>(Map.of(
        "fednow", "fednow",
        "rtp", "rtp",
        "ach", "ach",
        "solana", "solana"
    ));

    /** The wire name for a canonical network, falling back to the canonical name itself. */
    public String emittedKeyFor(String canonicalName) {
        return emittedKeys.getOrDefault(canonicalName.toLowerCase(Locale.ROOT), canonicalName);
    }

}
