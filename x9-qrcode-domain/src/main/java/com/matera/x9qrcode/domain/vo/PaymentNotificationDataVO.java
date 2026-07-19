/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;

import static java.util.Objects.isNull;

public record PaymentNotificationDataVO(
    PaymentNotificationPaymentVO payment,
    PaymentNotificationPayerVO payer,
    ExpectedDateVO expectedDate,
    BlockchainVO blockchain
) {

    public PaymentNotificationDataVO {
        if (isNull(payment)) {
            throw new ValueObjectRuleException("PaymentNotification Payment must not be null");
        }

        if (NetworkEnum.ACH.equals(payment.network()) && isNull(expectedDate)) {
            throw new ValueObjectRuleException("PaymentNotification ExpectedDate must not be null when network is ACH");
        }
    }

}
