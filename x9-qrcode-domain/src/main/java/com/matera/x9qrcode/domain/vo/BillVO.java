/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;
import com.matera.x9qrcode.domain.utils.DateTimeUtils;
import com.matera.x9qrcode.domain.vo.enumerated.PaymentTimingEnum;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public record BillVO(
    DescriptionVO description,
    OrderVO order,
    InvoiceVO invoice,
    TipVO tip,
    AmountDueVO amountDue,
    PaymentTimingEnum paymentTiming
) {

    public BillVO {
        if (isNull(description)) {
            throw new ValueObjectRuleException("Bill description must not be null.");
        }

        if (isNull(amountDue)) {
            throw new ValueObjectRuleException("Bill amount due must not be null.");
        }

        if (isNull(paymentTiming)) {
            throw new ValueObjectRuleException("Bill payment timing must not be null.");
        }

        if (isNull(invoice) && nonNull(amountDue.adjustment())) {
            throw new ValueObjectRuleException("Bill amount due adjustment can only be informed when invoice is not null.");
        }

        if (PaymentTimingEnum.DEFERRED.equals(paymentTiming) && isNull(invoice)) {
            throw new ValueObjectRuleException("Bill invoice must be informed when DEFERRED payment timing.");
        }

        AdjustmentVO adjustment = amountDue.adjustment();

        if (nonNull(adjustment)) {
            List<DiscountVO> discounts = adjustment.parameters().discounts();

            if (nonNull(discounts)) {
                IntStream.range(0, discounts.size()).forEach(index -> {
                    DiscountVO discount = discounts.get(index);
                    OffsetDateTime discountTargetDate = invoice.dueDate().minusDays(discount.daysBefore());

                    if (discount.discount() >= amountDue.currencyAmount().amount()) {
                        throw new ValueObjectRuleException(
                            "Bill discount at index %d. Must not be greater than or equal to amount due.".formatted(index));
                    }

                    if (discountTargetDate.isBefore(DateTimeUtils.nowUTC())) {
                        throw new ValueObjectRuleException(
                            "Bill discount target date at index %d. Must be after the current date.".formatted(index));
                    }
                });
            }
        }
    }

}
