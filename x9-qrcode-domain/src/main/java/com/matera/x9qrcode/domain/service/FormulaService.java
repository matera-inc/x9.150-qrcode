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

    /**
     * @param dateForPayment the instant to price at, used exactly as given; {@code null} means now.
     *     The caller decides it (the payload: the later of the payer's date and now; a notification:
     *     the instant it was received), so that whatever else it judges at that instant, such as
     *     which payment methods are still valid, agrees with the amount (INTERPRETATION I-14).
     * @param feeStopsAt the QR Code's {@code validUntil}: a late fee does not grow past it (ADR-0005).
     *     {@code null} leaves it unbounded in time; the result is still refused past 18 digits.
     */
    FormulaResultDTO calculate(OffsetDateTime dateForPayment,
                               OffsetDateTime dueDate,
                               Long originalAmount,
                               String currency,
                               AdjustmentParametersVO parameters,
                               OffsetDateTime feeStopsAt);

}
