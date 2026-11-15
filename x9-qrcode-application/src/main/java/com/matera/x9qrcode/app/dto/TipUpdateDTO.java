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

    /** Tips refused, with the presets the PATCH sent beside it, if any: the tip rule refuses them. */
    public static TipUpdateDTO notAllowed(PartialInput<List<Integer>> presets) {
        return new TipUpdateDTO(false, null, presets);
    }

    /** Tips refused, with the range the PATCH sent beside it: the tip rule refuses it. */
    public static TipUpdateDTO notAllowed(Integer min, Integer max, PartialInput<List<Integer>> presets) {
        return new TipUpdateDTO(false, new TipRangeDTO(min, max), presets);
    }

    public static TipUpdateDTO of(PartialInput<List<Integer>> presets) {
        return new TipUpdateDTO(true, null, presets);
    }

    public static TipUpdateDTO of(Integer min, Integer max, PartialInput<List<Integer>> presets) {
        return new TipUpdateDTO(true, new TipRangeDTO(min, max), presets);
    }

}
