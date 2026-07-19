/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.service.factory;

import com.matera.x9qrcode.domain.service.FormulaService;
import com.matera.x9qrcode.domain.vo.enumerated.FormulaEnum;

import lombok.RequiredArgsConstructor;

import java.util.Map;

@RequiredArgsConstructor
public class FormulaFactory {

    private final Map<FormulaEnum, FormulaService> formulaServiceMap;

    public FormulaService createFormula(FormulaEnum formula) {
        return formulaServiceMap.get(formula);
    }

}
