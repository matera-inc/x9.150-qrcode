/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.service;

import com.matera.x9qrcode.domain.dto.FormulaResultDTO;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;
import com.matera.x9qrcode.domain.vo.AdjustmentParametersVO;
import com.matera.x9qrcode.domain.vo.AmountVO;
import com.matera.x9qrcode.domain.vo.DiscountVO;
import com.matera.x9qrcode.domain.vo.enumerated.FormulaEnum;

import java.math.BigInteger;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Optional;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public class FixedDiscountLateFeeLinearInterestFormulaService implements FormulaService {

    @Override
    public FormulaEnum getFormulaType() {
        return FormulaEnum.FIXED_DISCOUNT_LATE_FEE_LINEAR_INTEREST;
    }

    @Override
    public FormulaResultDTO calculate(OffsetDateTime dateForPayment,
                                      OffsetDateTime dueDate,
                                      Long originalAmount,
                                      String currency,
                                      AdjustmentParametersVO parameters,
                                      OffsetDateTime feeStopsAt) {
        OffsetDateTime currentDate = getCurrentDate(dateForPayment);

        if (currentDate.isAfter(dueDate)) {
            // The fee stops growing at the QR Code's validUntil: the code cannot be paid after it,
            // and creation and PATCH bound the amount at exactly that instant (ADR-0005). A code
            // that expires on or before its due date never owes a fee.
            OffsetDateTime feeAt = nonNull(feeStopsAt) && currentDate.isAfter(feeStopsAt) ? feeStopsAt : currentDate;

            if (!feeAt.isAfter(dueDate)) {
                return null;
            }

            long daysLate = LateFeeBound.daysLate(dueDate, feeAt);

            // Exact arithmetic, then the 18-digit bound: refused rather than wrapped round a long
            // or emitted past what X9.150 allows, even if a stored bill escaped the creation check.
            BigInteger fee = LateFeeBound.fee(parameters.lateFees(), daysLate);

            return new FormulaResultDTO(
                AmountVO.requireAtMost18Digits(fee.add(BigInteger.valueOf(originalAmount))),
                AmountVO.requireAtMost18Digits(fee),
                parameters.lateFees().explanation(),
                currency,
                // The fee for k days applies up to and including dueDate + k × 24 h; one
                // millisecond later it is k + 1 days. That boundary is what the payee signs.
                dueDate.plus(Duration.ofDays(daysLate))
            );
        }

        // On or before the due date. The due date is an instant, and a payment up to and including
        // it is on time: the full amount, no late fee. Every discount ends at least a day before
        // it (daysBefore > 0), so at exactly dueDate none is eligible and no adjustment applies.
        if (isNull(parameters.discounts()) || parameters.discounts().isEmpty()) {
            return null;
        }

        Optional<DiscountVO> maximumEligibleDiscount = parameters.discounts().stream()
            .filter(discount -> !currentDate.isAfter(dueDate.minusDays(discount.daysBefore())))
            .max(Comparator.comparingLong(DiscountVO::discount));

        return maximumEligibleDiscount.map(
            discount -> new FormulaResultDTO(
                originalAmount - discount.discount(),
                Math.negateExact(discount.discount()),
                discount.explanation(), currency,
                // E itself: the instant eligibility ends (inclusive), so the advertised edge and
                // the accepted edge are the same instant.
                dueDate.minusDays(discount.daysBefore()))
        ).orElse(null);
    }

    /**
     * The instant given, exactly. This used to replace a date already in the past by a fresh read
     * of the clock, so the caller and the formula each had their own "now": the payload chose its
     * payment methods at one instant and priced them at another (INTERPRETATION I-14). Callers now
     * pass the instant they judge everything else at.
     */
    private OffsetDateTime getCurrentDate(OffsetDateTime dateForPayment) {
        return nonNull(dateForPayment) ? dateForPayment : DateTimeUtils.nowUTC();
    }

}
