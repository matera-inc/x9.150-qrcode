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
 * release. With nothing configured we emit the normative paths' own spelling, which is the reading
 * with no contradiction in it.
 *
 * <p>Postel's rule applies at the boundary, not inside it. A <b>payment notification from a
 * third-party payer</b> is read case-insensitively: we do not control that payer's implementation,
 * they may have read either half of the standard, and refusing a payment over the case of a string
 * would be absurd. A <b>create or patch request to our own API</b> is held to the exact configured
 * key: those callers are inside this ecosystem, the spelling we accept is the spelling we emit, and
 * accepting four spellings of a rail we will only ever write one way just hides drift until it
 * surfaces somewhere less forgiving.
 */
@Data
public class NetworksProperties {

    /**
     * Overrides only: canonical (lower-case) network name to the exact string to emit as the object
     * key. Empty by default, because the canonical name <em>is</em> the normative spelling — mapping
     * {@code "rtp"} to {@code "rtp"} would only restate the fallback below. An entry appears here
     * when a deployment needs to emit something else, e.g. {@code fednow: FedNow}.
     *
     * <p>An exact string rather than a case <em>style</em>, because a style cannot settle every
     * case: "camel" gives no answer for FedNow — {@code fedNow}? {@code FedNow}? — and the standard
     * happens to want neither.
     */
    private Map<String, String> emittedKeys = new LinkedHashMap<>();

    /**
     * The one spelling of a network this deployment uses — both the key it emits and, for our own
     * API, the key it requires on input. Defaults to the lower-case normative path from §14.5.
     *
     * @param networkName the network, in any case
     */
    public String keyFor(String networkName) {
        String canonical = canonical(networkName);

        return emittedKeys.getOrDefault(canonical, canonical);
    }

    /** The lower-case form used to look a network up, which is also its normative §14.5 spelling. */
    public static String canonical(String networkName) {
        return networkName.toLowerCase(Locale.ROOT);
    }

}
