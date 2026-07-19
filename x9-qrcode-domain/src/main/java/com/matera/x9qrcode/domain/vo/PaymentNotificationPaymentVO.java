/*
 * Copyright © 2026 Matera Systems, Inc.
 * Licensed under the Matera Source License v1.0 (source-available; not open source). See LICENSE.md.
 * Creating a Derivative Work from this file — by AI/ML generation or by manual re-implementation
 * based on it — is governed by that license (see the "Derivative Work" definition and Annex A).
 */
package com.matera.x9qrcode.domain.vo;

import com.matera.x9qrcode.domain.exception.ValueObjectRuleException;
import com.matera.x9qrcode.domain.vo.enumerated.NetworkEnum;

import java.util.List;

import static java.util.Objects.isNull;

public record PaymentNotificationPaymentVO(
    AmountVO amount,
    AmountVO tipAmount,
    String currency,
    NetworkEnum network,
    String transactionId
) {

    private static final List<NetworkEnum> NETWORKS_REQUIRING_TRANSACTION_ID = List.of(
        NetworkEnum.RTP,
        NetworkEnum.FEDNOW
    );

    public PaymentNotificationPaymentVO {
        if (isNull(amount)) {
            throw new ValueObjectRuleException("Payment Notification amount must not be null.");
        }

        if (NETWORKS_REQUIRING_TRANSACTION_ID.contains(network) && isNull(transactionId)) {
            throw new ValueObjectRuleException(
                "Payment Notification transactionId must not be null when network is RTP or FedNow.");
        }

        if (isNull(currency)) {
            throw new ValueObjectRuleException("Payment Notification currency must not be null.");
        }

        if (isNull(network)) {
            throw new ValueObjectRuleException("Payment Notification network must not be null.");
        }
    }
}
