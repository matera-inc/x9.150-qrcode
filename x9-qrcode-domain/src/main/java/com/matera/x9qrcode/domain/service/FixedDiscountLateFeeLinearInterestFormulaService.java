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
import com.matera.x9qrcode.domain.vo.DiscountVO;
import com.matera.x9qrcode.domain.vo.enumerated.FormulaEnum;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Optional;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public class FixedDiscountLateFeeLinearInterestFormulaService implements FormulaService {

    public static final long DAY_MILLIS = Duration.ofDays(1).toMillis();

    @Override
    public FormulaEnum getFormulaType() {
        return FormulaEnum.FIXED_DISCOUNT_LATE_FEE_LINEAR_INTEREST;
    }

    @Override
    public FormulaResultDTO calculate(OffsetDateTime dateForPayment,
                                      OffsetDateTime dueDate,
                                      Long originalAmount,
                                      String currency,
                                      AdjustmentParametersVO parameters) {
        OffsetDateTime currentDate = getCurrentDate(dateForPayment);

        if (currentDate.isAfter(dueDate)) {
            long daysLate = calculateDaysLate(dueDate, currentDate);
            long fee = (daysLate * parameters.lateFees().perDay()) + parameters.lateFees().fixed();

            return new FormulaResultDTO(
                originalAmount + fee,
                fee,
                parameters.lateFees().explanation(),
                currency,
                calculateValidUntil(currentDate)
            );
        }

        if (currentDate.isBefore(dueDate)) {
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
                    calculateValidUntil(dueDate, discount.daysBefore()))
            ).orElse(null);
        }

        throw new IllegalCallerException("Unable to calculate adjustments for bill.");
    }

    private OffsetDateTime getCurrentDate(OffsetDateTime dateForPayment) {
        OffsetDateTime nowUTC = DateTimeUtils.nowUTC();

        if (nonNull(dateForPayment) && !dateForPayment.isBefore(nowUTC)) {
            return dateForPayment;
        }

        return nowUTC;
    }

    private long calculateDaysLate(OffsetDateTime dueDate, OffsetDateTime currentDate) {
        long late = Duration.between(dueDate, currentDate).toMillis();

        return (late + DAY_MILLIS - 1) / DAY_MILLIS;
    }

    private static OffsetDateTime calculateValidUntil(OffsetDateTime date, Integer daysBefore) {
        return date.minusDays(daysBefore).withHour(23).withMinute(59).withSecond(59);
    }

    private OffsetDateTime calculateValidUntil(OffsetDateTime date) {
        return date.withHour(23).withMinute(59).withSecond(59);
    }

}
