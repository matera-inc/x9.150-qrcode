/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.app.dto;

import com.matera.x9qrcode.app.usecase.PartialInput;

import java.util.List;

public record TipUpdateDTO(
    Boolean allowed,
    TipRangeDTO range,
    PartialInput<List<Integer>> presets
) {

    public static TipUpdateDTO noTip() {
        return new TipUpdateDTO(false, null, PartialInput.absent());
    }

    public static TipUpdateDTO of(PartialInput<List<Integer>> presets) {
        return new TipUpdateDTO(true, null, presets);
    }

    public static TipUpdateDTO of(Integer min, Integer max, PartialInput<List<Integer>> presets) {
        return new TipUpdateDTO(true, new TipRangeDTO(min, max), presets);
    }

}
