/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.service;

import com.matera.x9qrcode.domain.dto.FormulaResultDTO;
import com.matera.x9qrcode.domain.vo.AdjustmentParametersVO;
import com.matera.x9qrcode.domain.vo.enumerated.FormulaEnum;

import java.time.OffsetDateTime;

public interface FormulaService {

    FormulaEnum getFormulaType();

    FormulaResultDTO calculate(OffsetDateTime dateForPayment,
                               OffsetDateTime dueDate,
                               Long originalAmount,
                               String currency,
                               AdjustmentParametersVO parameters);

}
