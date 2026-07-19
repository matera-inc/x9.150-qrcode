/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.dto;

import java.util.List;

public record TipDTO(
    Boolean allowed,
    TipRangeDTO range,
    List<Integer> presets) {

    public static TipDTO noTip() {
        return new TipDTO(false, null, null);
    }

    public static TipDTO of(Boolean allowed, List<Integer> presets) {
        return new TipDTO(allowed, null, presets);
    }

    public static TipDTO of(Boolean allowed, Integer min, Integer max, List<Integer> presets) {
        return new TipDTO(allowed, new TipRangeDTO(min, max), presets);
    }

}
