/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;

import java.util.regex.Pattern;

import static java.util.Objects.isNull;

/**
 * One line of additional information on a bill: a {@code key} and its {@code value}.
 *
 * <p><b>{@code key} is the standard's name for it, and it does not mean a unique index.</b> These
 * are rows a person reads — "Partial payment · 5 000 USDC" — and a bill may legitimately carry the
 * same one several times, once per payment received. "Label" would describe them better, but the
 * name belongs to ANSI X9.150 and conformance outranks our preference in vocabulary.
 *
 * <p>The name is worth this paragraph because it caused the defect. Reading "key" as a map key made
 * a {@code Map<String, String>} look like the right structure, and the map silently discarded every
 * repeat: a biller recording three partial payments got one of them, with a 201 and nothing to say
 * the others were gone. Worse, the two paths disagreed about which survived — create kept the last,
 * patch kept the first.
 *
 * <p>It mattered because these rows are shown to the payer as the explanation of what is owed. A
 * dropped one leaves a history that does not add up to the amount being asked for.
 */
public record AdditionalInformationVO(String key, String value) {

    private static final Pattern PRINTABLE_ASCII = Pattern.compile("^[\\x20-\\x7E]*$");

    public AdditionalInformationVO {
        if (isNull(key) || key.isEmpty()) {
            throw new ValueObjectRuleException("Additional information key must not be empty.");
        }

        if (!PRINTABLE_ASCII.matcher(key).matches()) {
            throw new ValueObjectRuleException("Additional information key must be printable ASCII.");
        }

        if (!isNull(value) && !PRINTABLE_ASCII.matcher(value).matches()) {
            throw new ValueObjectRuleException("Additional information value must be printable ASCII.");
        }
    }

}
