/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import java.util.List;

public record TipVO(
    Boolean allowed,
    TipRangeVO range,
    List<Integer> presets
) {

    public static TipVO noTip() {
        return new TipVO(false, null, null);
    }

    public static TipVO of(Boolean allowed, List<Integer> presets) {
        return new TipVO(allowed, null, presets);
    }

    public static TipVO of(Boolean allowed, Integer min, Integer max, List<Integer> presets) {
        return new TipVO(allowed, new TipRangeVO(min, max), presets);
    }

}
